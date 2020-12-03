package cn.xnatural.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.AccessControlException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.xnatural.http.HttpServer.log;

/**
 * http 请求 处理上下文
 */
public class HttpContext {
    public final           HttpRequest            request;
    public final HttpResponse                     response  = new HttpResponse();
    protected final HttpAioSession                aioSession;
    protected final HttpServer                    server;
    /**
     * 路径变量值映射
     */
    protected final           Map<String, Object> pathToken = new HashMap<>(7);
    /**
     * 路径块, 用于路径匹配
     */
    protected final LinkedList<String>  pieces        = new LinkedList<>();
    /**
     * 是否已关闭
     */
    protected final AtomicBoolean       closed        = new AtomicBoolean(false);
    /**
     * 请求属性集
     */
    protected final Map<String, Object> attrs         = new ConcurrentHashMap<>();
    /**
     * session 数据
     */
    protected final Map<String, Object> sessionData;
    /**
     * 执行的 {@link Handler}
     */
    protected final List<Handler>       passedHandler = new LinkedList<>();


    /**
     * 请求执行上下文
     * @param request 请求
     * @param server {@link HttpServer}
     * @param sessionDelegate session 委托映射
     */
    HttpContext(HttpRequest request, HttpServer server, Map<String, Object> sessionDelegate) {
        if (request == null) throw new NullPointerException("request must not be null");
        this.request = request;
        this.aioSession = request.session;
        this.server = server;
        this.sessionData = sessionDelegate;

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
            aioSession.close();
        }
    }


    /**
     * session id(会话id)
     * @return
     */
    public String getSessionId() { return (String) sessionData.get("id"); }


    /**
     * 设置请求属性
     * @param key
     * @param value
     * @return
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
     * @return
     */
    public HttpContext setSessionAttr(String aName, Object value) {
        if (sessionData == null) return this;
        if (value == null) sessionData.remove(aName);
        else sessionData.put(aName, value);
        return this;
    }


    /**
     * 获取 session 属性
     * @param key
     * @param type
     * @return
     */
    public <T> T getSessionAttr(String key, Class<T> type) {
        if (sessionData == null) return null;
        Object v = sessionData.get(key);
        if (type != null) return type.cast(v);
        return (T) v;
    }


    /**
     * 权限验证
     * @param permission 权限名 验证用户是否有此权限
     * @return true: 验证通过
     */
    public boolean auth(String permission) {
        if (permission == null || permission.isEmpty()) throw new IllegalArgumentException("permission is empty");
        if (!getSessionAttr("permissions", Set.class).contains(permission)) {
            response.status(403);
            throw new AccessControlException("没有权限");
        }
        return true;
//        def doAuth = {Set<String> uAuthorities -> // 权限验证函数
//            if (uAuthorities.contains(permission)) return true
//            else throw new AccessControlException('没有权限')
//        }
//
//        Set<String> uPermissions = getSessionAttr('permissions', Set) // 当前用户的所有权限. 例: auth1,auth2,auth3
//        if (uPermissions != null) {
//            def f = doAuth(uPermissions)
//            if (f) return true
//        }
        // else uPermissions = ConcurrentHashMap.newKeySet()

//        // 收集用户权限
//        Set<String> uRoles = getSessionAttr('roles', Set) // 当前用户的角色. 例: role1,role2,role3
//        if (uRoles == null) throw new AccessControlException('没有权限')
//        uRoles.each {role ->
//            server.attr('role')[(role)].each {String auth -> uPermissions.add(auth)}
//        }
        // setSessionAttr('uPermissions', uPermissions)
//        doAuth(uPermissions)
    }


    /**
     * 响应请求
     */
    public void render() { render(null); }


    /**
     * 响应请求
     * @param body
     */
    public void render(Object body) {
        boolean f = response.commit.compareAndSet(false, true);
        if (!f) throw new RuntimeException("Already submit response");
        long spend = System.currentTimeMillis() - request.createTime.getTime();
        if (spend > server.getInteger("warnTimeout", 5) * 1000) { // 请求超时警告
            log.warn("Request timeout '" + request.getId() + "', path: " + request.getPath() + " , spend: " + spend + "ms");
        }
        
        try {
            if (body == null) { //无内容返回
                response.statusIfNotSet(204);
                response.contentLengthIfNotSet(0);
                aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset())));
                return;
            } else {
                response.statusIfNotSet(200);
                // HttpResponseEncoder
                if (body instanceof String) { //回写字符串
                    response.contentTypeIfNotSet("text/plain;charset=" + server.getCharset());
                    byte[] bodyBs = ((String) body).getBytes(server.getCharset());
                    response.contentLengthIfNotSet(bodyBs.length);
                    aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset()))); //写header
                    aioSession.send(ByteBuffer.wrap(bodyBs)); // 写body
                } else if (body instanceof ApiResp) {
                    response.contentTypeIfNotSet("application/json;charset=" + server.getCharset());
                    ((ApiResp) body).setMark((String) param("mark"));
                    ((ApiResp) body).setTraceNo(request.getId());
                    body = JSON.toJSONString(body, SerializerFeature.WriteMapNullValue).getBytes(server.getCharset());
                    aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset())));
                    aioSession.send(ByteBuffer.wrap((byte[]) body));
                } else if (body instanceof byte[]) {
                    byte[] bodyBs = (byte[]) body;
                    response.contentLengthIfNotSet(bodyBs.length);
                    aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset()))); //写header
                    aioSession.send(ByteBuffer.wrap(bodyBs)); // 写body
                } else if (body instanceof File) {
                    renderFile((File) body);
                } else throw new Exception("Support response type: " + body.getClass().getName());
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
     * @param file
     * @throws Exception
     */
    protected void renderFile(File file) throws Exception {
        if (!file.exists()) {
            response.status(404);
            log.warn("Request {}({}). id: {}, url: {}", HttpResponse.statusMsg.get(response.status), response.status, request.getId(), request.getRowUrl());
            response.contentLengthIfNotSet(0);
            aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset())));
            return;
        }
        if (file.getName().endsWith(".html")) {
            response.contentTypeIfNotSet("text/html");
        } else if (file.getName().endsWith(".css")) {
            response.contentTypeIfNotSet("text/css");
        } else if (file.getName().endsWith(".js")) {
            response.contentTypeIfNotSet("application/javascript");
        }

        int chunkedSize = chunkedSize((int) file.length(), File.class);
        if (chunkedSize < 0) { // 文件整块传送
            response.contentLengthIfNotSet((int) file.length());
            byte[] content = new byte[(int) file.length()]; // 一次性读出来, 减少IO
            try (InputStream is = new FileInputStream(file)) { is.read(content); }
            aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset()))); // 先写header
            aioSession.send(ByteBuffer.wrap(content)); // 再写body
        } else { //文件分块传送
            response.header("Transfer-Encoding", "chunked");
            aioSession.send(ByteBuffer.wrap(preRespStr().getBytes(server.getCharset()))); // 先写header
            try (InputStream is = new FileInputStream(file)) {
                ByteBuffer buf = ByteBuffer.allocate(chunkedSize);
                boolean end = false;
                do {
                    int b = is.read();
                    if (-1 == b) end = true;
                    else buf.put((byte) b);
                    // buf 填满 或者 结束
                    if (!buf.hasRemaining() || end) {
                        buf.flip();
                        byte[] headerBs = (Integer.toHexString(buf.limit()) + "\r\n").getBytes(server.getCharset());
                        byte[] endBs = "\r\n".getBytes(server.getCharset());
                        ByteBuffer bb = ByteBuffer.allocate(headerBs.length + buf.limit() + endBs.length);
                        bb.put(headerBs); bb.put(buf); bb.put(endBs); bb.flip();
                        aioSession.send(bb);
                        buf.clear();
                    }
                } while (!end);
                // 结束chunk
                aioSession.send(ByteBuffer.wrap("0\r\n\r\n".getBytes(server.getCharset())));
            }
        }
    }


    /**
     * 分段传送, 每段大小
     * @param size 总字节大小
     * @param type 类型
     * @return 每段大小. <0: 不分段
     */
    protected int chunkedSize(int size, Class type) {
        int chunkedSize = -1;
        if (File.class.equals(type)) {
            // 下载限速
            if (size > 1024 * 1024 * 50) { // 大于50M
                chunkedSize = 1024 * 1024 * 4;
            } else if (size > 1024 * 1024) { // 大于1M
                chunkedSize = 1024 * 1024;
            } else if (size > 1024 * 500) { // 大于500K
                chunkedSize = 1024 * 200;
            } else { // 小于500K, 不需要分段传送.  限于带宽(带宽必须大于分块的最小值, 否则会导致前端接收数据不全)
                chunkedSize = -1;
            }
        }
        // TODO 其它类型暂时不分块
        return chunkedSize;
    }
    

    /**
     * http 响应的前半部分(起始行和header)
     * @return
     */
    protected String preRespStr() {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/").append(request.getVersion()).append(" ").append(response.status).append(" ").append(HttpResponse.statusMsg.get(response.status)).append("\r\n"); // 起始行
        response.headers.forEach((key, value) -> sb.append(key).append(": ").append(value).append("\r\n"));
        response.cookies.forEach((key, value) -> sb.append("Set-Cookie: ").append(key).append('=').append(value).append("\r\n"));
        return sb.append("\r\n").toString();
    }


    /**
     * 判断是否应该关闭此次Http连接会话
     */
    protected void determineClose() {
        String connection = request.getConnection();
        if (connection != null && connection.toUpperCase().contains("close")) {
            // http/1.1 规定 只有显示 connection:close 才关闭连接
            close();
        }
    }


    /**
     * 所有参数: 路径参数, query参数, 表单, json
     * @return
     */
    public Map<String, Object> params() {
        Map<String, Object> params = new HashMap();
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
     * @param v
     * @param type
     * @return
     */
    public static <T> T to(Object v, Class<T> type) {
        if (type == null) return (T) v;
        if (v == null) return null;
        else if (String.class.equals(type)) return (T) v.toString();
        else if (Boolean.class.equals(type) || boolean.class.equals(type)) return (T) Boolean.valueOf(v.toString());
        else if (Integer.class.equals(type) || int.class.equals(type)) return (T) Integer.valueOf(v.toString());
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
