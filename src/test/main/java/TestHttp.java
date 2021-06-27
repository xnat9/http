import cn.xnatural.http.HttpServer;
import ctrl.MainCtrl;
import ctrl.TestCtrl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestHttp {

    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                4, 8, 2, TimeUnit.HOURS,
                new LinkedBlockingQueue<>(100000),
                new ThreadFactory() {
                    final AtomicInteger i = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "http-" + i.getAndIncrement());
                    }
                }
        );
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("hp", ":7070");

        HttpServer server = new HttpServer(attrs, exec).buildChain((chain -> {
            // 手动自定义添加接口
            chain.get("test1111", hCtx -> {
                hCtx.render("test");
            }).post("testPost11111", hCtx -> {
                hCtx.render("testPost");
            }).prefix("testPrefix", ch -> { // 多层路径. /testPrefix/test2222
                ch.get("test2222", hCtx -> {
                    hCtx.render("test2222");
                });
            });
        })).ctrls( // 添加Controller层类
                MainCtrl.class, TestCtrl.class
        ).start();
        Thread.sleep(1000 * 60 * 30);
        //server.stop();
    }
}
