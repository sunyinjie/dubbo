# Directory
首先来看一下directory接口的实现类,他主要有两个实现类,一个是 StaticDirectory ,一个是 RegistryDirectory
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/12_1.png)

其实这个也是道很好的面试题,他还是可以区分三种人

+ 一种是停留在使用层面的,没看过源码的,因此他不会懂得这两个实现类
+ 第二种是看过官网如下描述,因此认为directory的实现类都是动态变化的
> Directory 代表多个 Invoker，可以把它看成 List<Invoker> ,但与 List 不同的是，它的值可能是动态变化的，比如注册中心推送变更
+ 第三种则是有看过源码的,其实从StaticDirectory中的Static关键词来看,就知道,这个其实是不会动态变化的,从下图知道,他的Invoker是通过构造函数传入,StaticDirectory用得比较少,主要用在服务对多注册中心的引用
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/12_2.png)

本文介绍的重点是RegistryDirectory,首先来看看他的继承结构
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/12_3.png)

这个NotifyListener中的notify方法就是注册中心的回调,也就是它之所以能根据注册中心动态变化的根源所在.
下面放一个上篇中集群容错的整体架构中的一个图唤醒大家的对Directory记忆
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/12_4.png)

从中可以看出,Directory获取invoker是从methodInvokerMap中获取的,从这个图也可以看出,这些主要都是读操作,那它的写操作是在什么时候写的呢?就是在回调方法notify的时候操作的
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/12_5.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/12_6.png)

也就是注册中心有变化,则更新methodInvokerMap和urlInvokerMap的值(这个后面讲服务引用原理的时候会再提一下),**这就是官网提到的它的值可能是动态变化的，比如注册中心推送变更的原因所在.**

---
> 转载
> 作者： 肥朝
> 链接： http://www.jianshu.com/p/3d47873f8ad3