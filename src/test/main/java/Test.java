import cn.xnatural.http.HttpServer;

public class Test {

    public static void main(String[] args) throws Exception {
        HttpServer server = new HttpServer().buildChain((chain -> {
            chain.get("test", hCtx -> {
                hCtx.render("test");
            }).post("testPost", hCtx -> {
                hCtx.render("testPost");
            });
        })).start();
        Thread.sleep(1000 * 60 * 100);
    }
}
