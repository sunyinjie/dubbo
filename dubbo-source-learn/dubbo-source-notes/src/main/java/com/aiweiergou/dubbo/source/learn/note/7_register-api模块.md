摘要: 
dubbo中的注册中心是dubbo中重要的组成部分，dubbo的服务信息存储，服务治理等特性都是基于它实现的。本文将带着大家一起来看看dubbo-register-api和dubbo-register-default两个模块的源码。

---
# 核心类图
![](/dubbo-source-learn/dubbo-source-notes/src/main/resources/img/7_核心类图.jpg)
该图是包含了dubbo-registry-api和dubbo-registry-default两个模块整合的简化类图，只描述了核心的类与类的关系，为了清晰明了，去除了方法和属性的描述，也忽略了类所处的包，将其放在一个类图中。

注册中心核心的职责就是注册和注销URL，订阅和取消订阅URL，还有包括查询符合条件的已注册的URL列表等职责，类图中的接口和类就是围绕核心职责的实现。

---
# 核心源码分析
## RegistryProtocol
该类是注册中心的协议，即协议名称为registry的协议。这是整个注册中心启用的入口，通过该类整合了Protocol、Cluster、Directory 和Registry这几个组件。因此它作为入口我们非常有必有学习它的源码。

当我们在配置发布服务和引用服务的时候，若配置使用了注册中心，则会将原来的URL替换为一个protocol名称为registry的URL，并且会保留原始的URL在新的URL参数中。则通过dubbo的SPI机制获取到的Protocol接口的实现类便是RegistryProtocol，因此变回进入到该类的核心方法中，我们一起来看看这些核心方法的源码实现。
### 发布服务方法 export
```
public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        //export invoker
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker);
        //registry provider
        final Registry registry = getRegistry(originInvoker);
        final URL registedProviderUrl = getRegistedProviderUrl(originInvoker);
        registry.register(registedProviderUrl);
        // 订阅override数据
        // FIXME 提供者订阅时，会影响同一JVM即暴露服务，又引用同一服务的的场景，因为subscribed以服务名为缓存的key，导致订阅信息覆盖。
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(registedProviderUrl);
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);
        //保证每次export都返回一个新的exporter实例
        return new Exporter<T>() {
            public Invoker<T> getInvoker() {
                return exporter.getInvoker();
            }

            public void unexport() {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
                try {
                    registry.unregister(registedProviderUrl);
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
                try {
                    overrideListeners.remove(overrideSubscribeUrl);
                    registry.unsubscribe(overrideSubscribeUrl, overrideSubscribeListener);
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker) {
        String key = getCacheKey(originInvoker);
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            synchronized (bounds) {
                exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
                if (exporter == null) {
                    final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                    exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);
                    bounds.put(key, exporter);
                }
            }
        }
        return (ExporterChangeableWrapper<T>) exporter;
    }

    /**
     * 对修改了url的invoker重新export
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> void doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
            return;//不存在是异常场景 直接返回
        } else {
            final Invoker<T> invokerDelegete = new InvokerDelegete<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegete));
        }
    }

    /**
     * 根据invoker的地址获取registry实例
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();
        if (Constants.REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            String protocol = registryUrl.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_DIRECTORY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(Constants.REGISTRY_KEY);
        }
        return registryFactory.getRegistry(registryUrl);
    }
```
1. 会先调用private方法protocol实现本地服务发布。该方法是调用了protocol.export()方法实现真正的服务发布，而这个protocol属性是SPI机制中自动注入进来的，它注入的是原始的网络协议的实现类，比如默认的DubboProtocol。并且支持缓存，避免发布多次。
2. 根据url获得Registry对象。会调用registryFactory.getRegistry(registryUrl)获得对象。而registryFactory属性也是通过SPI机制自动注入进来的。比如注入默认的DubboRegistryFactory对象。
3. 注册提供者URL到registry中。调用registry.register(registedProviderUrl)实现提供者URL的注册。原始服务的URL被编码并作为名为export的参数加入到RegistryURL中。
4. 订阅协议替换为provider的URL。调用方法registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener)订阅URL，并添加监听器OverrideListener。该监听器主要监听变化，当检测到URL变化的时候会调用方法doChangeLocalExport更新服务发布。
5. 最后返回一个代理原始exporter对象的代理Exporter对象。该对象在unexport方法同时会注销订阅，删除监听器等操作。
### 服务引用方法 refer
```
public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        url = url.setProtocol(url.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_REGISTRY)).removeParameter(Constants.REGISTRY_KEY);
        Registry registry = registryFactory.getRegistry(url);
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }
        // group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        String group = qs.get(Constants.GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((Constants.COMMA_SPLIT_PATTERN.split(group)).length > 1
                    || "*".equals(group)) {
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }
        return doRefer(cluster, registry, type, url);
    }
    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }
    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        URL subscribeUrl = new URL(Constants.CONSUMER_PROTOCOL, NetUtils.getLocalHost(), 0, type.getName(), directory.getUrl().getParameters());
        if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            registry.register(subscribeUrl.addParameters(Constants.CATEGORY_KEY, Constants.CONSUMERS_CATEGORY,
                    Constants.CHECK_KEY, String.valueOf(false)));
        }
        directory.subscribe(subscribeUrl.addParameter(Constants.CATEGORY_KEY,
                Constants.PROVIDERS_CATEGORY
                        + "," + Constants.CONFIGURATORS_CATEGORY
                        + "," + Constants.ROUTERS_CATEGORY));
        return cluster.join(directory);
    }
```
发布服务流程:
1. 将原始URL的协议转为registry协议。
2. 获得Registry对象。
3. 调用doRefer()方法引用服务。
4. 创建RegistryDirectory对象，并设置相关值。
5. 构造消费者subscribeUrl。协议名称设置为consumer。
6. 注册中心注册URL。代码为registry.register(subscribeUrl)。
7. 目录服务注册URL。RegistryDirectory.subscribe();
8. 调用cluster.join()方法返回一个支持集群功能的invoker。cluster.join(directory)；

这里用到了RegistryDirectory，因此我们要继续探究一下该类的源码。
## RegistryDirectory
### Registry
```
public interface Registry extends Node, RegistryService {
}
```
该接口注册中心，它继承自接口Node和RegistryService，它本身没有定义任何方法。
### RegistryService
```
public interface RegistryService {

    /**
     * 注册数据，比如：提供者地址，消费者地址，路由规则，覆盖规则，等数据。
     * <p>
     * 注册需处理契约：<br>
     * 1. 当URL设置了check=false时，注册失败后不报错，在后台定时重试，否则抛出异常。<br>
     * 2. 当URL设置了dynamic=false参数，则需持久存储，否则，当注册者出现断电等情况异常退出时，需自动删除。<br>
     * 3. 当URL设置了category=routers时，表示分类存储，缺省类别为providers，可按分类部分通知数据。<br>
     * 4. 当注册中心重启，网络抖动，不能丢失数据，包括断线自动删除数据。<br>
     * 5. 允许URI相同但参数不同的URL并存，不能覆盖。<br>
     *
     * @param url 注册信息，不允许为空，如：dubbo://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     */
    void register(URL url);

    /**
     * 取消注册.
     * <p>
     * 取消注册需处理契约：<br>
     * 1. 如果是dynamic=false的持久存储数据，找不到注册数据，则抛IllegalStateException，否则忽略。<br>
     * 2. 按全URL匹配取消注册。<br>
     *
     * @param url 注册信息，不允许为空，如：dubbo://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     */
    void unregister(URL url);

    /**
     * 订阅符合条件的已注册数据，当有注册数据变更时自动推送.
     * <p>
     * 订阅需处理契约：<br>
     * 1. 当URL设置了check=false时，订阅失败后不报错，在后台定时重试。<br>
     * 2. 当URL设置了category=routers，只通知指定分类的数据，多个分类用逗号分隔，并允许星号通配，表示订阅所有分类数据。<br>
     * 3. 允许以interface,group,version,classifier作为条件查询，如：interface=com.alibaba.foo.BarService&version=1.0.0<br>
     * 4. 并且查询条件允许星号通配，订阅所有接口的所有分组的所有版本，或：interface=*&group=*&version=*&classifier=*<br>
     * 5. 当注册中心重启，网络抖动，需自动恢复订阅请求。<br>
     * 6. 允许URI相同但参数不同的URL并存，不能覆盖。<br>
     * 7. 必须阻塞订阅过程，等第一次通知完后再返回。<br>
     *
     * @param url      订阅条件，不允许为空，如：consumer://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @param listener 变更事件监听器，不允许为空
     */
    void subscribe(URL url, NotifyListener listener);

    /**
     * 取消订阅.
     * <p>
     * 取消订阅需处理契约：<br>
     * 1. 如果没有订阅，直接忽略。<br>
     * 2. 按全URL匹配取消订阅。<br>
     *
     * @param url      订阅条件，不允许为空，如：consumer://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @param listener 变更事件监听器，不允许为空
     */
    void unsubscribe(URL url, NotifyListener listener);

    /**
     * 查询符合条件的已注册数据，与订阅的推模式相对应，这里为拉模式，只返回一次结果。
     *
     * @param url 查询条件，不允许为空，如：consumer://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @return 已注册信息列表，可能为空，含义同{@link com.alibaba.dubbo.registry.NotifyListener#notify(List<URL>)}的参数。
     * @see com.alibaba.dubbo.registry.NotifyListener#notify(List)
     */
    List<URL> lookup(URL url);

}
```
该接口是注册中心服务，定义了注册中心的核心行为。
### RegistryFactory
```
@SPI("dubbo")
public interface RegistryFactory {

    /**
     * 连接注册中心.
     * <p>
     * 连接注册中心需处理契约：<br>
     * 1. 当设置check=false时表示不检查连接，否则在连接不上时抛出异常。<br>
     * 2. 支持URL上的username:password权限认证。<br>
     * 3. 支持backup=10.20.153.10备选注册中心集群地址。<br>
     * 4. 支持file=registry.cache本地磁盘文件缓存。<br>
     * 5. 支持timeout=1000请求超时设置。<br>
     * 6. 支持session=60000会话超时或过期设置。<br>
     *
     * @param url 注册中心地址，不允许为空
     * @return 注册中心引用，总不返回空
     */
    @Adaptive({"protocol"})
    Registry getRegistry(URL url);

}
```
注册中心工厂类，用于获得注册中心对象。该类是一个支持SPI扩展的类，默认的扩展实现是名称为dubbo的扩展实现类DubboRegistryFactory。
### AbstractRegistry
该类是Registry接口的抽象实现。 
[](/dubbo-registry/dubbo-registry-api/src/main/java/com/alibaba/dubbo/registry/support/AbstractRegistry.java)