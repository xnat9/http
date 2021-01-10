package cn.xnatural.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static cn.xnatural.http.HttpServer.log;

/**
 * http 请求 处理上下文
 */
public class HttpContext {
    public final           HttpRequest            request;
    public final HttpResponse                     response  = new HttpResponse();
    protected final HttpAioSession                aioStream;
    protected final HttpServer                    server;
    /**
     * 路径变量值映射
     */
    protected final           Map<String, Object> pathToken = new HashMap<>(7);
    /**
     * 路径块, 用于路径匹配 /test/p1/p2 -> test,p1,p2
     */
    protected final LinkedList<String>            pieces        = new LinkedList<>();
    /**
     * 是否已关闭
     */
    protected final AtomicBoolean                 closed        = new AtomicBoolean(false);
    /**
     * 请求属性集
     */
    protected final Map<String, Object>           attrs         = new ConcurrentHashMap<>();
    /**
     * session 数据操作委托
     */
    protected final LazySupplier<Map<String, Object>> sessionSupplier;
    /**
     * 执行的 {@link Handler}
     */
    protected final List<Handler>                 passedHandler = new LinkedList<>();


    /**
     * 请求执行上下文
     * @param request 请求
     * @param server {@link HttpServer}
     * @param sessionDelegate session 委托映射
     */
    HttpContext(HttpRequest request, HttpServer server, Function<HttpContext, Map<String, Object>> sessionDelegate) {
        if (request == null) throw new NullPointerException("request must not be null");
        this.request = request;
        this.aioStream = request.session;
        this.server = server;
        this.sessionSupplier = new LazySupplier<>(() -> sessionDelegate.apply(this));

        if ("/".equals(request.getPath())) this.pieces.add("/");
        else {
            for (String piece : Handler.extract(request.getPath()).split("/")) {
                pieces.add(piece);
            }
            if (request.getPath().endsWith("/")) this.pieces.add("/");
        }
    }


    /**
     * 关闭
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            aioStream.close();
        }
    }


    /**
     * 从cookie中取session 标识
     * @return session id(会话id)
     */
    public String getSessionId() {
        Map<String, Object> data = sessionSupplier.get();
        if (data == null) return null;
        return (String) data.get("id");
    }


    /**
     * 设置请求属性
     * @param key 属性key
     * @param value 属性值
     * @return {@link HttpContext}
     */
    public HttpContext setAttr(String key, Object value) { attrs.put(key, value); return this; }


    /**
     * 获取请求属性
     * @param aName 属性名
     * @param aType 属性类型
     * @return 属性值
     */
    public <T> T getAttr(String aName, Class<T> aType) {
        Object v = attrs.get(aName);
        if (aType != null) return aType.cast(v);
        return (T) v;
    }


    /**
     * 设置 session 属性
     * @param aName 属性名
     * @param value 属性值
     * @return {@link HttpContext}
     */
    public HttpContext setSessionAttr(String aName, Object value) {
        if (sessionSupplier.get() == null) return this;
        if (value == null) sessionSupplier.get().remove(aName);
        else sessionSupplier.get().put(aName, value);
        return this;
    }


    /**
     * 删除 session 属性
     * @param aName 属性名
     * @return 属性值
     */
    public Object removeSessionAttr(String aName) {
        if (sessionSupplier.get() == null) return null;
        return sessionSupplier.get().remove(aName);
    }


    /**
     * 获取 session 属性
     * @param aName 属性名
     * @return 属性值
     */
    public Object getSessionAttr(String aName) {
        if (sessionSupplier.get() == null) return null;
        return sessionSupplier.get().get(aName);
    }


    /**
     * 权限验证
     * @param permissions 权限名 验证用户是否有此权限
     * @return true: 验证通过
     */
    public boolean auth(String... permissions) { return server.auth(this, permissions); }

    /**
     * 是否存在权限
     * @param permissions
     * @return true: 存在
     */
    public boolean hasAuth(String... permissions) { return server.hasAuth(this, permissions); }


    /**
     * 响应请求
     */
    public void render() { render(null); }


    /**
     * 响应请求
     * @param body 响应内容
     */
    public void render(Object body) {
        if (!response.commit.compareAndSet(false, true)) {
            throw new RuntimeException("Already submit response");
        }
        long spend = System.currentTimeMillis() - request.createTime.getTime();
        if (spend > server.getInteger("warnTimeout", 5) * 1000) { // 请求超时警告
            log.warn("Request timeout '" + request.getId() + "', path: " + request.getPath() + " , spend: " + spend + "ms");
        }
        
        try {
            if (body == null) { //无内容返回
                response.statusIfNotSet(204);
                response.contentLengthIfNotSet(0);
                aioStream.write(ByteBuffer.wrap(preRespBytes()));
                return;
            } else {
                response.statusIfNotSet(200);
                // HttpResponseEncoder
                if (body instanceof String) { //回写字符串
                    response.contentTypeIfNotSet("text/plain;charset=" + server.getCharset());
                    byte[] bodyBs = ((String) body).getBytes(server.getCharset());
                    response.contentLengthIfNotSet(bodyBs.length);
                    aioStream.write(ByteBuffer.wrap(preRespBytes())); //写header
                    aioStream.write(ByteBuffer.wrap(bodyBs)); // 写body
                } else if (body instanceof ApiResp) {
                    response.contentTypeIfNotSet("application/json;charset=" + server.getCharset());
                    ((ApiResp) body).setMark((String) param("mark"));
                    ((ApiResp) body).setTraceNo(request.getId());
                    byte[] bodyBs = JSON.toJSONString(body, SerializerFeature.WriteMapNullValue).getBytes(server.getCharset());
                    response.contentLengthIfNotSet(bodyBs.length);
                    aioStream.write(ByteBuffer.wrap(preRespBytes()));
                    aioStream.write(ByteBuffer.wrap(bodyBs));
                } else if (body instanceof File) {
                    renderFile((File) body);
                } else if (response.getContentType() != null) {
                    String ct = response.getContentType();
                    if (ct.contains("application/json")) {
                        byte[] bodyBs = JSON.toJSONString(body, SerializerFeature.WriteMapNullValue).getBytes(server.getCharset());
                        response.contentLengthIfNotSet(bodyBs.length);
                        aioStream.write(ByteBuffer.wrap(preRespBytes()));
                        aioStream.write(ByteBuffer.wrap(bodyBs));
                    } else if (ct.contains("text/plain")) {
                        byte[] bodyBs = body.toString().getBytes(server.getCharset());
                        response.contentLengthIfNotSet(bodyBs.length);
                        aioStream.write(ByteBuffer.wrap(preRespBytes()));
                        aioStream.write(ByteBuffer.wrap(bodyBs));
                    } else throw new Exception("Not support response Content-Type: " + ct);
                } else throw new Exception("Not support response type: " + body.getClass().getName());
            }
            determineClose();
        } catch (Exception ex) {
            log.error("Http response error", ex);
            close();
        }
    }


    /**
     * 渲染文件
     * 分块传送. js 加载不全, 可能是网速限制的原因
     * @param file 文件
     * @throws Exception
     */
    protected void renderFile(File file) throws Exception {
        if (!file.exists()) {
            response.status(404);
            log.warn("Request {}({}). id: {}, url: {}", HttpResponse.statusMsg.get(response.status), response.status, request.getId(), request.getRowUrl());
            response.contentLengthIfNotSet(0);
            aioStream.write(ByteBuffer.wrap(preRespBytes()));
            close(); return;
        }
        if (file.getName().endsWith(".html")) {
            response.contentTypeIfNotSet("text/html");
        } else if (file.getName().endsWith(".css")) {
            response.contentTypeIfNotSet("text/css");
        } else if (file.getName().endsWith(".js")) {
            response.contentTypeIfNotSet("application/javascript");
        }

        int chunkedSize = server.chunkedSize(this, (int) file.length(), File.class);
        if (chunkedSize < 0) { // 不分块, 文件整块传送
            byte[] content = new byte[(int) file.length()]; // 一次性读出来, 减少IO
            try (InputStream fis = new FileInputStream(file)) { fis.read(content); }
            response.contentLengthIfNotSet(content.length);
            aioStream.write(ByteBuffer.wrap(preRespBytes())); //1. 先写header
            aioStream.write(ByteBuffer.wrap(content)); //2. 再写body
        } else { // 文件分块传送
            // response.contentLengthIfNotSet((int) file.length());
            response.transferEncoding("chunked");
            response.contentTypeIfNotSet("application/octet-stream");
            aioStream.write(ByteBuffer.wrap(preRespBytes())); // 1. 先写公共header
            try (InputStream fis = new FileInputStream(file)) { // 2. 再写文件内容
                byte[] buf = new byte[chunkedSize]; // 数据缓存buf
                boolean end;
                do {
                    int length = fis.read(buf); // 一批一批的读, 减少IO
                    end = length < chunkedSize; // 是否已结束(当读出来的数据少于chunkedSize)
                    if (length > 0) {
                        aioStream.write(ByteBuffer.wrap((Integer.toHexString(length) + "\r\n").getBytes(server.getCharset()))); //1. 写chunked: header
                        aioStream.write(ByteBuffer.wrap(buf, 0, length)); //2. 写chunked: body
                        aioStream.write(ByteBuffer.wrap("\r\n".getBytes(server.getCharset()))); //2. 写chunked: end
                    }
                    // Thread.sleep(200L); // 阿里云网速限制
                } while (!end);
                //3. 结束chunk
                aioStream.write(ByteBuffer.wrap("0\r\n\r\n".getBytes(server.getCharset())));
            }
        }
    }


    /**
     * http 响应的前半部分
     * 包含: 起始行, 公共 header
     * @return
     */
    protected byte[] preRespBytes() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/").append(request.getVersion()).append(" ").append(response.status).append(" ").append(HttpResponse.statusMsg.get(response.status)).append("\r\n"); // 起始行
        response.headers.forEach((key, value) -> sb.append(key).append(": ").append(value).append("\r\n"));
        response.cookies.forEach((key, value) -> sb.append("Set-Cookie: ").append(key).append("=").append(value).append("\r\n"));
        return sb.append("\r\n").toString().getBytes(server.getCharset());
    }


    /**
     * 判断是否应该关闭此次Http连接会话
     */
    protected void determineClose() {
        String connection = request.getConnection();
        if (connection != null && connection.toLowerCase().contains("close")) {
            // http/1.1 规定 只有显示 connection:close 才关闭连接
            close();
        }
    }


    /**
     * 所有参数: 路径参数, query参数, 表单, json
     * @return
     */
    public Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.putAll(request.getJsonParams());
        params.putAll(request.getFormParams());
        params.putAll(request.getQueryParams());
        params.putAll(pathToken);
        return params;
    }


    /**
     * {@link #param(String, Class)}
     * @param pName
     * @return
     */
    public Object param(String pName) { return param(pName, null); }


    /**
     * 取请求参数值
     * @param pName 参数名
     * @param type 结果类型
     * @return 参数值
     */
    public <T> T param(String pName, Class<T> type) {
        if (type != null && HttpContext.class.isAssignableFrom(type)) return (T) this;
        if (type != null && HttpServer.class.isAssignableFrom(type)) return (T) server;
        Object v = pathToken.get(pName);
        if (v == null) v = request.getQueryParams().get(pName);
        if (v == null) v = request.getFormParams().get(pName);
        if (v == null) v = request.getJsonParams().get(pName);
        if (v == null && type != null && type.isArray()) { // 数组参数名后边加个[]
            pName = pName + "[]";
            v = pathToken.get(pName);
            if (v == null) v = request.getQueryParams().get(pName);
            if (v == null) v = request.getFormParams().get(pName);
            if (v == null) v = request.getJsonParams().get(pName);
        }
        if (type == null) return (T) v;
        else if (v == null) return null;
        else if (FileData.class.isAssignableFrom(type)) return (T) (v instanceof List ? (((List) v).get(0)) : v);
        else if (FileData[].class.equals(type)) return v instanceof List ? (T) ((List) v).toArray() : (T) new FileData[]{(FileData) v};
        else if (type.isArray()) {
            List ls = new LinkedList();
            if (v instanceof List) {
                for (Object o : ((List) v)) { ls.add(to(o, type.getComponentType())); }
                return (T) ls.toArray((T[]) Array.newInstance(type.getComponentType(), ((List) v).size()));
            } else {
                ls.add(to(v, type.getComponentType()));
                return (T) ls.toArray((T[]) Array.newInstance(type.getComponentType(), 1));
            }
        } else return to(v, type);
    }


    /**
     * 类型转换
     * @param v 值
     * @param type 转换的类型
     * @return 转换后的结果
     */
    public static <T> T to(Object v, Class<T> type) {
        if (type == null) return (T) v;
        if (v == null) return null;
        else if (String.class.equals(type)) return (T) v.toString();
        else if (Boolean.class.equals(type) || boolean.class.equals(type)) return (T) Boolean.valueOf(v.toString());
        else if (Short.class.equals(type) || short.class.equals(type)) return (T) Short.valueOf(v.toString());
        else if (Integer.class.equals(type) || int.class.equals(type)) return (T) Integer.valueOf(v.toString());
        else if (BigInteger.class.equals(type)) return (T) new BigInteger(v.toString());
        else if (Long.class.equals(type) || long.class.equals(type)) return (T) Long.valueOf(v.toString());
        else if (Double.class.equals(type) || double.class.equals(type)) return (T) Double.valueOf(v.toString());
        else if (Float.class.equals(type) || float.class.equals(type)) return (T) Float.valueOf(v.toString());
        else if (BigDecimal.class.equals(type)) return (T) new BigDecimal(v.toString());
        else if (URI.class.equals(type)) return (T) URI.create(v.toString());
        else if (URL.class.equals(type)) {
            try {
                return (T) URI.create(v.toString()).toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        else if (type.isEnum()) return Arrays.stream(type.getEnumConstants()).filter((o) -> v.equals(((Enum) o).name())).findFirst().orElse(null);
        return (T) v;
    }
}
