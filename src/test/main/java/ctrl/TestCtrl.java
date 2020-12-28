package ctrl;

import cn.xnatural.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.*;
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
        file.transferTo(new File(System.getProperty("java.io.tmpdir")));
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

    protected final Map<String, File> tmpFiles = new ConcurrentHashMap<>();
    /**
     * 文件上传: 断点续传/持续上传/分片上传
     * 原理: 把文件分成多块 依次上传, 用同一个标识多次上传为同一个文件
     * 多线程上传不安全
     * @param server {@link HttpServer}
     * @param filePiece 文件片
     * @param uploadId 上传id, 用于集合所有分片
     * @param originName 文件原名
     * @param totalPiece 总片数
     * @param currentPiece 当前第几片
     * @return
     */
    @Path(path = "pieceUpload")
    ApiResp pieceUpload(HttpServer server, FileData filePiece, String uploadId, String originName, Integer totalPiece, Integer currentPiece) throws Exception {
        if (filePiece == null) {return ApiResp.fail("文件片未上传");}
        if (uploadId == null || uploadId.isEmpty()) {return ApiResp.fail("Param: uploadId 不能为空");}
        if (totalPiece == null) {return ApiResp.fail("Param: totalPiece 不能为空");}
        if (totalPiece < 2) {return ApiResp.fail("Param: totalPiece >= 2");}
        if (currentPiece == null) {return ApiResp.fail("Param: currentPiece 不能为空");}
        if ((originName == null || originName.isEmpty()) && currentPiece == 1) {return ApiResp.fail("参数错误: originName 不能为空");}
        if (currentPiece < 1) {return ApiResp.fail("Param: currentPiece >= 1");}
        if (totalPiece < currentPiece) {return ApiResp.fail("Param: totalPiece >= currentPiece");}

        ApiResp<Map<String, Object>> resp = ok().attr("uploadId", uploadId).attr("currentPiece", currentPiece);
        if (currentPiece == 1) { // 第一个分片: 保存文件
            tmpFiles.put(uploadId, filePiece.getFile());
            // TODO 过一段时间还没上传完 则主动删除 server.getInteger("pieceUpload.maxKeep", 120)
        } else if (totalPiece > currentPiece) { // 后面的分片: 追加到第一个分片的文件里面去
            File file = tmpFiles.get(uploadId);
            if (file == null) return ApiResp.of("404", "文件未找到: " + uploadId).attr("originName", originName);
            filePiece.appendTo(file); filePiece.delete();

            long maxSize = server.getLong("pieceUpload.maxFileSize", 1024 * 1024 * 900L); // 最大上传200M
            if (file.length() > maxSize) { // 文件大小验证
                file = tmpFiles.remove(uploadId);
                file.delete();
                return ApiResp.fail("上传文件太大, <=" + maxSize);
            }
        } else { // 最后一个分片
            File file = tmpFiles.remove(uploadId);
            filePiece.appendTo(file); filePiece.delete();

            FileData fd = new FileData().setOriginName(originName).setFile(file).setInputStream(new FileInputStream(file));
            // TODO 另存
            fd.transferTo(new File(System.getProperty("java.io.tmpdir")));
            return resp.attr("finalName", fd.getFinalName());
        }
        return resp;
    }


    // 自定义接口
    @Path(path = "cus")
    ApiResp cus(String p1) {
        log.info("here ================");
        return ok("p1: " + p1 + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
    }
}
