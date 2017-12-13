摘要: 
dubbo是一个具有非常强大扩展能力的分布式服务框架，而让它具备强大扩展能力的主要手段就是通过它的SPI机制的。因此掌握dubbo的SPI机制对于使用、扩展和阅读源码都大有帮助。本文将重点介绍dubbo的SPI机制的实现源码，看源码能够将很多文档中未说得非常详细的内容展现出来。

---
# dubbo的SPI机制
SPI机制请参阅 [dubbo开发者文档](http://dubbo.io/books/dubbo-dev-book/)
> SPI是面向dubbo开发者角色的扩展接口，开发者可以通过它来扩展dubbo的功能和技术实现。相对而言API是面向dubbo使用者角色编程接口。
> SPI解决的是扩展内容配置和动态加载的问题。在java中解决相同或者类似问题的技术有OSGI，JDK自带的SPI，以及IOC框架Spring也能够解决类似的问题，各种解决方案各有特点，我们不展开讲。而dubbo的SPI是从JDK标准的SPI(Service Provider Interface)扩展点发现机制加强而来，它做了如下改进：（这些内容引用自dubbo官方开发者手册）
> 
> 1. JDK标准的SPI会一次性实例化扩展点所有实现，如果有扩展实现初始化很耗时，但如果没用上也加载，会很浪费资源。
> 2. 如果扩展点加载失败，连扩展点的名称都拿不到了。比如：JDK标准的ScriptEngine，通过getName();获取脚本类型的名称，但如果RubyScriptEngine因为所依赖的jruby.jar不存在，导致RubyScriptEngine类加载失败，这个失败原因被吃掉了，和ruby对应不起来，当用户执行ruby脚本时，会报不支持ruby，而不是真正失败的原因。
> 3. 增加了对扩展点IoC和AOP的支持，一个扩展点可以直接setter注入其它扩展点。
SPI的源码位于工程dubbo-common的包com.alibaba.dubbo.common.extension下

---
# 源码实现
## ExtensionLoader [源码](/dubbo-common/src/main/java/com/alibaba/dubbo/common/extension/ExtensionLoader.java)
该类是dubbo的SPI机制实现的最为核心的一个类，绝大多数实现逻辑均位于该类中。为了简化，该类是一个将使用api及核心逻辑实现都封装同一个类中。通常获得一个扩展接口的实例使用如下接口方法获得:
```
ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
```
### 静态工厂方法 getExtensionLoader
该方法是一个静态工厂方法，是一个编程api，通过该方法获得一个某个参数制定的扩展类的ExtensionLoader对象。
```
private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }
```
1. dubbo的SPI扩展点必须是接口。
2. dubbo的SPI扩展点接口必须用注解SPI标注。
3. 某个扩展点的ExtensionLoader是获取的时候延迟生成，并且会进行缓存。
### 获得扩展实例方法 getExtension
```
    /**
         * 返回指定名字的扩展。如果指定名字的扩展不存在，则抛异常 {@link IllegalStateException}.
         *
         * @param name
         * @return
         */
        @SuppressWarnings("unchecked")
        public T getExtension(String name) {
            if (name == null || name.length() == 0)
                throw new IllegalArgumentException("Extension name == null");
            if ("true".equals(name)) {
                return getDefaultExtension();
            }
            Holder<Object> holder = cachedInstances.get(name);
            if (holder == null) {
                cachedInstances.putIfAbsent(name, new Holder<Object>());
                holder = cachedInstances.get(name);
            }
            Object instance = holder.get();
            if (instance == null) {
                synchronized (holder) {
                    instance = holder.get();
                    if (instance == null) {
                        instance = createExtension(name);
                        holder.set(instance);
                    }
                }
            }
            return (T) instance;
        }
```
1. 扩展点名称不能空，否则抛出异常。
2. 扩展点名称传入true则表示获取默认扩展点实例。
3. 扩展点支持缓存，说明扩展点对象在SPI容器中单例的，需要考虑线程安全。
4. 扩展点对象生成时延迟创建的，实现了对jdk的spi的改进。
### 获取默认扩展对象方法 getDefaultExtension
```
        /**
             * 返回缺省的扩展，如果没有设置则返回<code>null</code>。
             */
            public T getDefaultExtension() {
                getExtensionClasses();
                if (null == cachedDefaultName || cachedDefaultName.length() == 0
                        || "true".equals(cachedDefaultName)) {
                    return null;
                }
                return getExtension(cachedDefaultName);
            }
```
获得默认的扩展对象，属性cachedDefaultName的值并不是参数传递进来的，它是在方法获得扩展类getExtensionClasses()中赋值的。稍后我们一起看看该方法的实现。如果没有加载到默认的扩展点实现，则返回null。
### 创建某种扩展对象方法 createExtension
```
private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && wrapperClasses.size() > 0) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }
```
1. 根据扩展名称获得扩展实现类。先会调用方法getExtensionClasses加载扩展类列表，然后获取指定名称对应的扩展类。
2. 若找不到扩展类则抛出异常。
3. 从扩展实例缓存中找到该扩展类的实例。若找到则直接返回。
4. 若未找到缓存对象则产生新对象。调用的方法是clazz.newInstance())。
5. 给扩展实例执行依赖注入。调用了方法injectExtension，从而实现了对jdk的spi机制的ioc和aop功能扩展。
### 获取扩展实现类方法 getExtensionClasses()
该方法实现了获取某个名称扩展点的实现类，该方法是被几乎所有的方法调用之前先调用该方法，实现了扩展实现类的加载。
```
private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }
    // 此方法已经getExtensionClasses方法同步过。
    private Map<String, Class<?>> loadExtensionClasses() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if (value != null && (value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        loadFile(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadFile(extensionClasses, DUBBO_DIRECTORY);
        loadFile(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }
    private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL url = urls.nextElement();
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                        try {
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                final int ci = line.indexOf('#');
                                if (ci >= 0) line = line.substring(0, ci);
                                line = line.trim();
                                if (line.length() > 0) {
                                    try {
                                        String name = null;
                                        int i = line.indexOf('=');
                                        if (i > 0) {
                                            name = line.substring(0, i).trim();
                                            line = line.substring(i + 1).trim();
                                        }
                                        if (line.length() > 0) {
                                            Class<?> clazz = Class.forName(line, true, classLoader);
                                            if (!type.isAssignableFrom(clazz)) {
                                                throw new IllegalStateException("Error when load extension class(interface: " +
                                                        type + ", class line: " + clazz.getName() + "), class "
                                                        + clazz.getName() + "is not subtype of interface.");
                                            }
                                            if (clazz.isAnnotationPresent(Adaptive.class)) {
                                                if (cachedAdaptiveClass == null) {
                                                    cachedAdaptiveClass = clazz;
                                                } else if (!cachedAdaptiveClass.equals(clazz)) {
                                                    throw new IllegalStateException("More than 1 adaptive class found: "
                                                            + cachedAdaptiveClass.getClass().getName()
                                                            + ", " + clazz.getClass().getName());
                                                }
                                            } else {
                                                try {
                                                    clazz.getConstructor(type);
                                                    Set<Class<?>> wrappers = cachedWrapperClasses;
                                                    if (wrappers == null) {
                                                        cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                                                        wrappers = cachedWrapperClasses;
                                                    }
                                                    wrappers.add(clazz);
                                                } catch (NoSuchMethodException e) {
                                                    clazz.getConstructor();
                                                    if (name == null || name.length() == 0) {
                                                        name = findAnnotationName(clazz);
                                                        if (name == null || name.length() == 0) {
                                                            if (clazz.getSimpleName().length() > type.getSimpleName().length()
                                                                    && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                                name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                                            } else {
                                                                throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + url);
                                                            }
                                                        }
                                                    }
                                                    String[] names = NAME_SEPARATOR.split(name);
                                                    if (names != null && names.length > 0) {
                                                        Activate activate = clazz.getAnnotation(Activate.class);
                                                        if (activate != null) {
                                                            cachedActivates.put(names[0], activate);
                                                        }
                                                        for (String n : names) {
                                                            if (!cachedNames.containsKey(clazz)) {
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            if (c == null) {
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {
                                                                throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + url + ", cause: " + t.getMessage(), t);
                                        exceptions.put(line, e);
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            reader.close();
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " +
                                type + ", class file: " + url + ") in " + url, t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }
```
1. 先从加载的缓冲中获取扩展点实现类对象。若获取到则直接返回，否则需要加载。
2. 缓存有击穿的风险。即当扩展点无实现类的情况下会每次都进行扩展点记载，该操作是非常耗时的操作，因此要避免这种情况。
3. 加载扩展点实现类。通过调用方法loadExtensionClasses实现。
4. 扩展点接口上的SPI注解可以指定默认的扩展点名称。而且只能设置一个默认扩展点，不允许多个。这个很好理解。代码可以看出此时会将SPI注解的value值赋值给属性cachedDefaultName。
5. 扫描classpath下的三个目录加载SPI配置信息。分别类路径下的：META-INF/services/、META-INF/dubbo/ 和 META-INF/dubbo/internal/，加载顺序与该顺序正好相反，META-INF/dubbo/internal/ 最早开始加载。
6. 加载类路径下所有SPI配置文件。文件命名是：目录名+{扩展接口的类名}，比如扩展接口Protocol则文件名为："META-INF/dubbo/com.alibaba.dubbo.rpc.Protocol"。加载这些配置文件中的内容。
7. 逐行读取配置文件中的内容。
8. 配置文件支持'#'后面的均为注视，可以忽略。
9. 配置文件类似properties文件格式。内容格式是： {name}={className}。其中等号左边是扩展点实现名称，右边是扩展点实现的全局类名，需要包含在类路径中，否则类加载会抛出异常。可以写多行，每行表示一个实现类。
10. 找到类名后会使用当前的classloader加载该类。若加载失败则会记录异常。
11. 检查该扩展实现类的合法性。该类必须是扩展接口类的实现类。若不是则会抛出异常。
12. 检查扩展实现类是否为Adaptive标注类。如果是注解Adaptive标注的类（在类上注解，方法注解不算），则会将改类赋值给cachedAdaptiveClass类，表示该类为一个适配器类，然后退出。
13. 否则尝试获取包含参数为type的构造函数。如果有包含参数为type的构造函数，则说明该实现类是一个包装器类，会将cachedWrapperClasses的属性设置为该值，然后退出。
14. 否则继续获得无参构造函数。尝试获取无参数构造函数，若无则会抛出异常。
15. 检查扩展类名称。如果没有名称则会尝试自动生成，即若扩展点定义为com.alibaba.dubbo.rpc.Protocol，则扩展类名必须为com.alibaba.dubbo.rpc.XxxProtocol的才可以生成，自动生成的名称为xxx。即将前缀替换为小写。否则会抛出异常。
16. 扩展名称支持多个。名称如果是以','隔开，则表示名称是多个，多个名称都会被记录下来，引用相同的扩展实现类。
17. 如果扩展类有Activate注解，则会将注解实例activate放入集合属性cachedActivates中。
18. 将扩展实现的名称和类分别放入缓存中。以备后续使用。
### 获得自适应扩展 getAdaptiveExtension
ExtensionLoader注入的依赖扩展点是一个Adaptive实例，直到扩展点方法执行时才决定调用是一个扩展点实现。
Dubbo使用URL对象（包含了Key-Value）传递配置信息。扩展点方法调用会有URL参数（或是参数有URL成员），这样依赖的扩展点也可以从URL拿到配置信息，所有的扩展点自己定好配置的Key后，配置信息从URL上从最外层传入。URL在配置传递上即是一条总线。
```
private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extenstion " + type + ", cause: " + e.getMessage(), e);
        }
    }
    private Class<?> getAdaptiveExtensionClass() {
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }
    private Class<?> createAdaptiveExtensionClass() {
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuidler = new StringBuilder();
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // 完全没有Adaptive方法，则不需要生成Adaptive类
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        codeBuidler.append("package " + type.getPackage().getName() + ";");
        codeBuidler.append("\nimport " + ExtensionLoader.class.getName() + ";");
        codeBuidler.append("\npublic class " + type.getSimpleName() + "$Adpative" + " implements " + type.getCanonicalName() + " {");
        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();
            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // 有类型为URL的参数
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // 参数没有URL类型
                else {
                    String attribMethod = null;
                    // 找到参数的URL属性
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adative class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }
                    // Null point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }
                String[] value = adaptiveAnnotation.value();
                // 没有设置Key，则使用“扩展点接口名的点分隔 作为Key
                if (value.length == 0) {
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }
                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }
                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);
                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }
            codeBuidler.append("\npublic " + rt.getCanonicalName() + " " + method.getName() + "(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuidler.append(", ");
                }
                codeBuidler.append(pts[i].getCanonicalName());
                codeBuidler.append(" ");
                codeBuidler.append("arg" + i);
            }
            codeBuidler.append(")");
            if (ets.length > 0) {
                codeBuidler.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuidler.append(", ");
                    }
                    codeBuidler.append(ets[i].getCanonicalName());
                }
            }
            codeBuidler.append(" {");
            codeBuidler.append(code.toString());
            codeBuidler.append("\n}");
        }
        codeBuidler.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuidler.toString());
        }
        return codeBuidler.toString();
    }
```
1. 先从缓存中获得扩展点适配实例。如果有则直接获得，否则需要创建。
2. 若有类通过属性cachedAdaptiveClass的类创建。否则需要获取类。
3. 如果获取类失败，则需要调用方法createAdaptiveExtensionClass动态创建适配器类。
4. 如果扩展点类type没有方法被注解Adaptive注释过，则无需创建适配器类，则抛出异常。
5. 动态生成一个适配器实现类。生成的类名是： type.getSimpleName() + "$Adpative"。
6. 生成的类要实现每一个type中包含Adaptive注释过方法。若没有则该方法被抛出不支持的异常。
7. 实现包含Adaptive注释过方法。先通过type参数类型为URL或者getXXX返回值为URL的获得url对象值。并且会检查url值是否为空，如果为空则会抛出异常。
8. 若方法上的Adaptive注解的value是空，则使用“扩展点接口名的点分隔" 作为Key。
9. 以value作为key，从url对象中获得值，然后将获得的值调用方法getExtension获得扩展点实现对象。这样就实现了动态注入扩展实现。

**进一步分析内容 [参见](/5_SPI机制自适应Adaptive.md)**
### 获得自动激活扩展列表方法 getActivateExtension
对于集合类扩展点，比如：Filter, InvokerListener, ExportListener, TelnetHandler, StatusChecker等，
可以同时加载多个实现，此时，可以用自动激活来简化配置。本方法实现了这一特性。
```
/**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) {
                    T ext = getExtension(name);
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (usrs.size() > 0) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (usrs.size() > 0) {
            exts.addAll(usrs);
        }
        return exts;
    }
```
1. 有多个重载方法可以获得自动激活的扩展实例列表。
2. 如果values（即扩展点名称）列表中不包含默认值“-default”则开始遍历map对象cachedActivates中的所有activate。
3. 若扩展点被Activate注解标准，则检查activate中的group是否匹配