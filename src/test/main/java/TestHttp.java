import cn.xnatural.http.HttpServer;
import ctrl.MainCtrl;
import ctrl.TestCtrl;

public class TestHttp {

    public static void main(String[] args) throws Exception {
        HttpServer server = new HttpServer().buildChain((chain -> {
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
        Thread.sleep(1000 * 60 * 100);
    }
}
