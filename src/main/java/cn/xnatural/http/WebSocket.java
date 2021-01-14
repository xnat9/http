package cn.xnatural.http;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

/**
 * web socket 连接实例
 */
public class WebSocket {
    // 关联的Http aio 会话
    protected    HttpAioSession session;
    // 消息监听
    protected    WsListener     listener;
    public final WsDecoder      decoder = new WsDecoder(this);


    public WebSocket(HttpAioSession session) { this.session = session; }


    /**
     * 发送消息
     * @param msg
     */
    public void send(String msg) { session.write(encode(msg)); }


    /**
     * 关闭 当前 websocket
     */
    public void close() {
        // session 为大内存对象, 主动回收
        HttpAioSession se = session; session = null; se.close();
        if (listener != null) listener.onClose(this);
    }


    /**
     * 设置消息监听
     * @param listener
     * @return
     */
    public WebSocket listen(WsListener listener) { this.listener = listener; return this; }


    /**
     * {@link #encode(byte[], byte)}
     * @param msg 消息
     * @return
     */
    protected ByteBuffer encode(String msg) {
        try {
            return encode(msg.getBytes(session.server.getCharset()), (byte) 1);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 编码 响应 文档消息
     * 编码参考: tio WsServerEncoder
     * @param body
     * @param opCode
     * @return
     */
    public static ByteBuffer encode(byte[] body, byte opCode) {
        ByteBuffer buf;
        byte header0 = (byte) (0x8f & (opCode | 0xf0));
        if (body.length < 126) {
            buf = ByteBuffer.allocate(2 + body.length);
            buf.put(header0);
            buf.put((byte) body.length);
        } else if (body.length < (1 << 16) - 1) {
            buf = ByteBuffer.allocate(4 + body.length);
            buf.put(header0);
            buf.put((byte) 126);
            buf.put((byte) (body.length >>> 8));
            buf.put((byte) (body.length & 0xff));
        } else {
            buf = ByteBuffer.allocate(10 + body.length);
            buf.put(header0);
            buf.put((byte) 127);
            // buf.put(new byte[] { 0, 0, 0, 0 });
            buf.position(buf.position() + 4);
            buf.put((byte) (body.length >>> 24));
            buf.put((byte) (body.length >>> 16));
            buf.put((byte) (body.length >>> 8));
            buf.put((byte) (body.length & 0xff));
        }
        buf.put(body);
        buf.flip();
        return buf;
    }


    public HttpAioSession getSession() {
        return session;
    }
}
