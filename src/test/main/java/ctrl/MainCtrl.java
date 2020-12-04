package ctrl;

import cn.xnatural.http.Ctrl;
import cn.xnatural.http.HttpContext;
import cn.xnatural.http.HttpServer;
import cn.xnatural.http.Path;

import java.io.File;

@Ctrl
public class MainCtrl {

    static File baseDir(String child) {
        File p = new File(System.getProperty("user.dir"));
        if (child != null) {return new File(p, child);}
        return p;
    }


    @Path(path = {"index.html", "/"})
    File index(HttpContext ctx) {
        ctx.response.cacheControl(10);
        return baseDir("src/test/main/resources/static/index.html");
    }

    @Path(path = "test.html")
    File testHtml(HttpContext ctx) {
        ctx.response.cacheControl(3);
        return baseDir("src/test/main/resources/static/test.html");
    }

    // 文件
    @Path(path = "file/:fName")
    File file(String fName) {
        return new File(new File(System.getProperty("java.io.tmpdir")), (fName == null ? "tmp.xlsx" : fName));
    }


    // ====================== api-doc =========================

    @Path(path = "api-doc/:fName.json")
    String swagger_data(String fName, HttpContext ctx) {
//        def f = return baseDir("conf/${fName}.json")
//        if (f.exists()) {
//            ctx.response.contentType("application/json")
//            return f.getText("utf-8")
//        }
//        null
        return null;
    }
    @Path(path = "api-doc/:fName")
    File swagger_ui(String fName, HttpContext ctx) {
        ctx.response.cacheControl(1800);
        return baseDir("src/test/main/resources/static/swagger-ui/" + fName);
    }


    // ==========================js =====================

    @Path(path = "js/:fName")
    File js(String fName, HttpContext ctx, HttpServer server) {
        if ("pro".equals(server.getStr("profile", null))) {
            ctx.response.cacheControl(1800);
        }
        return baseDir("src/test/main/resources/static/js/" + fName);
    }
    @Path(path = "js/lib/:fName")
    File js_lib(String fName, HttpContext ctx) {
        ctx.response.cacheControl(1800);
        return baseDir("src/test/main/resources/static/js/lib/" + fName);
    }

    @Path(path = "components/:fName")
    File components(String fName, HttpContext ctx, HttpServer server) {
        if ("pro".equals(server.getStr("profile", null))) {
            ctx.response.cacheControl(1800);
        }
        return baseDir("src/test/main/resources/static/components/" + fName);
    }


    // =======================css ========================

    @Path(path = "css/:fName")
    File css(String fName, HttpContext ctx, HttpServer server) {
        if ("pro".equals(server.getStr("profile", null))) {
            ctx.response.cacheControl(1800);
        }
        return baseDir("src/test/main/resources/static/css/" + fName);
    }
    @Path(path = "css/fonts/:fName")
    File css_fonts(String fName, HttpContext ctx) {
        ctx.response.cacheControl(1800);
        return baseDir("src/test/main/resources/static/css/fonts/" + fName);
    }
    @Path(path = "css/lib/:fName")
    File css_lib(String fName, HttpContext ctx) {
        ctx.response.cacheControl(1800);
        return baseDir("src/test/main/resources/static/css/lib/" + fName);
    }
}
