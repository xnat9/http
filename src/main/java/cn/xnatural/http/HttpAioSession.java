package cn.xnatural.http;

import cn.xnatural.aio.AioServer;
import cn.xnatural.aio.AioStream;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.LinkedList;
import java.util.List;

/**
 * Http AIO 数据流
 */
public class HttpAioSession extends AioStream {
    // 不为空代表是WebSocket
    protected WebSocket ws;
    // 当前解析的请求
    protected HttpRequest request;
    // 临时文件
    protected final List<File> tmpFiles = new LinkedList<>();


    HttpAioSession(AsynchronousSocketChannel sc, HttpServer server) {
        super(sc, server);
    }


    @Override
    protected void doClose(AioStream stream) {
        for (File f : tmpFiles) {
            try {f.delete(); } catch (Exception ex) {}
        }
    }



    @Override
    protected void doRead(ByteBuffer buf) {
        if (ws != null) { // 是 WebSocket的情况
            ws.decoder.decode(buf);
        } else { // 正常 http 请求
            if (request == null) {request = new HttpRequest(this);}
            try {
                request.decoder.decode(buf);
            } catch (Exception ex) {
                log.error("HTTP 解析出错", ex);
                close(); return;
            }
            if (request.decoder.complete) {
                if (request.decoder.websocket) { // 创建WebSocket 会话
                    ws = new WebSocket(session);
                    ((HttpServer) delegate).receive(request);
                } else {
                    HttpRequest req = request; request = null; // 接收下一个请求
                    ((HttpServer) delegate).receive(req);
                }
            }
        }
    }
}
