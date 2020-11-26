package cn.xnatural.http.mvc;

import cn.xnatural.http.HttpContext;

/**
 * 对应一个 控制层 的一个处理器
 */
abstract class Handler {


    /**
     * 逻辑处理
     * @param ctx
     */
    abstract void handle(HttpContext ctx);


    /**
     * 匹配的顺序, 越大越先匹配
     * @return
     */
    double order() { return 0d; }


    /**
     * Handler 类型
     * @return
     */
    String type() { return Handler.class.getSimpleName(); }


    /**
     * 匹配
     * @param ctx
     * @return
     */
    boolean match(HttpContext ctx) { return false; }


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

