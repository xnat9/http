import cn.xnatural.http.HttpServer;
import ctrl.TestCtrl;

public class TestHttp {

    public static void main(String[] args) throws Exception {
        HttpServer server = new HttpServer().buildChain((chain -> {
            chain.get("test", hCtx -> {
                hCtx.render("test");
            }).post("testPost", hCtx -> {
                hCtx.render("testPost");
            });
        })).ctrls(TestCtrl.class).start();
        Thread.sleep(1000 * 60 * 100);
    }
}
