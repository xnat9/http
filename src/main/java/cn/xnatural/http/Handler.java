package cn.xnatural.http;

/**
 * 对应一个 控制层 的一个处理器
 */
@FunctionalInterface
public interface Handler {


    /**
     * 逻辑处理
     * @param hCtx {@link HttpContext}
     */
    void handle(HttpContext hCtx) throws Throwable;


    /**
     * 匹配的顺序, 越大越先匹配
     * @return 优先级
     */
    default double getOrder() { return 0d; }


    /**
     * Handler 类型
     * @return 类型标识
     */
    default String getType() { return Handler.class.getSimpleName(); }


    /**
     * 匹配
     * @param hCtx {@link HttpContext}
     * @return 是否匹配当前请求
     */
    default boolean match(HttpContext hCtx) { return false; }


    /**
     * 去掉 路径 前后 的 /
     * @param path 路径
     * @return
     */
    static String extract(String path) {
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        if (path.startsWith("/")) path = path.substring(1);
        return path;
    }
}

