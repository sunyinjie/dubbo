# Cluster
再回顾一遍官网的对于集群容错的架构设计图
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_1.png)

之前我反复强调三个关键词**Directory**,**Router**,**LoadBalance**,但是换个角度而言,其实也可以是四个关键词,还有一个就是**Cluster**
首先我们先看看官网的介绍,这个**Cluster**到底是干嘛的
> Cluster 将 Directory 中的多个 Invoker 伪装成一个 Invoker，对上层透明，伪装过程包含了容错逻辑，调用失败后，重试另一个

简单来说,就是**应对出错情况采取的策略**,当然这么说还是有些不准确的.那我们再来看看这个接口及其继承体系图
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_2.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_3.png)

看继承体系图中,我们也可以看到了他有9个实现类,换个角度来说,就是有9中应对策略,下面的逐一简介和分析其内部实现(以下逻辑都在Cluster接口的join方法)
## MergeableCluster
这个主要用在**分组聚合**中,我们来看一下官网的介绍
> 按组合并返回结果 ，比如菜单服务，接口一样，但有多种实现，用group区分，现在消费方需从每种group中调用一次返回结果，合并结果返回，这样就可以实现聚合菜单项。

该类的源码是这么多实现类中最多的.代码全部贴出来篇幅就太大了,用一个流程图来告诉大家这个类是干什么的
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_4.png)

大家可以根据我这个流程图对照源码理一下思路,这三个判断也是很容易看出来的,比如

+ URL中merger属性是否有值
```
String merger = getUrl().getMethodParameter( invocation.getMethodName(), Constants.MERGER_KEY );
if (ConfigUtils.isEmpty(merger) ) { // 如果方法不需要Merge，退化为只调一个Group
    for(final Invoker<T> invoker : invokers ) {
        if (invoker.isAvailable()) {
            return invoker.invoke(invocation);
        }
    }
    return invokers.iterator().next().invoke(invocation);
}
```
是否指定合并方法`merger.startsWith(".")`,为什么是否指定方法是这么判断的呢?因为指定合并方法在xml配置中就是要以"."开头,例如
```
<dubbo:reference interface="com.xxx.MenuService" group="*">
    <dubbo:method name="getMenuItems" merger=".addAll" />
</dubbo:service>
```
是否默认merge策略
```
if (ConfigUtils.isDefault(merger)) {
    resultMerger = MergerFactory.getMerger(returnType);
} else {
    resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
}
```
其实这个`ConfigUtils.isDefault`从我们使用上都可以推断出他的实现
```
public static boolean isDefault(String value) {
    return "true".equalsIgnoreCase(value) 
            || "default".equalsIgnoreCase(value);
}
```
## AvailableCluster
从单词Available意思就知道,这个是调用可用的.代码实现逻辑也比较简单
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_5.png)

源码的这个写法是比较优雅的,遍历所有的Invokers判断invoker.isAvalible,只要一个有为true直接调用返回，否则就抛出异常.
## ForkingCluster
引用官网的介绍
> 并行调用多个服务器，只要一个成功即返回。通常用于实时性要求较高的读操作，但需要浪费更多服务资源。可通过 forks="2" 来设置最大并行数。

看看源码的实现
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_6.png)

这里就涉及到了线程池,但是由于本篇篇幅会很大,所以线程池的会专门有个专题来讲
至于这个execute方法是干嘛的,这个时候可以用Dash工具来查找
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_7.png)

## FailfastCluster
引用官网的介绍
> 快速失败，只发起一次调用，失败立即报错。通常用于非幂等性的写操作，比如新增记录。

源码实现
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_8.png)

这个的实现应该是实现类里面最简单的,就是调用invoke,调用失败就抛出异常,但是这个却是面试问得最多的,请留意后面的面试题
## MockClusterWrapper
这个主要用在本地伪装上,让我们来看官网描述
> 本地伪装通常用于服务降级，比如某验权服务，当服务提供方全部挂掉后，客户端不抛出异常，而是通过 Mock 数据返回授权失败

同样我也用一个流程图来描述他的逻辑
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_9.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_10.png)

## FailoverCluster
这个是本篇的重点,也是dubbo里面容错方案的缺省值.让我们来看官网介绍
> 失败自动切换，当出现失败，重试其它服务器。通常用于读操作，但重试会带来更长延迟。可通过 retries="2" 来设置重试次数(不含第一次)

也就是说,默认的情况下,如果第一次调用失败,会重试两次,也就是一共是调用三次.所以len = 3的(经过调试确实是3).
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_11.png)

这个时候面试题就来了,注意我上面提到的FailfastCluster,面试会问,dubbo中"读接口"和"写接口"有什么区别?答案也是很明显的,因为默认FailoverCluster会重试,如果是"写"类型的接口,如果在网络抖动情况下写入多个值,所以"写"类型的接口要换成FailfastCluster
## FailbackCluster
> 失败自动恢复，后台记录失败请求，定时重发。通常用于消息通知操作。

![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_12.png)
这个定时重发的逻辑如下,由于还是涉及到线程池,这个必须要有些知识铺垫,后面会讲解,不过我们可以先看一下api文档
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_13.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_14.png)

## FailsafeCluster
官网介绍
> 失败安全，出现异常时，直接忽略。通常用于写入审计日志等操作。
这个的逻辑就很简单了,官网的这句话就已经介绍完了
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_15.png)

## BroadcastCluster
老规矩,还是官网介绍
> 广播调用所有提供者，逐个调用，任意一台报错则报错。通常用于通知所有提供者更新缓存或日志等本地资源信息。
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/14_16.png)

这个的逻辑也是很简单,遍历所有Invokers, 逐个调用每个调用,有异常就catch,以免影响到剩下的调用,那这个和AvailableCluster有什么区别?当然有啊,你仔细看就知道,BroadcastCluster是要遍历调用完全部的invoker,而AvailableCluster是只要有一个调用就return了.

---
> 转载
> 作者： 肥朝
> 链接： http://www.jianshu.com/p/e0235110fb74