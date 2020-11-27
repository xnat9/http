package cn.xnatural.http;

import cn.xnatural.http.common.LazySupplier;
import cn.xnatural.http.mvc.FileData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;

/**
 * HTTP 解析器
 */
public class HttpDecoder {
    static final  Logger      log      = LoggerFactory.getLogger(HttpDecoder.class);
    static final byte[] lineDelimiter = ((Supplier<byte[]>) () -> {
        try {
            return "\n".getBytes("utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }).get();


    protected     HttpRequest request;
    // 请求收到的字节长度
    protected long        size;
    // 解析是否完成
    protected boolean     complete;
    protected boolean     startLineComplete;
    protected boolean     headerComplete;
    protected boolean     bodyComplete;
    protected LazySupplier<String>      charset = new LazySupplier<>(() -> request.session.delegate.getStr("charset", "utf-8"));
    // 当前读到哪个Part
    protected Part        curPart;
    protected LazySupplier<String> boundary = new LazySupplier<>(() -> {
        if (request == null) return null;
        String ct = request.contentType();
        if (ct == null) return null;
        if (ct.toUpperCase().contains("multipart/form-data")) {
            return ct.split(";")[1].split("=")[1];
        }
        return null;
    });
    // 解析几次
    protected int decodeCount;
    // 是否升级到websocket
    protected boolean websocket;
    protected Map<String, Object> multiForm;


    HttpDecoder(HttpRequest request) {
        this.request = request;
    }


    /**
     * 解析http请求
     *
     * @param buf
     * @return
     */
    void decode(ByteBuffer buf) throws Exception {
        decodeCount++;
        if (!startLineComplete) {
            startLineComplete = startLine(buf);
        }
        if (startLineComplete && !headerComplete) {
            headerComplete = header(buf);
            if (headerComplete) {// 判断是否升级为 websocket
                if ("Upgrade".equalsIgnoreCase(request.getHeader("Connection")) && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                    websocket = true;
                    bodyComplete = true;
                }
            }
        }
        if (headerComplete && !bodyComplete) {
            bodyComplete = body(buf);
        }
        complete = bodyComplete && headerComplete && startLineComplete;
    }


    /**
     * 解析: 请求起始行
     *
     * @param buf
     */
    protected boolean startLine(ByteBuffer buf) {
        String firstLine = readLine(buf);
        if (firstLine == null) { // 没有一行数据
            if (decodeCount > 1) {
                throw new RuntimeException("HTTP start line too manny");
            }
            return false;
        }
        try {
            String[] arr = firstLine.split(" ");
            request.method = arr[0];
            request.rowUrl = arr[1];
            request.protocol = arr[2].split("/")[0];
            request.version = arr[2].split("/")[1];
        } catch (Exception ex) {
            log.error("非Http数据. " + firstLine);
            throw ex;
        }
        return true;
    }


    /**
     * 解析: 请求头
     * @param buf
     */
    protected boolean header(ByteBuffer buf) {
        do {
            String headerLine = readLine(buf);
            if (headerLine == null) break;
            if ("\r" == headerLine) return true; // 请求头结束
            int index = headerLine.indexOf(":");
            request.headers.put(headerLine.substring(0, index).toLowerCase(), headerLine.substring(index + 1).trim());
        } while (true);
        // [Cookie:USER_ID_ANONYMOUS=28f34d1a1137476994fda617d2777b7c; DETECTED_VERSION=1.8.1; MAIN_NAV_ACTIVE_TAB_INDEX=1; PAGINATION_PAGE_SIZE=10; mntLogin=true; name=admin; roles=admin; Cache-Control=max-age=120; uId=4028b88173142d470173142d52700000; JSESSIONID=xPzSn1KxdBnxGU2fp_jYI4vWtdSduKAB2A64Q3R4, Accept:*/*, Connection:keep-alive, User-Agent:Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.25 Safari/537.36 Core/1.70.3766.400 QQBrowser/10.6.4163.400, Host:localhost:9090, Accept-Encoding:gzip, deflate, br, Accept-Language:zh-CN,zh;q=0.9]
        // log.info("headers: " + req.headers)
        return false;
    }


    /**
     * 解析: 请求体
     * @param buf
     */
    protected boolean body(ByteBuffer buf) throws Exception {
        String ct = request.contentType();

        if (ct == null || ct.isEmpty()) return true; // get 请求 有可能没得 body
        if (ct.contains("application/json") || ct.contains("application/x-www-form-urlencoded") || ct.contains("text/plain")) {
            String lengthStr = request.getHeader("content-length");
            if (lengthStr != null) {
                int length = Integer.valueOf(lengthStr);
                if (buf.remaining() < length) return false; // 数据没接收完
                byte[] bs = new byte[length];
                buf.get(bs);
                request.bodyStr = new String(bs, charset.get());
            }
            return true;
        } else if (ct.contains("multipart/form-data")) {
            if (multiForm == null) multiForm = new HashMap<>();
            return readMultipart(buf);
        }
        return false;
    }


    /**
     * 遍历读一个part
     * @param buf
     * @return true: 读完, false 未读完(数据不够)
     */
    protected boolean readMultipart(ByteBuffer buf) throws Exception {
        // HttpMultiBodyDecoder, HttpPostMultipartRequestDecoder
        String boundary = "--" + this.boundary.get();
        String endLine = boundary + "--";
        if (curPart == null) {
            do { // 遍历读每个part
                String line = readLine(buf);
                if (line == null) return false;
                if (line == "\r") continue;
                if (line == endLine || line == (endLine + "\r")) return true; // 结束行
                curPart = new Part(); curPart.boundary = line;

                // 读part的header
                boolean f = readMultipartHeader(buf);
                if (!f) return false;
                // 读part的值
                f = readMultipartValue(buf);
                if (f) {
                    size += curPart.fd == null ? 0 : curPart.fd.getSize();
                    curPart = null; // 下一个 Part
                } else return false;
            } while (true);
        } else if (!curPart.headerComplete) {
            boolean f = readMultipartHeader(buf);
            if (f) readMultipart(buf);
        } else if (!curPart.valueComplete) {
            boolean f = readMultipartValue(buf);
            if (f) {
                size += curPart.fd == null ? 0 : curPart.fd.getSize();
                curPart = null; // 下一个 Part
                f = readMultipart(buf);
                if (f) return true;
            }
        }
        return false;
    }


    /**
     * 读 Multipart 中的 Header部分
     * @param buf
     * @return true: 读完, false 未读完(数据不够)
     */
    protected boolean readMultipartHeader(ByteBuffer buf) {
        // 读参数名: 从header Content-Disposition 中读取参数名 和文件名
        do { // 每个part的header
            String line = readLine(buf);
            if (null == line) return false;
            else if ("\r" == line) {
                curPart.headerComplete = true;
                return true;
            } else if (line.toUpperCase().contains("content-disposition")) {
                for (String entry : line.split(":")[1].split(";")) {
                    String[] arr = entry.split("=");
                    if (arr.length > 1) {
                        if (arr[0].trim() == "name") curPart.name = arr[1].replace("\"", "").replace("\r", "");
                        else if (arr[0].trim() == "filename") {
                            curPart.filename = arr[1].replace("\"", "").replace("\r", "");
                        }
                    }
                }
            }
        } while (true);
    }


    /**
     * 读 Multipart 中的 Value 部分
     * @param buf
     * @return true: 读完, false 未读完(数据不够)
     */
    protected boolean readMultipartValue(ByteBuffer buf) throws Exception {
        if (curPart.filename != null) { // 当前part 是个文件, filename  可能是个空字符串
            int index = indexOf(buf, ("\r\n--" + boundary).getBytes("utf-8"));

            if (curPart.tmpFile == null) { // 临时存放文件
                curPart.tmpFile = File.createTempFile(request.id(), FileData.extractFileExtension(curPart.filename));
                request.session.tmpFiles.add(curPart.tmpFile);
                if (multiForm.containsKey(curPart.name)) { // 有多个值
                    Object v = multiForm.get(curPart.name);
                    if (v instanceof List) ((List) v).add(curPart.fd);
                    else {
                        multiForm.put(curPart.name, new LinkedList<>(Arrays.asList(v, curPart.fd)));
                    }
                } else multiForm.put(curPart.name, curPart.fd);
            }
            if (index == -1) {
                int length = buf.remaining();
                try (OutputStream os = new FileOutputStream(curPart.tmpFile, true)) {
                    byte[] bs = new byte[length];
                    buf.get(bs); //先读到内存 减少io
                    os.write(bs);
                }
                curPart.fd.setSize(curPart.fd.getSize() + length);
                return false;
            } else {
                int length = index - buf.position();
                if (length == 0 && curPart.filename == "") {// 未上传的情况
                    request.session.tmpFiles.remove(curPart.tmpFile);
                    curPart.tmpFile.delete();
                    Object v = multiForm.remove(curPart.name);
                    if (v instanceof List) ((List) v).remove(curPart.fd);
                    else {
                        multiForm.put(curPart.name, null);
                    }
                } else {
                    try (OutputStream os = new FileOutputStream(curPart.tmpFile, true)) {
                        byte[] bs = new byte[length];
                        buf.get(bs); //先读到内存 减少io
                        os.write(bs);
                    }
                    curPart.fd.setSize(curPart.fd.getSize() + length);
                }
                curPart.valueComplete = true;
                return true;
            }
        } else { // 字符串 Part
            curPart.value = readLine(buf).replace("\r", "");
            if (curPart.value != null) {
                curPart.valueComplete = true;
                if (multiForm.containsKey(curPart.name)) { // 有多个值
                    Object v = multiForm.get(curPart.name);
                    if (v instanceof List) ((List) v).add(curPart.value);
                    else {
                        multiForm.put(curPart.name, new LinkedList<>(Arrays.asList(v, curPart.fd)));
                    }
                } else multiForm.put(curPart.name, curPart.value);
                return true;
            }
            return false;
        }
    }


    /**
     * 读行
     *
     * @param buf
     * @return
     */
    protected String readLine(ByteBuffer buf) {
        int index = indexOf(buf, lineDelimiter);
        if (index == -1) return null;
        int readableLength = index - buf.position();
        byte[] bs = new byte[readableLength];
        buf.get(bs);
        size += readableLength;
        // 跳过 分割符的长度
        for (int i = 0; i < lineDelimiter.length; i++) {
            buf.get();
        }
        size += lineDelimiter.length;
        try {
            return new String(bs, charset.get());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 查找分割符所匹配下标
     * @param buf
     * @param delim 分隔符
     * @return 下标位置
     */
    protected int indexOf(ByteBuffer buf, byte[] delim) {
        byte[] hb = buf.array();
        int delimIndex = -1; // 分割符所在的下标
        for (int i = buf.position(), size = buf.limit(); i < size; i++) {
            boolean match = true; // 是否找到和 delim 相同的字节串
            for (int j = 0; j < delim.length; j++) {
                match = match && (i + j < size) && delim[j] == hb[i + j];
            }
            if (match) {
                delimIndex = i;
                break;
            }
        }
        return delimIndex;
    }


    protected class Part {
        String boundary;
        String name;
        String filename;
        File   tmpFile;
        FileData fd;
        boolean headerComplete;
        String value;
        boolean valueComplete;

        public FileData getFd() {
            if (fd != null) return fd;
            if (tmpFile != null && tmpFile.exists()) {
                // originName: curPart.filename, inputStream: , size: 0
                try {
                    fd = new FileData().setOriginName(filename).setInputStream(new FileInputStream(tmpFile)).setSize(tmpFile.length());
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
            return fd;
        }
    }
}
