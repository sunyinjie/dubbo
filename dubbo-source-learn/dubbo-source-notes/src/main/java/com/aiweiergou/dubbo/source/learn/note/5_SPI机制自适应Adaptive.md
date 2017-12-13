摘要: 
dubbo的SPI机制中为了可以根据URL中的参数灵活选择扩展实现类，设计了一种Adpative机制，通过类似于：ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension() 这种方法获得的是一个动态自适应的扩展对象，该对象不是真正的扩展实现，它只是代理了扩展点实现，将真正的接口调用还是转交给自适应的目标扩展点实现方法。

---
# 使用介绍
扩展点的Adpative类可以有两种方式实现，一种方式是人工实现Adpative类，然后配置成为该类型的自适应类；另外一种方法是如果没有人工指定的Adpative类，则dubbo的SPI机制会自动生成和编译一个动态的Adpative类。
## 人工设置
人工设置扩展点自适应实现类会非常灵活，可以由开发者灵活控制，但是缺点是如果有很多扩展点，自适应逻辑相同或者相似则会出现类爆炸的问题。
我们以编译器扩展点Compiler [源码](/dubbo-common/src/main/java/com/alibaba/dubbo/common/compiler/Compiler.java) 为例。
```
/*
 * Copyright 1999-2011 Alibaba Group.
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
package com.alibaba.dubbo.common.compiler;


import com.alibaba.dubbo.common.extension.SPI;

/**
 * Compiler. (SPI, Singleton, ThreadSafe)
 *
 * @author william.liangf
 */
@SPI("javassist")
public interface Compiler {

    /**
     * Compile java source code.
     *
     * @param code        Java source code
     * @param classLoader TODO
     * @return Compiled class
     */
    Class<?> compile(String code, ClassLoader classLoader);

}
```
它的自适应扩展点实现类**AdaptiveCompiler** [源码](/dubbo-common/src/main/java/com/alibaba/dubbo/common/compiler/support/AdaptiveCompiler.java)
```
/*
 * Copyright 1999-2011 Alibaba Group.
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
package com.alibaba.dubbo.common.compiler.support;


import com.alibaba.dubbo.common.compiler.Compiler;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

/**
 * AdaptiveCompiler. (SPI, Singleton, ThreadSafe)
 *
 * @author william.liangf
 */
@Adaptive
public class AdaptiveCompiler implements Compiler {

    private static volatile String DEFAULT_COMPILER;

    public static void setDefaultCompiler(String compiler) {
        DEFAULT_COMPILER = compiler;
    }

    public Class<?> compile(String code, ClassLoader classLoader) {
        Compiler compiler;
        ExtensionLoader<Compiler> loader = ExtensionLoader.getExtensionLoader(Compiler.class);
        String name = DEFAULT_COMPILER; // copy reference
        if (name != null && name.length() > 0) {
            compiler = loader.getExtension(name);
        } else {
            compiler = loader.getDefaultExtension();
        }
        return compiler.compile(code, classLoader);
    }

}
```
自适应实现类本身并没有实现compile方法，它是由参数DEFAULT_COMPILER指定一个默认的扩展点名称，因此是可以动态调整的。
## 自动生成
由于大部分的扩展点自适应实现类的代码逻辑都相似，因此自动生成动态的自适应扩展类则会给开发者带来很大的便利，省去了很多冗余代码。但是由于dubbo的实现方式是通过代码来自动生成自适应实现的代码，代码可读性非常差。这也是缺点。我们通过调试将其生成的代码打印出来将大大提高代码可读性。
### Protocol 扩展点
以扩展点Protocol为例来展示生成的代码 [源码](/dubbo-rpc/dubbo-rpc-api/src/main/java/com/alibaba/dubbo/rpc/Protocol.java)
```
/*
 * Copyright 1999-2011 Alibaba Group.
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
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 *
 * @author william.liangf
 */
@SPI("dubbo")
public interface Protocol {

    /**
     * 获取缺省端口，当用户没有配置端口时使用。
     *
     * @return 缺省端口
     */
    int getDefaultPort();

    /**
     * 暴露远程服务：<br>
     * 1. 协议在接收请求时，应记录请求来源方地址信息：RpcContext.getContext().setRemoteAddress();<br>
     * 2. export()必须是幂等的，也就是暴露同一个URL的Invoker两次，和暴露一次没有区别。<br>
     * 3. export()传入的Invoker由框架实现并传入，协议不需要关心。<br>
     *
     * @param <T>     服务的类型
     * @param invoker 服务的执行体
     * @return exporter 暴露服务的引用，用于取消暴露
     * @throws RpcException 当暴露服务出错时抛出，比如端口已占用
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * 引用远程服务：<br>
     * 1. 当用户调用refer()所返回的Invoker对象的invoke()方法时，协议需相应执行同URL远端export()传入的Invoker对象的invoke()方法。<br>
     * 2. refer()返回的Invoker由协议实现，协议通常需要在此Invoker中发送远程请求。<br>
     * 3. 当url中有设置check=false时，连接失败不能抛出异常，并内部自动恢复。<br>
     *
     * @param <T>  服务的类型
     * @param type 服务的类型
     * @param url  远程服务的URL地址
     * @return invoker 服务的本地代理
     * @throws RpcException 当连接服务提供方失败时抛出
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * 释放协议：<br>
     * 1. 取消该协议所有已经暴露和引用的服务。<br>
     * 2. 释放协议所占用的所有资源，比如连接和端口。<br>
     * 3. 协议在释放后，依然能暴露和引用新的服务。<br>
     */
    void destroy();

}
```
它生成的扩展自适应实现类源码如下
```
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.extension.ExtensionLoader;

public class Protocol$Adpative implements com.alibaba.dubbo.rpc.Protocol {
	public void destroy() {
		throw new UnsupportedOperationException(
				"method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
	}

	public int getDefaultPort() {
		throw new UnsupportedOperationException(
				"method public abstract int com.alibaba.dubbo.rpc.Protocol.getDefaultPort() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
	}

	public com.alibaba.dubbo.rpc.Exporter export(
			com.alibaba.dubbo.rpc.Invoker arg0)
			throws com.alibaba.dubbo.rpc.RpcException {
		if (arg0 == null)
			throw new IllegalArgumentException(
					"com.alibaba.dubbo.rpc.Invoker argument == null");
		if (arg0.getUrl() == null)
			throw new IllegalArgumentException(
					"com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");
		com.alibaba.dubbo.common.URL url = arg0.getUrl();
		String extName = (url.getProtocol() == null ? "dubbo" : url
				.getProtocol());
		if (extName == null)
			throw new IllegalStateException(
					"Fail to get extension(com.alibaba.dubbo.rpc.Protocol) name from url("
							+ url.toString() + ") use keys([protocol])");
		com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader
				.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class)
				.getExtension(extName);
		return extension.export(arg0);
	}

	public com.alibaba.dubbo.rpc.Invoker refer(java.lang.Class arg0,
			com.alibaba.dubbo.common.URL arg1)
			throws com.alibaba.dubbo.rpc.RpcException {
		if (arg1 == null)
			throw new IllegalArgumentException("url == null");
		com.alibaba.dubbo.common.URL url = arg1;
		String extName = (url.getProtocol() == null ? "dubbo" : url
				.getProtocol());
		if (extName == null)
			throw new IllegalStateException(
					"Fail to get extension(com.alibaba.dubbo.rpc.Protocol) name from url("
							+ url.toString() + ") use keys([protocol])");
		com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader
				.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class)
				.getExtension(extName);
		return extension.refer(arg0, arg1);
	}
}
```
从生成的源码可以看出来这些特点：
1. 只会代理扩展点接口上有@Adaptive标注的方法。没有标注的方法调用会抛出不支持该方法的异常。
2. 从类型为url的参数或者参数的url属性中获得url对象，从url中获得value作为扩展点的名称。
3. 从扩展点加载器中获得对应名称的扩展点。
4. 再调用扩展点的方法。
### Transporter 扩展点
[源码](/dubbo-remoting/dubbo-remoting-api/src/main/java/com/alibaba/dubbo/remoting/Transporter.java)
```
/*
 * Copyright 1999-2011 Alibaba Group.
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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

import javax.sound.midi.Receiver;

/**
 * Transporter. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Transport_Layer">Transport Layer</a>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @author ding.lid
 * @author william.liangf
 * @see com.alibaba.dubbo.remoting.Transporters
 */
@SPI("netty")
public interface Transporter {

    /**
     * Bind a server.
     *
     * @param url     server url
     * @param handler
     * @return server
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#bind(URL, Receiver, ChannelHandler)
     */
    @Adaptive({Constants.SERVER_KEY, Constants.TRANSPORTER_KEY})
    Server bind(URL url, ChannelHandler handler) throws RemotingException;

    /**
     * Connect to a server.
     *
     * @param url     server url
     * @param handler
     * @return client
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#connect(URL, Receiver, ChannelListener)
     */
    @Adaptive({Constants.CLIENT_KEY, Constants.TRANSPORTER_KEY})
    Client connect(URL url, ChannelHandler handler) throws RemotingException;

}
```
生成的自适应扩展实现源码如下
```
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.extension.ExtensionLoader;

public class Transporter$Adpative implements
		com.alibaba.dubbo.remoting.Transporter {
	public com.alibaba.dubbo.remoting.Client connect(
			com.alibaba.dubbo.common.URL arg0,
			com.alibaba.dubbo.remoting.ChannelHandler arg1)
			throws com.alibaba.dubbo.remoting.RemotingException {
		if (arg0 == null)
			throw new IllegalArgumentException("url == null");
		com.alibaba.dubbo.common.URL url = arg0;
		String extName = url.getParameter("client", url.getParameter(
				"transporter", "netty"));
		if (extName == null)
			throw new IllegalStateException(
					"Fail to get extension(com.alibaba.dubbo.remoting.Transporter) name from url("
							+ url.toString()
							+ ") use keys([client, transporter])");
		com.alibaba.dubbo.remoting.Transporter extension = (com.alibaba.dubbo.remoting.Transporter) ExtensionLoader
				.getExtensionLoader(
						com.alibaba.dubbo.remoting.Transporter.class)
				.getExtension(extName);
		return extension.connect(arg0, arg1);
	}

	public com.alibaba.dubbo.remoting.Server bind(
			com.alibaba.dubbo.common.URL arg0,
			com.alibaba.dubbo.remoting.ChannelHandler arg1)
			throws com.alibaba.dubbo.remoting.RemotingException {
		if (arg0 == null)
			throw new IllegalArgumentException("url == null");
		com.alibaba.dubbo.common.URL url = arg0;
		String extName = url.getParameter("server", url.getParameter(
				"transporter", "netty"));
		if (extName == null)
			throw new IllegalStateException(
					"Fail to get extension(com.alibaba.dubbo.remoting.Transporter) name from url("
							+ url.toString()
							+ ") use keys([server, transporter])");
		com.alibaba.dubbo.remoting.Transporter extension = (com.alibaba.dubbo.remoting.Transporter) ExtensionLoader
				.getExtensionLoader(
						com.alibaba.dubbo.remoting.Transporter.class)
				.getExtension(extName);
		return extension.bind(arg0, arg1);
	}
}
```
区别点是调用了String extName = url.getParameter("server", url.getParameter( "transporter", "netty"));来获得扩展名称。