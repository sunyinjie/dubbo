摘要：rpc是dubbo最核心的模块，定义了dubbo作为整个rpc框架最核心的接口和抽象实现，因此对于学习源码非常重要

------------------------------------------------------------
# 简单的类图
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/2_模块类图.png)
去除了一些非关键属性和方法定义，以及一些非核心类合接口

-----------------
# 核心类说明
## Protocol
服务协议。这是rpc模块中最核心的一个类，它定义了rpc的最主要的两个行为即：
1. provider暴露远程服务，即将调用信息发布到服务器上的某个URL上去，可以供消费者连接调用，一般是将某个service类的全部方法整体发布到服务器上。
2. consumer引用远程服务，即根据service的服务类和provider发布服务的URL转化为一个Invoker对象，消费者可以通过该对象调用provider发布的远程服务。
这其实概括了rpc的最为核心的职责，提供了多级抽象的实现、包装器实现等。
## AbstractProtocol
Protocol的顶层抽象实现类，它定义了这些属性：
1. exporterMap表示发布过的serviceKey和Exporter（远程服务发布的引用）的映射表；
2. invokers是一个Invoker对象的集合，表示层级暴露过远程服务的服务执行体对象集合。还提供了一个通用的服务发布销毁方法destroy，该方法是一个通用方法，它清空了两个集合属性，调用了所有invoker的destroy方法，也调用所有exporter对象的unexport方法。
## AbstractProxyProtocol
继承自AbstractProtoco的一个抽象代理协议类。它聚合了代理工厂ProxyFactory对象来实现服务的暴露和引用。
[源码](/dubbo-rpc/dubbo-rpc-api/src/main/java/com/alibaba/dubbo/rpc/protocol/AbstractProxyProtocol.java)
## ProtocolFilterWrapper
是一个Protocol的支持过滤器的装饰器。通过该装饰器的对原始对象的包装使得Protocol支持可扩展的过滤器链，已经支持的包括ExceptionFilter、ExecuteLimitFilter和TimeoutFilter等多种支持不同特性的过滤器。
[源码](/dubbo-rpc/dubbo-rpc-api/src/main/java/com/alibaba/dubbo/rpc/protocol/ProtocolFilterWrapper.java)
## ProtocolListenerWrapper
一个支持监听器特性的Protocal的包装器。支持两种监听器的功能扩展，分别是：ExporterListener是远程服务发布监听器，可以监听服务发布和取消发布两个事件点；InvokerListener是服务消费者引用调用器的监听器，可以监听引用和销毁两个事件方法。支持可扩展的事件监听模型，目前只提供了一些适配器InvokerListenerAdapter、ExporterListenerAdapter以及简单的过期服务调用监听器DeprecatedInvokerListener。开发者可自行扩展自己的监听器。
[源码](/dubbo-rpc/dubbo-rpc-api/src/main/java/com/alibaba/dubbo/rpc/protocol/ProtocolListenerWrapper.java)
## ProxyFactory
dubbo的代理工厂。定义了两个接口分别是：
1. getProxy根据invoker目标接口的代理对象，一般是消费者获得代理对象触发远程调用；
2. getInvoker方法将代理对象proxy、接口类type和远程服务的URL获取执行对象Invoker，往往是提供者获得目标执行对象执行目标实现调用。
AbstractProxyFactory是其抽象实现，提供了getProxy的模版方法实现，使得可以支持多接口的映射。dubbo最终内置了两种动态代理的实现，分别是jdkproxy和javassist。
默认的实现使用javassist。为什么选择javassist，梁飞选型的时候做过性能测试对比分析 [参考](http://javatar.iteye.com/blog/814426/)
## Invoker
该接口是服务的执行体。
它有获取服务发布的URL，服务的接口类等关键属性的行为；还有核心的服务执行方法invoke，执行该方法后返回执行结果Result，而传递的参数是调用信息Invocation。
该接口有大量的抽象和具体实现类。AbstractProxyInvoker是基于代理的执行器抽象实现，AbstractInvoker是通用的抽象实现。

--------------------
# 服务发布流程
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/2_服务发布流程.jpg)
首先ServiceConfig类拿到对外提供服务的实际类ref(如：HelloWorldImpl),然后通过ProxyFactory类的getInvoker方法使用ref生成一个AbstractProxyInvoker实例，到这一步就完成具体服务到Invoker的转化。接下来就是Invoker转换到Exporter的过程。
Dubbo处理服务暴露的关键就在Invoker转换到Exporter的过程(如上图中的红色部分)，后面以Dubbo和RMI这两种典型协议的实现来进行说明。

------------------
# 服务引用流程
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/2_服务引用流程.jpg)
首先ReferenceConfig类的init方法调用Protocol的refer方法生成Invoker实例(如上图中的红色部分)，这是服务消费的关键。接下来把Invoker转换为客户端需要的接口(如：HelloWorld)。
关于每种协议如RMI/Dubbo/Web service等它们在调用refer方法生成Invoker实例的细节和上一章节所描述的类似。
