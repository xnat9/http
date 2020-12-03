package ctrl;

import cn.xnatural.enet.event.EL;
import cn.xnatural.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static cn.xnatural.http.ApiResp.ok;

@Ctrl
public class TestCtrl {
    protected static final Logger log = LoggerFactory.getLogger(TestCtrl.class);

    final Set<WebSocket> wss = ConcurrentHashMap.newKeySet();


    @Filter(order = 1)
    void filter2(HttpContext ctx) {
        log.info("test filter2 ============");
    }

    @Filter(order = 2)
    void filter1(HttpContext ctx) {
        log.info("filter1 ============");
    }

    @EL(name = "testWsMsg")
    void wsMsgBroadcast(String msg) {
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


    // 文件上传
    @Path(path = "upload")
    ApiResp upload(FileData file, String version) {
        if (file == null) return ApiResp.fail("文件未上传");
        return ok().attr("file", file.toString()).attr("version", version);
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


    // 下载文件
    @Path(path = "download")
    void download(HttpContext ctx) {
        File f = new File("d:/tmp/tmp.xlsx");
        ctx.response.contentType("application/vnd.ms-excel;charset=utf-8");
        ctx.response.header("Content-Disposition", "attachment;filename=" + f.getName());
//        ctx.response.header("Content-Disposition", "attachment;filename=" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".xlsx");
//        Object wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(true);
//        Object bos = new ByteArrayOutputStream(); wb.write(bos);
        ctx.render(f);
    }


    // 自定义接口
    @Path(path = "cus")
    ApiResp cus(String p1) {
        log.info("here ================");
        return ok("p1: " + p1 + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
    }
}
