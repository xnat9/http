package cn.xnatural.http.mvc;

import cn.xnatural.http.HttpContext;
import cn.xnatural.http.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * mvc Handler 执行链路
 */
public class Chain {
    protected static final Logger              log       = LoggerFactory.getLogger(Chain.class);
    protected final        LinkedList<Handler> handlers  = new LinkedList<>();
    protected final        HttpServer          server;
    // 子Chain: prefix(前缀) -> Chain
    protected final        Map<String, Chain>  subChains = new ConcurrentHashMap<>();


    public Chain(HttpServer server) { this.server = server; }


    /**
     * 执行此Chain
     * @param ctx
     */
    protected void handle(HttpContext ctx) {
        boolean match = false;
        for (Handler h: handlers) { // 遍历查找匹配的Handler
            if (h instanceof FilterHandler) {
                h.handle(ctx);
            } else if (h instanceof PathHandler) {
                match = h.match(ctx);
                log.trace((match ? "Matched" : "Unmatch") + " {}, {}", ((PathHandler) h).path(), ctx.request.path);
                if (match) {
                    h.handle(ctx);
                    break;
                }
            } else new RuntimeException("未处理Handler类型: " + h.getClass().getSimpleName());
            // 退出条件
            if (ctx.response.commit.get()) break;
        }
        if (ctx.response.commit.get()) return;
        if (!match) { // 未找到匹配
            ctx.response.statusIfNotSet(404);
            ctx.render();
        } else if (ctx.response.status != null) { // 已经设置了status
            ctx.render();
        }
    }


    /**
     * 添加Handler
     * 按优先级添加, 相同类型比较, FilterHandler > PathHandler
     * [filter2, filter1, path3, path2, path1]
     * @param handler
     * @return
     */
    Chain add(Handler handler) {
        boolean added = false;
        int i = 0;
        for (ListIterator<Handler> it = handlers.listIterator(); it.hasNext(); ) {
            Handler h = it.next();
            if (h.type() == handler.type()) { // 添加类型的按优先级排在一起
                if (h.order() < handler.order()) { // order 值越大越排前面
                    it.previous(); // 相同类型 不是第一个 插入前面, 第一个插在第一个后面
                    it.add(handler); added = true; break;
                } else if (h.order() == handler.order()) {// 相同的order 按顺序排
                    it.add(handler); added = true; break;
                } else { // 小于
                    if (i == 0 && it.hasNext() && ((h = it.next()) != null)) { // 和handler同类型的只有一个时, 插在后边
                        if (h.type() != handler.type()) {
                            it.previous();
                            it.add(handler); added = true; break;
                        } else it.previous();
                    }
                }
                i++;
            }
        }
        if (!added) {
            if (handler instanceof FilterHandler) handlers.offerFirst(handler);
            else handlers.offerLast(handler);
        }
        return this;
    }


    /**
     * 指定方法,路径处理器
     * @param method get, post, delete ...
     * @param path 匹配路径
     * @param contentTypes application/json, multipart/form-data, application/x-www-form-urlencoded
     * @param handler 处理器
     * @return
     */
    public Chain method(String method, String path, String[] contentTypes, Handler handler) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("path mut not be empty");
        return add(new PathHandler() {
            @Override
            void handle(HttpContext ctx) {
                try {
                    handler.handle(ctx);
                } catch (Exception ex) {server.errHandle(ex, ctx);}
            }

            @Override
            String path() { return path; }

            @Override
            boolean match(HttpContext ctx) {
                boolean matched = super.match(ctx);
                if (!matched) return false;
                if (method && !method.equalsIgnoreCase(ctx.request.method)) {
                    ctx.response.status(405);
                    return false;
                }
                if (contentTypes == null || contentTypes.length < 1) {
                    boolean f = false;
                    for (String contentType: contentTypes) {
                        if (contentType.split(";")[0].equalsIgnoreCase(ctx.request.contentType.split(';')[0])) {
                            f = true; break;
                        }
                    }
                    if (!f) {
                        ctx.response.status(415);
                        return false;
                    }
                }
                if (415 == ctx.response.status || 405 == ctx.response.status) ctx.response.status(200);
                return true;
            }
        });
    }


    /**
     * 添加 websocket Handler
     * @return
     */
    public Chain ws(String path, Handler handler) {
        return add(new WSHandler() {
            @Override
            void handle(HttpContext ctx) {
                try {
                    handler.handle(ctx);
                } catch (Exception ex) {server.errHandle(ex, ctx);}
            }

            @Override
            String path() { return path; }
        });
    }


    /**
     * 添加Filter, 默认匹配
     * @param handler
     * @return
     */
    public Chain filter(Handler handler, int order) {
        return add(new FilterHandler() {
            @Override
            void handle(HttpContext ctx) {
                try {
                    handler.handle(ctx);
                } catch (Exception ex) {server.errHandle(ex, ctx);}
            }

            @Override
            double order() { return Double.valueOf(order); }
        });
    }


    Chain path(String path, Handler handler) {
        return method(null, path, null, handler);
    }


    Chain get(String path, Handler handler) {
        return method("get", path, null, handler);
    }


    Chain post(String path, Handler handler) {
        return method("post", path, null, handler);
    }


    Chain delete(String path, Handler handler) {
        return method("delete", path, null, handler);
    }


    /**
     * 前缀(一组Handler)
     * 相同的prefix用同一个Chain
     * 如果前缀包含多个路径, 则拆开每个路径都对应一个Chain
     * 例: 前缀: a/b, a/c a对应的Chain下边有两个子Chain b和c
     * @param prefix 路径前缀
     * @param handlerBuilder mvc执行链builder
     * @return Chain
     */
    public Chain prefix(final String prefix, final Consumer<Chain> handlerBuilder) {
        Chain subChain = this;
        for (String singlePrefix : Handler.extract(prefix).split("/")) { // 折成单路径(没有/分割的路径片)
            Chain parentChain = subChain;
            subChain = subChain.subChains.computeIfAbsent(singlePrefix, s -> new Chain(server));
            Chain finalSubChain = subChain;
            parentChain.add(new PathHandler() {
                final Chain chain = finalSubChain;
                @Override
                String path() { return singlePrefix; }

                @Override
                boolean match(HttpContext ctx) {
                    boolean f = false;
                    for (int i = 0; i < pieces().length; i++) {
                        f = (pieces()[i] == ctx.pieces[i]);
                        if (!f) break;
                    }
                    if (f) ctx.pieces = ctx.pieces.drop(pieces.length);
                    return f;
                }

                @Override
                void handle(HttpContext ctx) {
                    chain.handle(ctx);
                }
            });
        }
        handlerBuilder.accept(subChain);
        return this;
    }
}
