package cn.xnatural.http;


/**
 * web socket 监听器
 */
public interface WsListener {
    /**
     * {@link WebSocket} 关闭回调
     * @param ws
     */
    default void onClose(WebSocket ws) {}

    /**
     * 接收到文本消愁时回调
     * @param msg
     */
    default void onText(String msg) {}

    /**
     * 接收到字节码时回调
     * @param msg
     */
    default void onBinary(byte[] msg) {}
}
