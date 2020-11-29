package cn.xnatural.http;

import cn.xnatural.aio.AioServer;
import cn.xnatural.enet.event.EL;
import cn.xnatural.http.common.LazySupplier;
import cn.xnatural.http.mvc.*;
import cn.xnatural.http.ws.WS;
import cn.xnatural.http.ws.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.*;
import java.security.AccessControlException;
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
    static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    protected final CompletionHandler<AsynchronousSocketChannel, HttpServer> handler = new AcceptHandler();
    protected       AsynchronousServerSocketChannel                          ssc;
    protected final       LazySupplier<String>                _hpCfg = new LazySupplier<>(() -> getStr("hp", ":7070"));
    protected final       LazySupplier<Integer>                _port = new LazySupplier<>(() -> Integer.valueOf(_hpCfg.get().split(":")[1]));
    protected final Chain                  chain        = new Chain(this);
    protected final List                   ctrls        = new LinkedList<>();
    // 是否可用
    protected boolean                                enabled      = false;
    protected final Counter counter = new Counter();
    // 当前连接数
    protected  final Queue<HttpAioSession> connections  = new ConcurrentLinkedQueue<>();
    protected final LazySupplier<Set<String>>            _ignoreLogSuffix = new LazySupplier<>(() -> {
        final Set<String> set = new HashSet<>(Arrays.asList(".js", ".css", ".html", ".vue", ".png", ".ttf", ".woff", ".woff2", "favicon.ico", ".map"));
        for (String suffix : getStr("ignoreLogUrlSuffix", "").split(",")) {
            set.add(suffix);
        }
        return set;
    });
    protected final Map<String, Object> attrs;
    protected final ExecutorService exec;

    /**
     * 创建 {@link AioServer}
     *
     * @param attrs 属性集
     *              maxMsgSize: socket 每次取数据的最大
     *              writeTimeout: 数据写入超时时间. 单位:毫秒
     *              backlog: 排队连接
     *              connection.maxIdle: 连接最大存活时间
     * @param exec
     */
    public HttpServer(Map<String, Object> attrs, ExecutorService exec) {
        this.attrs = attrs == null ? new HashMap<>() : attrs;
        this.exec = exec == null ? new ThreadPoolExecutor(
                8,16, 1, TimeUnit.HOURS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    final AtomicInteger i = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "http-" + i.getAndIncrement());
                    }
                }
        ) : exec;
    }


    @EL(name = "sys.starting", async = true)
    public void start() {
        if (ssc != null) throw new RuntimeException("HttpServer is already running");
        try {
            AsynchronousChannelGroup cg = AsynchronousChannelGroup.withThreadPool(exec);
            ssc = AsynchronousServerSocketChannel.open(cg);
            ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ssc.setOption(StandardSocketOptions.SO_RCVBUF, getInteger("so_revbuf", 1024 * 1024 * 4));

            String host = _hpCfg.get().split(":")[0];
            InetSocketAddress addr = new InetSocketAddress(getPort());
            if (host != null && !host.isEmpty()) {addr = new InetSocketAddress(host, getPort());}

            ssc.bind(addr, getInteger("backlog", 128));
            initChain();
            accept();
            log.info("Start listen HTTP(AIO) {}", _hpCfg.get());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @EL(name = "sys.stopping", async = true)
    public void stop() {
        enabled = false;
        try { ssc.close(); } catch (IOException e) {/** ignore **/}
    }


    @EL(name = "sys.started", async = true)
    protected void started() { enabled = true; }


    /**
     * 接收新的 http 请求
     * @param request
     */
    protected void receive(HttpRequest request) {
        HttpContext hCtx = null;
        try {
            // 打印请求
            if (!_ignoreLogSuffix.get().stream().anyMatch((suffix) -> request.path().endsWith(suffix))) {
                log.info("Start Request '{}': {}. from: " + request.session.channel.getRemoteAddress().toString(), request.id(), request.rowUrl);
            }
            counter.increment();
            hCtx = new HttpContext(request, this);
            if (enabled) {
                if (app.sysLoad == 10 || connections.size() > getInteger("maxConnections", 128)) { // 限流
                    hCtx.response.status(503);
                    hCtx.render(ApiResp.fail("服务忙, 请稍后..."));
                    hCtx.close();
                    return;
                }
                chain.handle(hCtx);
            } else {
                hCtx.response.status(503);
                hCtx.render(ApiResp.fail("服务忙, 请稍后..."));
            }
        } catch (Exception ex) {
            log.error("请求处理错误", ex);
            if (hCtx != null) hCtx.close();
        }
    }



    /**
     * 添加
     * @param clzs
     * @return
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
            // exposeBean(ctrl);
            Ctrl anno = ctrl.getClass().getAnnotation(Ctrl.class);
            if (anno != null) {
                if (anno.prefix() != null && !anno.prefix().isEmpty()) {
                    chain.prefix(anno.prefix(), (ch) -> parseCtrl(ctrl, ch));
                } else parseCtrl(ctrl, chain);
            } else {
                log.warn("@Ctrl Not Fund in: " + ctrl.getClass().getName());
            }
        }
    }


    /**
     * 解析 @Ctrl 类
     * @param ctrl
     * @param chain
     */
    protected void parseCtrl(Object ctrl, Chain chain) {
        Ctrl aCtrl = ctrl.getClass().getAnnotation(Ctrl.class);
        Consumer<Method> fn = method -> {
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
                        log.error("@Path path must not be empty. {}#{}", ctrl.getClass(), method.getName());
                        return;
                    }
                    log.info("Request mapping: /" + (((aCtrl.prefix() != null && !aCtrl.prefix().isEmpty()) ? aCtrl.prefix() + "/" : "") + ("/".equals(path) ? "" : path)));
                    chain.method(aPath.method(), path, aPath.consumer(), (hCtx) -> { // 实际@Path 方法 调用
                        try {
                            Object result = method.invoke(ctrl, Arrays.stream(ps).map((p) -> hCtx.param(p.name, p.type)).toArray());
                            if (!void.class.isAssignableFrom(method.getReturnType())) {
                                log.debug("Invoke Handler '" + (ctrl.getClass().getName() + '#' + method.getName()) + "', result: " + result);
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
                        method.invoke(ctrl, Arrays.stream(ps).map((p) -> hCtx.param(p.name, p.type)).toArray());
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
                log.info("WebSocket: /" + (((aCtrl.prefix() != null && !aCtrl.prefix().isEmpty()) ? aCtrl.prefix() + "/" : "") + aWS.path()));
                chain.ws(aWS.path(), ctx -> {
                    try {
                        // 响应握手
                        ctx.response.status(101);
                        ctx.response.header("Upgrade", "websocket");
                        ctx.response.header("Connection", "Upgrade");

                        byte[] bs1 = ctx.request.getHeader("Sec-WebSocket-Key").getBytes("utf-8");
                        byte[] bs2 = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("utf-8");
                        byte[] bs = new byte[bs1.length + bs2.length];
                        System.arraycopy(bs1, 0, bs, 0, bs1.length);
                        System.arraycopy(bs2, 0, bs, bs1.length, bs2.length);
                        ctx.response.header("Sec-WebSocket-Accept", Base64.getEncoder().encodeToString(Utils.sha1(bs)));
                        ctx.response.header("Sec-WebSocket-Location", "ws://" + ep.fire("web.hp") + "/" + aCtrl.prefix() + "/" + aWS.path())
                        ctx.render(null);

                        method.invoke(ctrl, ctx.aioSession.ws);
                    } catch (InvocationTargetException ex) {
                        log.error("", ex.getCause());
                        ctx.close();
                    }
                });
                return;
            }
        };
        Class c = ctrl.getClass();
        do {
            for (Method m : c.getDeclaredMethods()) { fn.accept(m); }
            c = c.getSuperclass();
        } while (c != null);
    }


    /**
     * 手动构建 Chain
     * @param buildFn
     * @return
     */
    public HttpServer buildChain(Consumer<Chain> buildFn) {
        buildFn.accept(chain);
        return this;
    }


    /**
     * 错误处理
     * @param ex
     * @param ctx
     */
    public void errHandle(Exception ex, HttpContext ctx) {
        if (ex instanceof AccessControlException) {
            log.error("Request Error '" + ctx.request.id() + "', url: " + ctx.request.rowUrl() + ", " + ex.getMessage());
            ctx.render(ApiResp.of(ctx.respCode ? ctx.respCode : "403", (ex.message ? ": $ex.message" : "")));
            return;
        }
        log.error("Request Error '" + ctx.request.id + "', url: " + ctx.request.rowUrl, ex);
        ctx.render(ApiResp.of(ctx.respCode ? ctx.respCode : "01", ctx.respMsg?:(ex.class.simpleName + (ex.message ? ": $ex.message" : ""))))
    }


    /**
     * 接收新连接
     */
    protected void accept() { ssc.accept(this, handler); }


    /**
     * 处理新连接
     * @param channel
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

                se = new HttpAioSession(channel, this); connections.offer(se);
                InetSocketAddress rAddr = ((InetSocketAddress) channel.getLocalAddress());
                log.debug("New HTTP(AIO) Connection from: " + rAddr.getHostString() + ":" + rAddr.getPort() + ", connected: " + connections.size())
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


    @EL(name = {"http.hp", "web.hp"}, async = false)
    public String getHp() {
        String ip = _hpCfg.get().split(":")[0];
        if ("localhost".equals(ip)) {ip = ipv4();}
        return ip + ":" + _port.get();
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
     * 清除已关闭或已过期的连接
     */
    @EL(name = "sys.heartbeat", async = true)
    protected void clean() {
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
            if (!se.chennel.isOpen()) {
                itt.remove(); se.close();
                log.info("Cleaned unavailable {}: " + se + ", connected: " + connections.size(), se.ws != null ? "WsAioSession" : "HttpAioSession");
            } else if (se.ws && System.currentTimeMillis() - se.lastUsed > wsExpire) {
                limit--; itt.remove(); se.ws.close();
                log.debug("Closed expired WsAioSession: " + se + ", connected: " + connections.size());
            } else if (System.currentTimeMillis() - se.lastUsed > httpExpire) {
                limit--; itt.remove(); se.close();
                log.debug("Closed expired HttpAioSession: " + se + ", connected: " + connections.size());
            }
        }
    }


    protected Integer getPort() { return _port.get(); }


    public Object getAttr(String key) { return attrs.get(key); }


    public Object setAttr(String key, Object value) { return attrs.put(key, value); }


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
