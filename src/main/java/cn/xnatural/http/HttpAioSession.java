package cn.xnatural.http;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.xnatural.http.HttpServer.log;

/**
 * Http AIO 数据流
 */
public class HttpAioSession {
    protected final        AsynchronousSocketChannel channel;
    protected final                ReadHandler                  readHandler = new ReadHandler();
    public HttpServer                server;
    // 上次读写时间
    protected              Long                      lastUsed    = System.currentTimeMillis();
    protected final AtomicBoolean closed      = new AtomicBoolean(false);
    // 每次接收消息的内存空间(文件上传大小限制)
    protected final LazySupplier<ByteBuffer> _buf = new LazySupplier<>(() -> ByteBuffer.allocate(server.getInteger("maxMsgSize", 1024 * 1024 * 1)));
    // 不为空代表是WebSocket
    protected WebSocket ws;
    // 当前解析的请求
    protected HttpRequest request;
    // 临时文件
    protected final List<File> tmpFiles = new LinkedList<>();


    HttpAioSession(AsynchronousSocketChannel channel, HttpServer server) {
        if (channel == null) throw new NullPointerException("channel must not be null");
        if (server == null) throw new NullPointerException("server must not be null");
        this.channel = channel;
        this.server = server;
    }


    /**
     * 开始数据接收处理
     */
    void start() { read(); }


    /**
     * 关闭
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try { channel.shutdownOutput(); } catch(Exception ex) {}
            try { channel.shutdownInput(); } catch(Exception ex) {}
            try { channel.close(); } catch(Exception ex) {}
            _buf.clear(); // 释放
            tmpFiles.forEach((f) -> {try { f.delete(); } catch (Exception ex) {}});
            doClose(this);
        }
    }


    /**
     * 子类重写, 清除对当前{@link HttpAioSession}的引用
     * @param session
     */
    protected void doClose(HttpAioSession session) {}


    /**
     * 发送消息到客户端
     * @param buf
     */
    public void write(ByteBuffer buf) {
        if (closed.get() || buf == null) return;
        lastUsed = System.currentTimeMillis();
        try {
            channel.write(buf).get();
        } catch (Exception ex) {
            if (!(ex instanceof ClosedChannelException)) {
                try {
                    log.error(ex.getClass().getName() + " " + channel.getRemoteAddress().toString() + " ->" + channel.getLocalAddress().toString(), ex);
                } catch (IOException e) {
                    log.error("", e);
                }
            }
            close();
        }
    }


    /**
     * 继续处理接收数据
     */
    protected void read() {
        if (closed.get()) return;
        channel.read(_buf.get(), _buf.get(), readHandler);
    }


    /**
     * 读数据, 解析数据
     * @param buf
     */
    protected void doRead(ByteBuffer buf) {
        if (ws != null) { // 是 WebSocket的情况
            try {
                ws.decoder.decode(buf);
            } catch (Exception ex) {
                log.error("Web socket decode error", ex);
                close();
            }
        } else { // 正常 http 请求
            if (request == null) {request = new HttpRequest(HttpAioSession.this); }
            try {
                request.decoder.decode(buf);
            } catch (Exception ex) {
                log.error("Http decode error", ex);
                close(); return;
            }
            if (request.decoder.complete) {
                if (request.decoder.websocket) { // 创建WebSocket 会话
                    ws = new WebSocket(HttpAioSession.this);
                    server.receive(request);
                } else {
                    HttpRequest req = request; request = null; // 接收下一个请求
                    server.receive(req);
                }
            }
        }
    }


    /**
     * 远程连接地址
     * @return
     */
    public String getRemoteAddress() {
        try {
            return channel.getRemoteAddress().toString();
        } catch (IOException e) {
            log.error("",e);
        }
        return null;
    }


    /**
     * 本地连接地址
     * @return
     */
    public String getLocalAddress() {
        try {
            return channel.getLocalAddress().toString();
        } catch (IOException e) {
            log.error("",e);
        }
        return null;
    }


    @Override
    public String toString() {
        return HttpAioSession.class.getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[" + channel.toString() + "]";
    }


    /**
     * aio 数据读数处理器
     */
    protected class ReadHandler implements CompletionHandler<Integer, ByteBuffer> {

        @Override
        public void completed(Integer count, ByteBuffer buf) {
            if (count > 0) {
                lastUsed = System.currentTimeMillis();
                buf.flip();
                doRead(buf);
                buf.compact();
                // 避免 ReadPendingException
                read();
            } else {
                //1. 有可能文件上传一次大于 buf 的容量
                //2. 浏览器老发送空的字节
                // TODO 待研究
                // log.warn("接收字节为空. 关闭 " + session.sc.toString())
                if (!channel.isOpen()) close();
            }
        }


        @Override
        public void failed(Throwable ex, ByteBuffer buf) {
            if (!(ex instanceof ClosedChannelException)) {
                try {
                    log.error(ex.getClass().getSimpleName() + " " + channel.getLocalAddress().toString() + " ->" + channel.getRemoteAddress().toString(), ex);
                } catch (IOException e) {
                    log.error("", e);
                }
            }
            close();
        }
    }
}
