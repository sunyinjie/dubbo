# Router
路由,这个路由是个很有意思的词汇.因为前端的路由和后端的路由他们是不同的,但是思想是基本一致的.鉴于很多技术文章都有一个诟病,就是只讲概念,却不讲应用场景,其实Router在应用隔离,读写分离,灰度发布中都有它的影子.因此本篇用灰度发布的例子来做前期的铺垫
# 灰度发布
> 灰度发布是指在黑与白之间，能够平滑过渡的一种发布方式。AB test就是一种灰度发布方式，让一部分用户继续用A，一部分用户开始用B，如果用户对B没有什么反对意见，那么逐步扩大范围，把所有用户都迁移到B上面来。灰度发布可以保证整体系统的稳定，在初始灰度的时候就可以发现、调整问题，以保证其影响度。
说人话就是,你发布应用的时候,不停止对外的服务,也就是让用户感觉不到你在发布.那么下面演示一下灰度发布

1. 首先在192.168.56.2和192.168.56.3两台机器上启动Provider,然后启动Consumer,如下图
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_1.png)

2. 假设我们要升级192.168.56.2服务器上的服务,接着我们去dubbo的控制台配置路由,切断192.168.56.2的流量,配置完成并且启动之后,就看到此时只调用192.168.56.3的服务
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_2.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_3.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_4.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_5.png)

3. 假设此时你在192.168.56.2服务器升级服务,升级完成后再次将启动服务.
4. 由于服务已经升级完成,那么我们此时我们要把刚才的禁用路由取消点,于是点了禁用,但是此时dubbo的这个管理平台就出现了bug,如下图所示
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_6.png)

惊奇的发现点了禁用,数据就变两条了,继续点禁用,还是两条,而且删除还删除不了,这样就很蛋疼了...但是一直删不了也不是办法,解决办法也是有的,那就是去zookeeper上删除节点
5. 用这个idea的zookeeper插件,只要将这个zookeeper节点删除
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_7.png)

然后刷新控制台的界面,如下图那么就只剩下一条了
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_8.png)

6. 那么此时我们再看控制台的输出,已经恢复正常,整个灰度发布流程结束
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_9.png)

先来看看Router的继承体系图
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_10.png)

从图中可以看出,他有三个实现类,分别是ConditionRouter,MockInvokersSelector,ScriptRouter
MockInvokersSelector在集群容错架构设计中提到这里就暂时不多做叙述
ScriptRouter在dubbo的测试用例中就有用到,这个类的源码不多,也就124行.引用官网的描述
> 脚本路由规则 支持 JDK 脚本引擎的所有脚本，比如：javascript, jruby, groovy 等，通过 type=javascript 参数设置脚本类型，缺省为 javascript。

当然看到这里可能你可能还是没有感觉出这个类有什么不可替代的作用,你注意一下这个类中有个ScriptEngine的属性,那么我可以举一个应用场景给你
假如有这么个表达式如下:
> double d = (1+1-(2-4)*2)/24;//没有问题 
"(1+1-(2-4)*2)/24"//但是假如这个表达式是这样的字符串格式,或者更复杂的运算,那么你就不好处理了,然后这个ScriptEngine类的eval方法就能很好处理这类字符串表达式的问题

本篇主要讲讲ConditionRouter(条件路由),条件路由主要就是根据dubbo管理控制台配置的路由规则来过滤相关的invoker,当我们对路由规则点击启用的时候,就会触发RegistryDirectory类的notify方法
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_11.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_12.png)

其实我觉得看技术类文章更重要的是看分析的思路,看的是思考过程,比如为什么这个notify方法传入的是List<URL>呢?如果看过前两篇集群容错架构设计和Directory就明白,我的分析过程都是以官方文档为依据,所以这个问题的答案自然也在官方文档.下面引用一段官网文档的描述
> 所有配置最终都将转换为 URL 表示，并由服务提供方生成，经注册中心传递给消费方，各属性对应 URL 的参数，参见配置项一览表中的 "对应URL参数" 列

其实对于Router来说,我们最关心的就是他是怎么过滤的.所以下面这些流程代码我们先走一遍
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_13.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_14.png)

这个条件路由有一个特点,就是他的getUrl是有值的,同时这里分享一个IDEA中debug查看表达式内容的技巧,比如router.getUrl()表达式的值,如下图所示
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_15.png)

从这里我们看到,此时实现类是ConditionRouter,由于接下来的逻辑如果直接让大家看源码图可能不够清晰,所以我又把这个核心的筛选过程用了一个高清无码图,并且用序号标注
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_16.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_17.png)

最后的筛选结果如下,因为我们在管理后台配置了禁用192.168.56.2,所以最后添加进invokers的就只有192.168.56.3
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_18.png)
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/13_19.png)

---
> 转载
> 作者： 肥朝
> 链接： http://www.jianshu.com/p/278e782eef85