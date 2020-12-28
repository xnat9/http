package cn.xnatural.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.*;
import java.nio.channels.*;
import java.security.AccessControlException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;


/**
 * http 服务
 */
public class HttpServer {
    protected static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    /**
     * jdk aio 基类 {@link AsynchronousServerSocketChannel}
     */
    protected       AsynchronousServerSocketChannel                          ssc;
    /**
     * {@link AsynchronousServerSocketChannel} aio 连接器
     */
    protected final CompletionHandler<AsynchronousSocketChannel, HttpServer> handler            = new AcceptHandler();
    /**
     * 配置: hp=[host]:port
     */
    protected final       LazySupplier<String>                               _hpCfg             = new LazySupplier<>(() -> getStr("hp", ":7070"));
    /**
     * 端口
     */
    protected final       LazySupplier<Integer>                              _port              = new LazySupplier<>(() -> Integer.valueOf(_hpCfg.get().split(":")[1]));
    /**
     * 请求/响应 io 字节编码
     */
    protected final LazySupplier<String>                                     _charset           = new LazySupplier<>(() -> getStr("charset", "utf-8"));
    /**
     * mvc: m层执行链
     */
    protected final Chain                                                    chain              = new Chain(this);
    /**
     * mvc: m层(控制器)
     */
    protected final List                      ctrls              = new LinkedList<>();
    /**
     * 是否可用
     */
    protected boolean                         enabled            = false;
    /**
     * 当前连接
     */
    protected  final Queue<HttpAioSession>    connections        = new ConcurrentLinkedQueue<>();
    /**
     * 请求计数器
     */
    protected final Counter                   counter            = new Counter();
    /**
     * 忽略打印的请求路径后缀
     */
    protected final LazySupplier<Set<String>> _ignoreLogSuffix   = new LazySupplier<>(() -> {
        final Set<String> set = new HashSet<>(Arrays.asList(".js", ".css", ".html", ".vue", ".png", ".ttf", ".woff", ".woff2", ".ico", ".map"));
        for (String suffix : getStr("ignoreLogUrlSuffix", "").split(",")) {
            if (suffix != null && !suffix.trim().isEmpty()) set.add(suffix.trim());
        }
        return set;
    });
    /**
     * 配置属性
     */
    protected final Map<String, Object> attrs;
    /**
     * 线程池执行器
     */
    protected final ExecutorService exec;


    /**
     * 创建
     * @param attrs 属性集
     *              maxMsgSize: socket 每次取数据的最大
     *              writeTimeout: 数据写入超时时间. 单位:毫秒
     *              backlog: 排队连接
     *              connection.maxIdle: 连接最大存活时间
     *              maxConnection: 最大连接数
     * @param exec 线程池
     */
    public HttpServer(Map<String, Object> attrs, ExecutorService exec) {
        this.attrs = attrs == null ? new ConcurrentHashMap<>() : attrs;
        this.exec = exec == null ? new ThreadPoolExecutor(
                4,8, 4, TimeUnit.HOURS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    AtomicInteger i = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "http-" + i.getAndIncrement());
                    }
                }
        ) : exec;
    }

    /**
     * {@link #HttpServer(Map, ExecutorService)}
     */
    public HttpServer() { this(null, null); }


    /**
     * 服务启动
     */
    public HttpServer start() {
        if (ssc != null) throw new RuntimeException("HttpServer is already running");
        try {
            AsynchronousChannelGroup cg = AsynchronousChannelGroup.withThreadPool(exec);
            ssc = AsynchronousServerSocketChannel.open(cg);
            ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ssc.setOption(StandardSocketOptions.SO_RCVBUF, getInteger("so_revbuf", 1024 * 1024 * 4));

            String host = _hpCfg.get().split(":")[0];
            InetSocketAddress addr = (host != null && !host.isEmpty()) ? new InetSocketAddress(host, getPort()) : new InetSocketAddress(getPort());

            ssc.bind(addr, getInteger("backlog", 128));
            initChain();
            enabled = true;
            accept();
            log.info("Start listen HTTP(AIO) {}", _hpCfg.get());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }


    /**
     * 服务停止
     */
    public void stop() {
        enabled = false;
        try { if (connections.size() > 5) {Thread.sleep(1000L);} ssc.close(); } catch (Exception e) {/** ignore **/}
        exec.shutdown();
    }


    /**
     * 接收新的 http 请求
     * @param request {@link HttpRequest}
     */
    protected void receive(HttpRequest request) {
        HttpContext hCtx = null;
        try {
            counter.increment();
            // 打印请求
            if (_ignoreLogSuffix.get().stream().noneMatch((suffix) -> request.getPath().endsWith(suffix))) {
                log.info("Start Request '{}': {}. from: " + request.session.getRemoteAddress(), request.getId(), request.rowUrl);
            }
            hCtx = new HttpContext(request, this, this::sessionDelegate);
            if (enabled) {
                if (connections.size() > getInteger("maxConnection", 128)) { // 限流
                    hCtx.response.status(503);
                    hCtx.render(ApiResp.fail("server busy, please wait..."));
                    hCtx.close();
                    return;
                }
                chain.handle(hCtx);
            } else {
                hCtx.response.status(503);
                hCtx.render(ApiResp.fail("server busy, please wait..."));
            }
        } catch (Exception ex) {
            log.error("Handle request error", ex);
            if (hCtx != null) hCtx.close();
        }
    }


    /**
     * session 数据 委托对象
     * @param hCtx {@link HttpContext}
     * @return session 数据操作 map
     */
    protected Map<String, Object> sessionDelegate(HttpContext hCtx) { return null; }


    /**
     * 添加
     * @param clzs 包含 {@link Ctrl} 的类
     * @return {@link HttpServer}
     */
    public HttpServer ctrls(Class...clzs) {
        if (clzs == null || clzs.length < 1) return this;
        try {
            for (Class clz : clzs) {
                ctrls.add(clz.newInstance());
            }
        } catch (Exception e) {
            throw new RuntimeException("Create object error.", e);
        }
        return this;
    }


    /**
     * 初始化Chain
     */
    protected void initChain() {
        for (Object ctrl : ctrls) {
            Ctrl aCtrl = ctrl.getClass().getAnnotation(Ctrl.class);
            if (aCtrl != null) {
                if (aCtrl.prefix() != null && !aCtrl.prefix().isEmpty()) {
                    chain.prefix(aCtrl.prefix(), (ch) -> parseCtrl(ctrl, ch));
                } else parseCtrl(ctrl, chain);
            } else {
                log.warn("@Ctrl Not Fund in: " + ctrl.getClass().getName());
            }
        }
    }


    /**
     * 解析 @Ctrl 类
     * @param ctrl 控制层类
     * @param chain 解析到哪个 {@link Chain}
     */
    protected void parseCtrl(Object ctrl, Chain chain) {
        Ctrl aCtrl = ctrl.getClass().getAnnotation(Ctrl.class);
        Consumer<Method> parser = method -> { // Handler 解析函数
            Path aPath = method.getAnnotation(Path.class);
            if (aPath != null) { // 路径映射
                if (aPath.path().length < 1) {
                    log.error("@Path path must not be empty. {}#{}", ctrl.getClass(), method.getName());
                    return;
                }
                Parameter[] ps = method.getParameters();
                method.setAccessible(true);
                for (String path : aPath.path()) {
                    if (path == null || path.isEmpty()) {
                        log.error("@Path path must not be empty. {}#{}", ctrl.getClass().getName(), method.getName());
                        return;
                    }
                    log.info("Request mapping: /" + (((aCtrl.prefix() != null && !aCtrl.prefix().isEmpty()) ? aCtrl.prefix() + "/" : "") + ("/".equals(path) ? "" : path)));
                    chain.method(aPath.method(), path, aPath.consumer(), aPath.produce(), hCtx -> { // 实际@Path 方法 调用
                        try {
                            Object result = method.invoke(ctrl, Arrays.stream(ps).map((p) -> hCtx.param(p.getName(), p.getType())).toArray());
                            if (!void.class.isAssignableFrom(method.getReturnType())) {
                                log.debug("Invoke Handler '{}#{}', result: {}, requestId: {}", ctrl.getClass().getName(), method.getName(), result, hCtx.request.getId());
                                hCtx.render(result);
                            }
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
                }
                return;
            }

            Filter aFilter = method.getAnnotation(Filter.class);
            if (aFilter != null) { // Filter处理
                if (!void.class.isAssignableFrom(method.getReturnType())) {
                    log.error("@Filter return type must be void. {}#{}", ctrl.getClass().getName(), method.getName());
                    return;
                }
                log.info("Request filter: /" + (aCtrl.prefix()) + ". {}#{}", ctrl.getClass().getName(), method.getName());
                Parameter[] ps = method.getParameters();
                method.setAccessible(true);
                chain.filter(hCtx -> { // 实际@Filter 方法 调用
                        method.invoke(ctrl, Arrays.stream(ps).map((p) -> hCtx.param(p.getName(), p.getType())).toArray());
                }, aFilter.order());
                return;
            }

            WS aWS = method.getAnnotation(WS.class);
            if (aWS != null) { // WS(websocket) 处理
                if (!void.class.isAssignableFrom(method.getReturnType())) {
                    log.error("@WS return type must be void. {}#{}", ctrl.getClass().getName(), method.getName());
                    return;
                }
                if (!(method.getParameterCount() == 1 && WebSocket.class.equals(method.getParameters()[0].getType()))) {
                    log.error("@WS parameter must be WebSocket. {}#{}", ctrl.getClass().getName(), method.getName());
                    return;
                }
                method.setAccessible(true);
                log.info("WebSocket: /" + (((aCtrl.prefix() != null && !aCtrl.prefix().isEmpty()) ? aCtrl.prefix() + "/" : "") + aWS.path()));
                chain.ws(aWS.path(), hCtx -> {
                    try {
                        // 响应握手
                        hCtx.response.status(101);
                        hCtx.response.header("Upgrade", "websocket");
                        hCtx.response.header("Connection", "Upgrade");

                        byte[] bs1 = hCtx.request.getHeader("Sec-WebSocket-Key").getBytes(_charset.get());
                        byte[] bs2 = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(_charset.get());
                        byte[] bs = new byte[bs1.length + bs2.length];
                        System.arraycopy(bs1, 0, bs, 0, bs1.length);
                        System.arraycopy(bs2, 0, bs, bs1.length, bs2.length);
                        hCtx.response.header("Sec-WebSocket-Accept", Base64.getEncoder().encodeToString(sha1(bs)));
                        hCtx.response.header("Sec-WebSocket-Location", "ws://" + getHp() + "/" + aCtrl.prefix() + "/" + aWS.path());
                        hCtx.render(null);

                        method.invoke(ctrl, hCtx.aioStream.ws);
                    } catch (InvocationTargetException ex) {
                        log.error("", ex.getCause());
                        hCtx.close();
                    }
                });
                return;
            }
        };
        Class c = ctrl.getClass();
        do {
            for (Method m : c.getDeclaredMethods()) { parser.accept(m); }
            c = c.getSuperclass();
        } while (c != null);
    }


    /**
     * 手动构建 执行链 Chain
     * @param buildFn {@link Consumer<Chain>}
     * @return {@link HttpServer}
     */
    public HttpServer buildChain(Consumer<Chain> buildFn) {
        buildFn.accept(chain);
        return this;
    }


    /**
     * 错误处理
     * @param ex 异常 {@link Throwable}
     * @param hCtx {@link HttpContext}
     */
    protected void errHandle(Throwable ex, HttpContext hCtx) {
        if (ex instanceof AccessControlException) {
            log.error("Request Error '" + hCtx.request.getId() + "', url: " + hCtx.request.getRowUrl() + ", " + ex.getMessage());
            hCtx.render(ApiResp.of("403", ex.getMessage()));
            return;
        }
        log.error("Request Error '" + hCtx.request.getId() + "', url: " + hCtx.request.getRowUrl(), ex);
        hCtx.render(ApiResp.fail((ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : ""))));
    }


    /**
     * 接收新连接
     */
    protected void accept() { ssc.accept(this, handler); }


    /**
     * 处理新连接
     * @param channel {@link AsynchronousSocketChannel}
     */
    protected void doAccept(final AsynchronousSocketChannel channel) {
        exec.execute(() -> {
            HttpAioSession se = null;
            try {
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                channel.setOption(StandardSocketOptions.SO_RCVBUF, getInteger("so_rcvbuf", 1024 * 1024 * 2));
                channel.setOption(StandardSocketOptions.SO_SNDBUF, getInteger("so_sndbuf", 1024 * 1024 * 4)); // 必须大于 chunk 最小值
                channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);

                se = new HttpAioSession(channel, this) {
                    @Override
                    protected void doClose(HttpAioSession session) { connections.remove(session); }
                };
                connections.offer(se);
                log.debug("New HTTP(AIO) Connection from: " + se.getRemoteAddress() + ", connected: " + connections.size());
                se.start();
                if (connections.size() > 10) clean();
            } catch (IOException e) {
                if (se != null) se.close();
                else {
                    try { channel.close(); } catch (IOException ex) {}
                }
                log.error("Create HttpAioSession error", e);
            }
        });
        // 继续接入
        accept();
    }


    /**
     * 清除已关闭或已过期的连接
     */
    public void clean() {
        if (connections.isEmpty()) return;
        int size = connections.size();
        long httpExpire = Duration.ofSeconds(getInteger("connection.maxIdle",
                ((Supplier<Integer>) () -> {
                    if (size > 80) return 60;
                    if (size > 50) return 120;
                    if (size > 30) return 180;
                    if (size > 20) return 300;
                    if (size > 10) return 400;
                    return 600;
                }).get()
        )).toMillis();
        long wsExpire = Duration.ofSeconds(getInteger("wsConnection.maxIdle",
                ((Supplier<Integer>) () -> {
                    if (size > 60) return 300;
                    if (size > 40) return 600;
                    if (size > 20) return 1200;
                    return 1800;
                }).get()
        )).toMillis();

        int limit = ((Supplier<Integer>) () -> {
            if (size > 80) return 8;
            if (size > 50) return 5;
            if (size > 30) return 3;
            return 2;
        }).get();
        for (Iterator<HttpAioSession> itt = connections.iterator(); itt.hasNext() && limit > 0; ) {
            HttpAioSession se = itt.next();
            if (se == null) {itt.remove(); break;}
            if (!se.channel.isOpen()) {
                itt.remove(); se.close();
                log.info("Cleaned unavailable {}: " + se + ", connected: " + connections.size(), se.ws != null ? "WsAioSession" : "HttpAioSession");
            } else if (se.ws != null && System.currentTimeMillis() - se.lastUsed > wsExpire) {
                limit--; itt.remove(); se.ws.close();
                log.debug("Closed expired WsAioSession: " + se + ", connected: " + connections.size());
            } else if (System.currentTimeMillis() - se.lastUsed > httpExpire) {
                limit--; itt.remove(); se.close();
                log.debug("Closed expired HttpAioSession: " + se + ", connected: " + connections.size());
            }
        }
    }


    /**
     * 分段传送, 每段大小
     * @param size 总字节大小
     * @param type 类型
     * @return 每段大小. <0: 不分段
     */
    protected int chunkedSize(int size, Class type) {
        int chunkedSize = -1;
        if (File.class.equals(type)) {
            // 下载限速
            if (size > 1024 * 1024 * 50) { // 大于50M
                chunkedSize = 1024 * 1024 * 4;
            } else if (size > 1024 * 1024) { // 大于1M
                chunkedSize = 1024 * 1024;
            } else if (size > 1024 * 500) { // 大于500K
                chunkedSize = 1024 * 200;
            }
            // 小文件不需要分段传送. 限于带宽(带宽必须大于分块的最小值, 否则会导致前端接收数据不全)
        } else {
            if (size > 1024 * 1024 * 4) throw new RuntimeException("body too large, > " + (1024 * 1024 * 4));
        }
        // TODO 其它类型暂时不分块
        return chunkedSize;
    }


    /**
     * 获取本机 ip 地址
     * @return ip
     */
    protected String ipv4() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface current = en.nextElement();
                if (!current.isUp() || current.isLoopback() || current.isVirtual()) continue;
                Enumeration<InetAddress> addresses = current.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.error("", e);
        }
        return null;
    }


    /**
     * https
     * @return
     */
//    protected Tuple2<PrivateKey, X509Certificate> security() {
//        SecureRandom random = new SecureRandom()
//        def gen = KeyPairGenerator.getInstance("RSA")
//        gen.initialize(2048, random)
//        def pair = gen.genKeyPair()
//
//        X509CertInfo info = new X509CertInfo()
//        X500Name owner = new X500Name("C=x,ST=x,L=x,O=x,OU=x,CN=x")
//        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))
//        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, random)))
//        try {
//            info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner))
//        } catch (CertificateException ignore) {
//            info.set(X509CertInfo.SUBJECT, owner)
//        }
//        try {
//            info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
//        } catch (CertificateException ignore) {
//            info.set(X509CertInfo.ISSUER, owner);
//        }
//        info.set(X509CertInfo.VALIDITY, new CertificateValidity(
//                new Date(System.currentTimeMillis() - 86400000L * 365),
//                new Date(253402300799000L))
//        )
//        info.set(X509CertInfo.KEY, new CertificateX509Key(pair.getPublic()));
//        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(new AlgorithmId(AlgorithmId.sha256WithRSAEncryption_oid)));
//
//        // Sign the cert to identify the algorithm that's used.
//        X509CertImpl cert = new X509CertImpl(info)
//        cert.sign(pair.private, "SHA256withRSA");
//
//        // Update the algorithm and sign again.
//        info.set(CertificateAlgorithmId.NAME + '.' + CertificateAlgorithmId.ALGORITHM, cert.get(X509CertImpl.SIG_ALG))
//        cert = new X509CertImpl(info);
//        cert.sign(pair.private, "SHA256withRSA")
//        cert.verify(pair.public)
//
//        return Tuple.tuple(pair.private, cert)
//    }



    /**
     * 返回 host:port
     * @return host:port
     */
    // @EL(name = {"http.hp", "web.hp"}, async = false)
    public String getHp() {
        String ip = _hpCfg.get().split(":")[0];
        if (ip == null || ip.isEmpty() || "localhost".equals(ip)) {ip = ipv4();}
        return ip + ":" + _port.get();
    }


    /**
     * 暴露的端口
     * @return 端口
     */
    public Integer getPort() { return _port.get(); }


    /**
     * 字符集
     * @return 字符集
     */
    public String getCharset() { return _charset.get(); }


    /**
     * 得到所有控制层对象
     * @return 所有 {@link Ctrl}
     */
    public List getCtrls() { return new LinkedList(ctrls); }


    /**
     * 获取属性
     * @param key 属性key
     * @return 属性值
     */
    public Object getAttr(String key) { return attrs.get(key); }


    public String getStr(String key, String defaultValue) {
        Object r = getAttr(key);
        if (r == null) return defaultValue;
        return r.toString();
    }


    public Integer getInteger(String key, Integer defaultValue) {
        Object r = getAttr(key);
        if (r == null) return defaultValue;
        else if (r instanceof Number) return ((Number) r).intValue();
        else return Integer.valueOf(r.toString());
    }


    public Long getLong(String key, Long defaultValue) {
        Object r = getAttr(key);
        if (r == null) return defaultValue;
        else if (r instanceof Number) return ((Number) r).longValue();
        else return Long.valueOf(r.toString());
    }


    public Boolean getBoolean(String key, Boolean defaultValue) {
        Object r = getAttr(key);
        if (r == null) return defaultValue;
        else if (r instanceof Boolean) return ((Boolean) r);
        else return Boolean.valueOf(r.toString());
    }


    /**
     * sha1 加密
     * @param bs 被加密byte[]
     * @return 加密后的byte[]
     */
    public static byte[] sha1(byte[] bs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(bs);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 新连接处理器
     */
    protected class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, HttpServer> {

        @Override
        public void completed(final AsynchronousSocketChannel channel, final HttpServer srv) {
            doAccept(channel);
        }

        @Override
        public void failed(Throwable ex, HttpServer srv) {
            if (!(ex instanceof ClosedChannelException)) {
                log.error(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), ex);
            }
        }
    }


    /**
     * 统计每小时的处理 请求 个数
     * MM-dd HH -> 个数
     */
    protected class Counter {
        protected final Map<String, LongAdder> hourCount = new ConcurrentHashMap<>(3);
        public void increment() {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH");
            boolean isNew = false;
            String hStr = sdf.format(new Date());
            LongAdder count = hourCount.get(hStr);
            if (count == null) {
                synchronized (hourCount) {
                    count = hourCount.get(hStr);
                    if (count == null) {
                        count = new LongAdder(); hourCount.put(hStr, count);
                        isNew = true;
                    }
                }
            }
            count.increment();
            if (isNew) {
                final Calendar cal = Calendar.getInstance();
                cal.setTime(new Date());
                cal.add(Calendar.HOUR_OF_DAY, -1);
                String lastHour = sdf.format(cal.getTime());
                LongAdder c = hourCount.remove(lastHour);
                if (c != null) log.info("{} total receive http request: {}", lastHour, c);
            }
        }
    }
}
