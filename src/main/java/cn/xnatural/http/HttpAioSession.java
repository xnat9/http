package cn.xnatural.http;

import cn.xnatural.http.common.LazySupplier;
import cn.xnatural.http.ws.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Http AIO 数据流
 */
public class HttpAioSession {
    protected static final Logger                    log         = LoggerFactory.getLogger(HttpAioSession.class);
    protected final        AsynchronousSocketChannel channel;
    protected final                ReadHandler                  readHandler = new ReadHandler(this);
    protected HttpServer                server;
    // 上次读写时间
    protected              Long                      lastUsed    = System.currentTimeMillis();
    protected final AtomicBoolean closed      = new AtomicBoolean(false);
    // 每次接收消息的内存空间(文件上传大小限制)
    protected final LazySupplier<ByteBuffer> _buf = new LazySupplier<>(() -> ByteBuffer.allocate(server.getInteger("maxMsgSize", 1024 * 1024 * 1)));
    // 不为空代表是WebSocket
    WebSocket ws;
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
    void close() {
        if (closed.compareAndSet(false, true)) {
            try { channel.shutdownOutput(); } catch(Exception ex) {}
            try { channel.shutdownInput(); } catch(Exception ex) {}
            try { channel.close(); } catch(Exception ex) {}
            tmpFiles.forEach((f) -> {try {f.delete(); } catch (Exception ex) {}});
            doClose(this);
        }
    }


    protected void doClose(HttpAioSession session) {}


    /**
     * 发送消息到客户端
     * @param buf
     */
    void send(ByteBuffer buf) {
        if (closed.get() || buf == null) return;
        lastUsed = System.currentTimeMillis();
        try {
            channel.write(buf).get();
        } catch (Exception ex) {
            if (!(ex instanceof ClosedChannelException)) {
                log.error(ex.getClass().getName() + " " + channel.getLocalAddress().toString() + " ->" + channel.getRemoteAddress().toString(), ex);
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


    @Override
    public String toString() {
        return HttpAioSession.class.getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[" + channel.toString() + "]";
    }


    protected class ReadHandler implements CompletionHandler<Integer, ByteBuffer> {
        // 当前解析的请求
        HttpRequest request;

        @Override
        public void completed(Integer count, ByteBuffer buf) {
            if (count > 0) {
                lastUsed = System.currentTimeMillis();
                buf.flip();

                if (ws != null) { // 是 WebSocket的情况
                    ws.decoder.decode(buf);
                } else { // 正常 http 请求
                    if (request == null) {request = new HttpRequest(HttpAioSession.this); }
                    try {
                        request.decoder.decode(buf);
                    } catch (Exception ex) {
                        log.error("HTTP 解析出错", ex);
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
                    throw new RuntimeException(e);
                }
            }
            close();
        }
    }
}
