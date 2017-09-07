/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.curator.framework.recipes.locks;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.curator.RetryLoop;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.WatcherRemoveCuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.utils.PathUtils;
import org.apache.curator.utils.ThreadUtils;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LockInternals
{
    private final WatcherRemoveCuratorFramework     client;
    private final String                            path;
    private final String                            basePath;
    private final LockInternalsDriver               driver;
    private final String                            lockName;
    private final AtomicReference<RevocationSpec>   revocable = new AtomicReference<RevocationSpec>(null);
    private final CuratorWatcher                    revocableWatcher = new CuratorWatcher()
    {
        @Override
        public void process(WatchedEvent event) throws Exception
        {
            if ( event.getType() == Watcher.Event.EventType.NodeDataChanged )
            {
                checkRevocableWatcher(event.getPath());
            }
        }
    };

    private final Watcher watcher = new Watcher()
    {
        @Override
        public void process(WatchedEvent event)
        {
            notifyFromWatcher();
        }
    };

    private volatile int    maxLeases;

    static final byte[]             REVOKE_MESSAGE = "__REVOKE__".getBytes();

    /**
     * Attempt to delete the lock node so that sequence numbers get reset
     *
     * @throws Exception errors
     */
    public void clean() throws Exception
    {
        try
        {
            client.delete().forPath(basePath);
        }
        catch ( KeeperException.BadVersionException ignore )
        {
            // ignore - another thread/process got the lock
        }
        catch ( KeeperException.NotEmptyException ignore )
        {
            // ignore - other threads/processes are waiting
        }
    }

    LockInternals(CuratorFramework client, LockInternalsDriver driver, String path, String lockName, int maxLeases)
    {
        this.driver = driver;   //内部锁驱动
        this.lockName = lockName;  //锁名
        this.maxLeases = maxLeases;

        this.client = client.newWatcherRemoveCuratorFramework();  //curatorframeworkImpl 子类
        this.basePath = PathUtils.validatePath(path);  //path
        this.path = ZKPaths.makePath(path, lockName);  //   /path/lockName
    }

    synchronized void setMaxLeases(int maxLeases)
    {
        this.maxLeases = maxLeases;
        notifyAll();
    }

    void makeRevocable(RevocationSpec entry)
    {
        revocable.set(entry);
    }

    final void releaseLock(String lockPath) throws Exception
    {
        client.removeWatchers();
        revocable.set(null);
        deleteOurPath(lockPath);
    }

    CuratorFramework getClient()
    {
        return client;
    }

    public static Collection<String> getParticipantNodes(CuratorFramework client, final String basePath, String lockName, LockInternalsSorter sorter) throws Exception
    {
        List<String>        names = getSortedChildren(client, basePath, lockName, sorter);
        Iterable<String>    transformed = Iterables.transform
            (
                names,
                new Function<String, String>()
                {
                    @Override
                    public String apply(String name)
                    {
                        return ZKPaths.makePath(basePath, name);
                    }
                }
            );
        return ImmutableList.copyOf(transformed);
    }

    public static List<String> getSortedChildren(CuratorFramework client, String basePath, final String lockName, final LockInternalsSorter sorter) throws Exception
    {
        List<String> children = client.getChildren().forPath(basePath); //path 路径
        List<String> sortedList = Lists.newArrayList(children);
        Collections.sort
        (
            sortedList,
            new Comparator<String>()
            {
                @Override
                public int compare(String lhs, String rhs)
                {
                    //str ： uuid - lock - sequence  //lockname ： lock
                    //return 回来为sequence 进行排序
                    return sorter.fixForSorting(lhs, lockName).compareTo(sorter.fixForSorting(rhs, lockName));
                }
            }
        );
        return sortedList;
    }

    public static List<String> getSortedChildren(final String lockName, final LockInternalsSorter sorter, List<String> children)
    {
        List<String> sortedList = Lists.newArrayList(children);
        Collections.sort
        (
            sortedList,
            new Comparator<String>()
            {
                @Override
                public int compare(String lhs, String rhs)
                {
                    return sorter.fixForSorting(lhs, lockName).compareTo(sorter.fixForSorting(rhs, lockName));
                }
            }
        );
        return sortedList;
    }

    List<String> getSortedChildren() throws Exception
    {
        return getSortedChildren(client, basePath, lockName, driver);
    }

    String getLockName()
    {
        return lockName;
    }

    LockInternalsDriver getDriver()
    {
        return driver;
    }

    String attemptLock(long time, TimeUnit unit, byte[] lockNodeBytes) throws Exception
    {
        final long      startMillis = System.currentTimeMillis();  //记录开始时间，计算需要等待的时间
        final Long      millisToWait = (unit != null) ? unit.toMillis(time) : null;
        final byte[]    localLockNodeBytes = (revocable.get() != null) ? new byte[0] : lockNodeBytes;
        int             retryCount = 0;

        String          ourPath = null;
        boolean         hasTheLock = false;
        boolean         isDone = false;
        while ( !isDone )
        {
            isDone = true;

            try
            {       //path 为传入path/lockname
                   //driver 默认为 StandardLockInternalsDriver
                   //构建锁的节点，节点createModel为 EPHEMERAL_SEQUENTIAL
                  //our path : 创建protect的节点  /path/uuid - localname - sequence
                 // 只有相应uuid的节点才能修改此节点的信息
                ourPath = driver.createsTheLock(client, path, localLockNodeBytes);
                hasTheLock = internalLockLoop(startMillis, millisToWait, ourPath);
            }
            catch ( KeeperException.NoNodeException e )
            {
                // gets thrown by StandardLockInternalsDriver when it can't find the lock node
                // this can happen when the session expires, etc. So, if the retry allows, just try it all again
                if ( client.getZookeeperClient().getRetryPolicy().allowRetry(retryCount++, System.currentTimeMillis() - startMillis, RetryLoop.getDefaultRetrySleeper()) )
                {
                    isDone = false;
                }
                else
                {
                    throw e;
                }
            }
        }

        if ( hasTheLock )
        {
            return ourPath;
        }

        return null;
    }

    private void checkRevocableWatcher(String path) throws Exception
    {
        RevocationSpec  entry = revocable.get();
        if ( entry != null )
        {
            try
            {
                byte[]      bytes = client.getData().usingWatcher(revocableWatcher).forPath(path);
                if ( Arrays.equals(bytes, REVOKE_MESSAGE) )
                {
                    entry.getExecutor().execute(entry.getRunnable());
                }
            }
            catch ( KeeperException.NoNodeException ignore )
            {
                // ignore
            }
        }
    }

    private boolean internalLockLoop(long startMillis, Long millisToWait, String ourPath) throws Exception
    {
        boolean     haveTheLock = false;
        boolean     doDelete = false;
        try
        {
            if ( revocable.get() != null )
            {
                client.getData().usingWatcher(revocableWatcher).forPath(ourPath);
            }

            while ( (client.getState() == CuratorFrameworkState.STARTED) && !haveTheLock )
            {
                // uuid - localname - sequence
                // ourPath ：/path/uuid - localname - sequence
                //basePath ： /path
                //return 回来为 按照sequence 进行排序
                List<String>        children = getSortedChildren();
                //sequenceNodeName ： uuid - localname - sequence
                String              sequenceNodeName = ourPath.substring(basePath.length() + 1); // +1 to include the slash

                //获取 getsTheLock:是否获取锁   pathToWatch:获取锁的 sequenceNode
                PredicateResults    predicateResults = driver.getsTheLock(client, children, sequenceNodeName, maxLeases);
                if ( predicateResults.getsTheLock() )
                {
                    haveTheLock = true;
                }
                else
                {
                    //  获取锁的path
                    String  previousSequencePath = basePath + "/" + predicateResults.getPathToWatch();

                    synchronized(this)
                    {
                        try 
                        {
                            // use getData() instead of exists() to avoid leaving unneeded watchers which is a type of resource leak
                            // 如果该节点变化，则使用watch
                            //notifyFromWatcher  唤醒所有的children，重新竞争
                            client.getData().usingWatcher(watcher).forPath(previousSequencePath);
                            if ( millisToWait != null )
                            {
                                millisToWait -= (System.currentTimeMillis() - startMillis);
                                startMillis = System.currentTimeMillis();
                                //时间超时，删除节点，不竞争下次锁
                                if ( millisToWait <= 0 )
                                {
                                    doDelete = true;    // timed out - delete our node
                                    break;
                                }

                                wait(millisToWait);  //睡眠相应的时间
                            }
                            else
                            {
                                //睡眠
                                wait();
                            }
                        }
                        catch ( KeeperException.NoNodeException e ) 
                        {
                            // it has been deleted (i.e. lock released). Try to acquire again
                        }
                    }
                }
            }
        }
        catch ( Exception e )
        {
            ThreadUtils.checkInterrupted(e);
            doDelete = true;
            throw e;
        }
        finally
        {
            //出现异常或者超时，删除此节点
            if ( doDelete )
            {
                deleteOurPath(ourPath);
            }
        }
        return haveTheLock;
    }

    private void deleteOurPath(String ourPath) throws Exception
    {
        try
        {
            client.delete().guaranteed().forPath(ourPath);
        }
        catch ( KeeperException.NoNodeException e )
        {
            // ignore - already deleted (possibly expired session, etc.)
        }
    }

    private synchronized void notifyFromWatcher()
    {
        notifyAll();
    }
}
