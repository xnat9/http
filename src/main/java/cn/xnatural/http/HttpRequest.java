package cn.xnatural.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

/**
 * Http 请求数据
 */
public class HttpRequest {
    protected static final Logger              log        = LoggerFactory.getLogger(HttpRequest.class);
    // 请求的创建时间
    final                  Date                createTime = new Date();
    // HTTP/HTTPS
    protected              String              protocol;
    // GET/POST
    protected              String              method;
    // 原始url地址字符串
    protected              String              rowUrl;
    // http协议版本: 1.0/1.1/1.2
    protected              String              version;
    protected              String              bodyStr;
    protected final        Map<String, String> headers    = new HashMap<>();
    protected final        HttpDecoder         decoder    = new HttpDecoder(this);
    protected final HttpAioSession             session;


    HttpRequest(HttpAioSession session) { this.session = session; }


    private LazySupplier<String> _id = new LazySupplier<>(() -> {
        String id = getHeader("X-Request-ID");
        if (id != null && !id.isEmpty()) return id;
        return UUID.randomUUID().toString().replace("-", "");
    });
    /**
     * 请求id
     * @return
     */
    public String getId() { return _id.get(); }


    private LazySupplier<Map<String, String>> _cookies = new LazySupplier<>(() -> {
        String cookieStr = getHeader("Cookie");
        if (cookieStr == null) return null;
        else {
            Map<String, String> cookies = new HashMap<>();
            for (String entry : cookieStr.split(";")) {
                if (entry == null) continue;
                entry = entry.trim();
                if (entry.isEmpty()) continue;
                String[] arr = entry.split("=");
                cookies.put(arr[0], arr.length > 1 ? arr[1] : null);
            }
            return Collections.unmodifiableMap(cookies);
        }
    });
    /**
     * cookie 值映射
     * @return
     */
    public Map<String, String> getCookies() { return _cookies.get(); }


    /**
     * 查询字符串
     * @return
     */
    private LazySupplier<String> _queryStr = new LazySupplier<>(() -> {
        int i = rowUrl.indexOf("?");
        return i == -1 ? null : rowUrl.substring(i + 1);
    });
    /**
     * 请求url 的查询字符串 ? 后面那坨
     * @return
     */
    public String getQueryStr() { return _queryStr.get(); }


    private LazySupplier<Map<String, Object>> _queryParams = new LazySupplier<>(() -> {
        if (getQueryStr() != null) {
            Map<String, Object> data = new HashMap<>();
            for (String s : getQueryStr().split("&")) {
                String[] arr = s.split("=");
                String name = null;
                try {
                    name = URLDecoder.decode(arr[0], "utf-8");
                    String value = arr.length > 1 ? URLDecoder.decode(arr[1], "utf-8") : null;
                    if (data.containsKey(name)) { // 证明name 有多个值
                        Object v = data.get(name);
                        if (v instanceof List) ((List) v).add(value);
                        else {
                            data.put(name, new LinkedList<>(Arrays.asList(v, value)));
                        }
                    } else {
                        data.put(name, value);
                    }
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            }
            return Collections.unmodifiableMap(data);
        }
        return Collections.emptyMap();
    });
    /**
     * 请求查询参数Map
     * @return
     */
    public Map<String, Object> getQueryParams() { return _queryParams.get(); }


    // 懒计算(只计算一次)例子
    private LazySupplier<String> _path = new LazySupplier<>(() -> {
        int i = rowUrl.indexOf("?");
        return i == -1 ? rowUrl : rowUrl.substring(0, i);
    });
    /**
     * 请求路径
     * @return
     */
    public String getPath() { return _path.get(); }


    private LazySupplier<Map<String, Object>> _formParams = new LazySupplier<>(() -> {
        String ct = getContentType();
        if (bodyStr != null && !bodyStr.isEmpty() && ct != null && ct.contains("application/x-www-form-urlencoded")) {
            Map<String, Object> data = new HashMap<>();
            for (String s : bodyStr.split("&")) {
                String[] arr = s.split("=");
                try {
                    String name = URLDecoder.decode(arr[0], "utf-8");
                    String value = arr.length > 1 ? URLDecoder.decode(arr[1], "utf-8") : null;
                    if (data.containsKey(name)) { // 证明name 有多个值
                        Object v = data.get(name);
                        if (v instanceof List) {
                            ((List) v).add(value);
                        } else {
                            data.put(name, new LinkedList<>(Arrays.asList(v, value)));
                        }
                    } else {
                        data.put(name, value);
                    }
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            }
            return Collections.unmodifiableMap(data);
        }
        if (ct != null && ct.contains("multipart/form-data") && decoder.multiForm != null) {
            return Collections.unmodifiableMap(decoder.multiForm);
        }
        return Collections.emptyMap();
    });
    /**
     * 表单参数
     * Content-Type: application/x-www-form-urlencoded
     * @return
     */
    public Map<String, Object> getFormParams() { return _formParams.get(); }


    private LazySupplier<Map<String, Object>> _jsonParams = new LazySupplier<>(() -> {
        String ct = getContentType();
        if (bodyStr != null && !bodyStr.isEmpty() && ct != null && ct.contains("application/json")) {
            try {
                return Collections.unmodifiableMap(JSON.parseObject(bodyStr, Feature.AllowComment, Feature.AllowSingleQuotes));
            } catch (JSONException ex) {
                log.error("Request body is not a JSON: " + bodyStr);
            }
        }
        return Collections.emptyMap();
    });
    /**
     * json 参数
     * @return
     */
    public Map<String, Object> getJsonParams() { return _jsonParams.get(); }


    /**
     * 请求头: Content-Type
     * @return
     */
    public String getContentType() { return getHeader("content-type"); }


    /**
     * 请求头: Accept
     * @return
     */
    public String getAccept() { return getHeader("Accept"); }


    /**
     * 请求头: Accept-Encoding
     * @return
     */
    public String getAcceptEncoding() { return getHeader("Accept-Encoding"); }


    /**
     * 请求头: Connection
     * @return
     */
    public String getConnection() { return getHeader("Connection"); }


    /**
     * 请求头: Host
     * @return
     */
    public String getHost() { return getHeader("Host"); }


    /**
     * 请求头: User-Agent
     * @return
     */
    public String getUserAgent() { return getHeader("User-Agent"); }


    /**
     * 请求头: Upgrade
     * @return
     */
    public String getUpgrade() { return getHeader("Upgrade"); }


    /**
     * 取Header值
     * @param hName header 名
     * @return
     */
    public String getHeader(String hName) { return headers.get(hName.toLowerCase()); }


    /**
     * 取cookie值
     * @param cName cookie 名
     * @return cookie 值
     */
    public String getCookie(String cName) {
        if (getCookies() == null) return null;
        return getCookies().get(cName);
    }


    /**
     * http 协议: http, https
     * @return
     */
    public String getProtocol() { return protocol; }

    /**
     * 请求方法: get, post...
     * @return
     */
    public String getMethod() { return method; }

    /**
     * 原始url
     * @return
     */
    public String getRowUrl() { return rowUrl; }

    /**
     * http 版本
     * @return
     */
    public String getVersion() { return version; }

    /**
     * 请求body字符串
     * @return
     */
    public String getBodyStr() { return bodyStr; }
}
