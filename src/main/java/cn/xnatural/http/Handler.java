package cn.xnatural.http;

/**
 * 对应一个 控制层 的一个处理器
 */
@FunctionalInterface
public interface Handler {


    /**
     * 逻辑处理
     * @param hCtx
     */
    void handle(HttpContext hCtx) throws Throwable;


    /**
     * 匹配的顺序, 越大越先匹配
     * @return
     */
    default double getOrder() { return 0d; }


    /**
     * Handler 类型
     * @return
     */
    default String getType() { return Handler.class.getSimpleName(); }


    /**
     * 匹配
     * @param hCtx
     * @return
     */
    default boolean match(HttpContext hCtx) { return false; }


    /**
     * 去掉 路径 前后 的 /
     * @param path
     * @return
     */
    static String extract(String path) {
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        if (path.startsWith("/")) path = path.substring(1);
        return path;
    }
}

