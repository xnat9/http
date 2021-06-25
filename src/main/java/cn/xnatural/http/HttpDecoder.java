package cn.xnatural.http;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HTTP 解析器
 */
public class HttpDecoder {
    /**
     * 当前请求
     */
    protected  HttpRequest request;
    /**
     * 请求收到的请求体字节长度
     */
    protected long         bodySize;
    /**
     * 请求收到的请求头字节长度
     */
    protected long         headerSize;
    /**
     * 解析是否完成
     */
    protected boolean      complete;
    /**
     * 起始行是否解析完成
     */
    protected boolean      startLineComplete;
    /**
     * 请求头是否解析完成
     */
    protected boolean      headerComplete;
    /**
     * 请求体是否解析完成
     */
    protected boolean      bodyComplete;
    /**
     * 请求体包含多个 Part时: 当前读到哪个Part
     */
    protected Part         curPart;
    /**
     * 解析几次
     */
    protected int          decodeCount;
    /**
     * 是否升级到websocket
     */
    protected boolean      websocket;
    /**
     * multipart/form-data 表单数据预存.包含文件
     */
    protected Map<String, Object>   multiForm;
    /**
     * 请求体包含多个 Part时: part之间的分割符
     */
    protected Lazies<String> boundary             = new Lazies<>(() -> {
        if (request == null) return null;
        String ct = request.getContentType();
        if (ct == null) return null;
        if (ct.toLowerCase().contains("multipart/form-data")) {
            return ct.split(";")[1].split("=")[1];
        }
        return null;
    });
    /**
     * 文本body长度限制
     */
    protected Lazies<Integer> _textBodyLengthLimit = new Lazies<>(() -> request.session.server.getInteger("textBodyLengthLimit", 1024 * 1024));
    /**
     * multipart/form-data, 单个值的大小限制, 单位: bit
     */
    protected Lazies<Integer> _multiValueMaxSize   = new Lazies<>(() -> request.session.server.getInteger("multiValueMaxSize", 1024 * 1024));


    HttpDecoder(HttpRequest request) { this.request = request; }


    /**
     * 开始解析http请求
     * @param buf 字节流
     */
    protected void decode(ByteBuffer buf) throws Exception {
        decodeCount++;
        // 1. 解析请求起始行
        if (!startLineComplete) {
            startLineComplete = startLine(buf);
        }
        // 2. 解析请求公用头(header)
        if (startLineComplete && !headerComplete) {
            headerComplete = header(buf);
            if (headerComplete) {// 判断是否升级为 websocket
                if ("Upgrade".equalsIgnoreCase(request.getConnection()) && "websocket".equalsIgnoreCase(request.getUpgrade())) {
                    websocket = true;
                    bodyComplete = true;
                }
            }
        }
        // 3. 解析请求体
        if (headerComplete && !bodyComplete) {
            bodyComplete = body(buf);
        }
        complete = bodyComplete && headerComplete && startLineComplete;
    }


    /**
     * 解析: 请求起始行
     * @param buf 字节流
     */
    protected boolean startLine(ByteBuffer buf) throws Exception {
        String firstLine = readLine(buf);
        if (firstLine == null) { // 没有一行数据
            if (decodeCount > 1) {
                throw new Exception("HTTP start line too large");
            }
            return false;
        }
        try {
            String[] arr = firstLine.split(" ");
            request.method = arr[0];
            request.rowUrl = arr[1];
            request.protocol = arr[2].split("/")[0];
            request.version = arr[2].split("/")[1].replace("\r", "");
        } catch (Exception ex) {
            throw new Exception("Error http data: " + firstLine, ex);
        }
        return true;
    }


    /**
     * 解析: 请求头
     * @param buf 字节流
     */
    protected boolean header(ByteBuffer buf) throws Exception {
        Long headerSizeLimit = request.session.server.getLong("headerSizeLimit", 1024 * 1024 * 2L);
        do {
            if (headerSize > headerSizeLimit) {
                throw new Exception("HTTP header too large");
            }
            int p = buf.position();
            String headerLine = readLine(buf);
            if (headerLine == null) {
                if (buf.remaining() == buf.limit()) { // buf数据是满的,但没读出来
                    throw new Exception("HTTP header item too large");
                }
                break;
            }
            headerSize += buf.position() - p;
            if ("\r".equals(headerLine)) return true; // 请求头结束
            int index = headerLine.indexOf(":");
            request.headers.put(headerLine.substring(0, index).toLowerCase(), headerLine.substring(index + 1).trim());
        } while (true);
        return false;
    }


    /**
     * 解析: 请求体
     * @param buf 字节流
     */
    protected boolean body(ByteBuffer buf) throws Exception {
        String ct = request.getContentType();

        if (ct == null || ct.isEmpty()) return true; // get 请求 有可能没得 body
        ct = ct.toLowerCase();
        int position = buf.position();
        if (ct.contains("application/json") || ct.contains("application/x-www-form-urlencoded") || ct.contains("text/plain")) {
            String lengthStr = request.getHeader("Content-Length");
            if (lengthStr != null) {
                int length = Integer.valueOf(lengthStr);
                if (length > _textBodyLengthLimit.get()) {
                    throw new Exception("text body length limit to: " + _textBodyLengthLimit.get());
                }
                if (buf.remaining() < length) return false; // 数据没接收完
                byte[] bs = new byte[length];
                buf.get(bs);
                request.bodyStr = new String(bs, request.session.server.getCharset());
            }
            bodySize += buf.position() - position;
            return true;
        } else if (ct.contains("multipart/form-data")) {
            if (multiForm == null) multiForm = new HashMap<>();
            boolean f = readMultipart(buf);
            bodySize += buf.position() - position;
            return f;
        }
        return false;
    }


    /**
     * 遍历读一个part
     * @param buf 字节流
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
                if ("\r".equals(line)) continue;
                if (line.equals(endLine) || line.equals(endLine + "\r")) return true; // 结束行
                curPart = new Part(); curPart.boundary = line;

                //1. 读Part的header
                boolean f = readMultipartHeader(buf);
                if (!f) return false; //数据不够
                //2. 读part的值
                f = readMultipartValue(buf);
                if (f) curPart = null; // 继续读下一个 Part
                else return false;
            } while (true);
        } else if (!curPart.headerComplete) {
            boolean f = readMultipartHeader(buf);
            if (f) return readMultipart(buf);
        } else if (!curPart.valueComplete) {
            boolean f = readMultipartValue(buf);
            if (f) {
                curPart = null; // 继续读下一个 Part
                f = readMultipart(buf);
                if (f) return true;
            }
        }
        return false;
    }


    /**
     * 读 Multipart 中的 Header部分
     * @param buf 字节流
     * @return true: 读完, false 未读完(数据不够)
     */
    protected boolean readMultipartHeader(ByteBuffer buf) throws Exception {
        // 读参数名: 从header Content-Disposition 中读取参数名 和文件名
        do { // 每个part的header
            String line = readLine(buf);
            if (null == line) {
                if (buf.remaining() == buf.limit()) { // buf数据是满的,但没读出来
                    throw new Exception("HTTP multipart header item too large");
                }
                return false;
            }
            else if ("\r".equals(line)) { curPart.headerComplete = true; return true; }
            else if (line.toLowerCase().contains("content-disposition")) { // part为文件
                for (String entry : line.split(":")[1].split(";")) {
                    String[] arr = entry.split("=");
                    if (arr.length > 1) {
                        if ("name".equals(arr[0].trim())) curPart.name = arr[1].replace("\"", "").replace("\r", "");
                        else if ("filename".equals(arr[0].trim())) {
                            curPart.filename = arr[1].replace("\"", "").replace("\r", "");
                        }
                    }
                }
            }
            // else throw new Exception("Unknown part: " + line);
        } while (true);
    }


    /**
     * 读 Multipart 中的 Value 部分
     * @param buf 字节流
     * @return true: 读完, false 未读完(数据不够)
     */
    protected boolean readMultipartValue(ByteBuffer buf) throws Exception {
        if (curPart.filename != null) { // 文件 Part, filename  可能是个空字符串
            int index = indexOf(buf, ("\r\n--" + boundary.get()).getBytes(request.session.server.getCharset()));
            if (curPart.fd == null) {
                curPart.fd = new FileData().setOriginName(curPart.filename).setInputStream(curPart.createInputStream());
                if (multiForm.containsKey(curPart.name)) { // 有多个值
                    Object v = multiForm.get(curPart.name);
                    if (v instanceof List) ((List) v).add(curPart.fd);
                    else {
                        multiForm.put(curPart.name, new LinkedList<>(Arrays.asList(v, curPart.fd)));
                    }
                } else multiForm.put(curPart.name, curPart.fd);
            }
            if (index == -1) { // 没找到结束符. 证明buf 里面全是文件的内容
                int length = buf.remaining();
                byte[] bs = new byte[length];
                buf.get(bs);
                curPart.addFileContent(bs);
                // 单文件上传大小限制
                if (curPart.fd.getSize() > request.session.server.getInteger("maxFileSize", 1024 * 1024 * 20)) {
                    throw new RuntimeException("file too large");
                }
                return false;
            } else { //文件最后的内容
                int length = index - buf.position();
                if (length == 0 && curPart.filename.isEmpty()) {// 未上传的情况
                    Object v = multiForm.remove(curPart.name);
                    if (v instanceof List) ((List) v).remove(curPart.fd);
                    else {
                        multiForm.put(curPart.name, null);
                    }
                } else { // 文件写入完成
                    byte[] bs = new byte[length];
                    buf.get(bs);
                    curPart.addFileContent(bs);
                }
                curPart.valueComplete = true;
                return true;
            }
        } else { // 文本 Part
            String line = readLine(buf);
            if (line == null) {
                if (buf.remaining() == buf.limit()) { // buf数据是满的,但没读出来
                    throw new Exception("HTTP multipart value item too large");
                }
                return false;
            }
            curPart.value = line.replace("\r", "");
            curPart.valueComplete = true;
            if (multiForm.containsKey(curPart.name)) {
                Object v = multiForm.get(curPart.name);
                if (v instanceof List) ((List) v).add(curPart.value);  // 有多个值
                else {
                    multiForm.put(curPart.name, new LinkedList<>(Arrays.asList(v, curPart.fd)));
                }
            } else multiForm.put(curPart.name, curPart.value);
            return true;
        }
    }


    /**
     * 读一行文本
     * @param buf 字节流
     * @return 一行字符串
     */
    protected String readLine(ByteBuffer buf) throws Exception {
        byte[] lineDelimiter = "\n".getBytes(request.session.server.getCharset());
        int index = indexOf(buf, lineDelimiter);
        if (index == -1) return null;
        int readableLength = index - buf.position();
        byte[] bs = new byte[readableLength];
        buf.get(bs);
        bodySize += readableLength;
        for (int i = 0; i < lineDelimiter.length; i++) { // 跳过 分割符的长度
            buf.get();
        }
        bodySize += lineDelimiter.length;
        return new String(bs, request.session.server.getCharset());
    }


    /**
     * 查找分割符所匹配下标
     * @param buf 字节流
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


    /**
     * http 请求体 Part
     */
    protected class Part {
        String boundary;
        String name;
        String filename;
        // File   tmpFile;
        Queue<ByteArrayInputStream> fileContent;
        FileData fd;
        boolean headerComplete;
        String value;
        boolean valueComplete;
        InputStream fileInputStream;

        InputStream createInputStream() {
            fileContent = new ConcurrentLinkedQueue<>();
            fileInputStream = new InputStream() {
                protected InputStream currentStream;
                @Override
                public int available() throws IOException {
                    return currentStream == null ? 0 : currentStream.available() + fileContent.stream().mapToInt(ByteArrayInputStream::available).sum();
                }

                @Override
                public int read() throws IOException {
                    if (currentStream == null) {
                        if (fileContent.isEmpty()) return -1;
                        currentStream = fileContent.poll();
                    }
                    int result = currentStream.read();
                    if (result == -1) {
                        currentStream = null;
                        return read();
                    }
                    return result;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (currentStream != null && currentStream.available() >= len) {
                        return currentStream.read(b, off, len);
                    }
                    return super.read(b, off, len);
                }
            };
            return fileInputStream;
        }

        void addFileContent(byte[] contentPart) {
            fileContent.offer(new ByteArrayInputStream(contentPart));
            fd.setSize(fd.getSize() == null ? 0 : fd.getSize() + contentPart.length);
        }
    }
}
