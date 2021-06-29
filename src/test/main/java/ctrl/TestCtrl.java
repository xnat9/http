package ctrl;

import cn.xnatural.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static cn.xnatural.http.ApiResp.ok;

/**
 * 常用接口例子
 */
@Ctrl(prefix = "test")
public class TestCtrl {
    protected static final Logger log = LoggerFactory.getLogger(TestCtrl.class);

    final Set<WebSocket> wss = ConcurrentHashMap.newKeySet();


    @Filter(order = 1)
    void filter1(HttpContext ctx) {
        log.info("filter1 ============");
    }

    @Filter(order = 2)
    void filter2(HttpContext ctx) {
        log.info("filter2 ============");
    }

    public void wsMsgBroadcast(String msg) {
        wss.forEach(ws -> ws.send(msg));
    }

    @WS(path = "msg")
    void wsMsg(WebSocket ws) {
        log.info("WS connect. {}", ws.getSession().getRemoteAddress());
        ws.listen(new WsListener() {
            @Override
            public void onClose(WebSocket wst) { wss.remove(wst); }

            @Override
            public void onText(String msg) {
                log.info("test ws receive client msg: {}", msg);
            }

            @Override
            public void onBinary(byte[] msg) {

            }
        });
        wsMsgBroadcast("上线: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        wss.add(ws);
    }
    

    @Path(path = "error")
    ApiResp error() throws Exception {
        throw new Exception("错误测试");
    }
    
    
    // get 请求
    @Path(path = "get")
    ApiResp get(Integer p1, String p2) {
        return ok().attr("p1", p1).attr("p2", p2);
    }
    

    // 接收form 表单提交
    @Path(path = "form", consumer = "application/x-www-form-urlencoded")
    ApiResp form(Integer p1, String p2, HttpContext ctx) {
        return ok(ctx.request.getFormParams());
    }
    

    // json 参数
    @Path(path = "json", consumer = "application/json")
    ApiResp json(HttpContext ctx) {
        return ok(ctx.request.getJsonParams());
    }
    

    // 接收post string
    @Path(path = "string")
    ApiResp string(HttpContext ctx) {
        return ok(ctx.request.getBodyStr());
    }


    // 异步响应(手动ctx.render)
    @Path(path = "async")
    void async(String p1, HttpContext ctx) {
        ctx.render(
                ok("p1: " + p1 + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
        );
    }


    // 测试登录
    @Path(path = "login")
    ApiResp login(String username, HttpContext ctx) {
        ctx.setSessionAttr("permissions", new HashSet<>(Arrays.asList("mnt-login")));
        ctx.setSessionAttr("uId", 1);
        ctx.setSessionAttr("username", username);
        return ok();
    }


    // 权限测试
    @Path(path = "auth")
    ApiResp auth(String auth, HttpContext ctx) {
        ctx.auth(auth == null ? "auth1" : auth);
        return ok();
    }


    //故意超时接口
    @Path(path = "timeout")
    ApiResp timeout(Integer timeout) throws Exception {
        int t = (timeout == null ? 10 : timeout);
        Thread.sleep(t * 1000L);
        return ok().desc("超时: " + t + "s" );
    }


    // 文件上传
    @Path(path = "upload")
    ApiResp upload(FileData file, String version) throws Exception {
        if (file == null) return ApiResp.fail("文件未上传");
        File uploadDir = new File("./upload");
        uploadDir.mkdirs();
        file.transferTo(uploadDir);
        log.info("upload file: " + file);
        return ok().attr("file", file.toString()).attr("version", version);
    }


    // 下载文件. 默认大于 500K 会自动分块传送
    @Path(path = "download/:fName")
    File download(String fName, HttpContext ctx) {
        File f = new File(System.getProperty("java.io.tmpdir"), (fName == null ? "tmp.xlsx" : fName));
        ctx.response.contentDisposition("attachment;filename=" + f.getName());
//        Object wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(true);
//        Object bos = new ByteArrayOutputStream(); wb.write(bos);
        return f;
    }


    // 自定义接口
    @Path(path = "cus")
    ApiResp cus(String p1) {
        log.info("here ================");
        return ok("p1: " + p1 + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
    }
}
