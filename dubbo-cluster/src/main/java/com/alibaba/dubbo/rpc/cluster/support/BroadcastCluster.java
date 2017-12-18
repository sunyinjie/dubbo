/*
 * Copyright 1999-2012 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * BroadcastCluster
 * 广播调用所有providers，重点：
 * 1、逐个调用
 * 2、任意1台报错，则报错
 * 3、一般用于通知所有提供者更新缓存或日志等本地资源信息
 * 4、和availableCluster有点相似也有点不同
 *    availableCluster是逐个调用所有可用的provider，1个成功就直接返回
 *    broadcastCluster是逐个调用所有******所有所有所有的provider，全部调用完成才返回，调用过程中任意1个出错，就失败了
 * @author william.liangf
 */
public class BroadcastCluster implements Cluster {

    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        return new BroadcastClusterInvoker<T>(directory);
    }

}