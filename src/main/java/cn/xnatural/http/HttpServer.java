package cn.xnatural.http;

import cn.xnatural.aio.AioServer;

import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

public class HttpServer extends AioServer {
    protected final CompletionHandler<AsynchronousSocketChannel, HttpServer> handler = new AcceptHandler()
    protected       AsynchronousServerSocketChannel                          ssc
    @Lazy protected String                                                   hpCfg   = getStr('hp', ":8080")
    @Lazy           Integer                                                  port    = hpCfg.split(":")[1] as Integer
    protected final Chain chain = new Chain(this)
    protected final List  ctrls = new LinkedList<>()
    // 是否可用
    boolean                                enabled      = false
    // 当前连接数
    protected  final Queue<HttpAioSession> connections  = new ConcurrentLinkedQueue<>()
    @Lazy protected  Set<String>           ignoreSuffix = new HashSet(['.js', '.css', '.html', '.vue', '.png', '.ttf', '.woff', '.woff2', 'favicon.ico', '.map', *attrs()['ignorePrintUrlSuffix']?:[]])

    HttpServer(String name) { super(name) }
    HttpServer() { super('web') }
    /**
     * 创建 {@link AioServer}
     *
     * @param attrs 属性集
     *              maxMsgSize: socket 每次取数据的最大
     *              writeTimeout: 数据写入超时时间. 单位:毫秒
     *              backlog: 排队连接
     *              aioSession.maxIdle: 连接最大存活时间
     * @param exec
     */
    public HttpServer(Map<String, Object> attrs, ExecutorService exec) {
        super(attrs, exec);
    }

}
