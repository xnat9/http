package cn.xnatural.http;

import cn.xnatural.http.common.LazySupplier;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

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
    protected HttpAioSession      session;


    HttpRequest(HttpAioSession session) { this.session = session; }


    private LazySupplier<String> _id = new LazySupplier<>(() -> {
        String id = getHeader("X-Request-ID");
        if (id != null || !id.isEmpty()) return id;
        return UUID.randomUUID().toString().replace("-", "");
    });
    public String id() {
        return _id.get();
    }


    public String contentType() {
        return getHeader("content-type");
    }

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
                cookies.put(arr[0], arr.length > 1 ? null : arr[1]);
            }
            return cookies;
        }
    });
    public Map<String, String> cookies() {
        return _cookies.get();
    }


    /**
     * 查询字符串
     * @return
     */
    private LazySupplier<String> _queryStr = new LazySupplier<>(() -> {
        int i = rowUrl.indexOf("?");
        return i == -1 ? null : rowUrl.substring(i + 1);
    });
    public String queryStr() {
        return _queryStr.get();
    }


    /**
     * 查询参数Map
     * @return
     */
    private LazySupplier<Map<String, Object>> _queryParams = new LazySupplier<>(() -> {
        if (queryStr() != null) {
            Map<String, Object> data = new HashMap<>();
            for (String s : queryStr().split("&")) {
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
    public Map<String, Object> queryParams() {
        return _queryParams.get();
    }


    // 懒计算(只计算一次)例子
    private LazySupplier<String> _path = new LazySupplier<>(() -> {
        int i = rowUrl.indexOf("?");
        return i == -1 ? rowUrl : rowUrl.substring(0, i);
    });
    public String path() {
        return _path.get();
    }


    private LazySupplier<Map<String, Object>> _formParams = new LazySupplier<>(() -> {
        String ct = contentType();
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
    public Map<String, Object> formParams() {
        return _formParams.get();
    }


    private LazySupplier<Map<String, Object>> _jsonParams = new LazySupplier<>(() -> {
        String ct = contentType();
        if (bodyStr != null && !bodyStr.isEmpty() && ct != null && ct.contains("application/json")) {
            try {
                return Collections.unmodifiableMap(JSON.parseObject(bodyStr, Feature.AllowComment, Feature.AllowSingleQuotes));
            } catch (JSONException ex) {
                log.error("Request body is not a JSON: " + bodyStr);
            }
        }
        return Collections.emptyMap();
    });
    public Map<String, Object> jsonParams() {
        return _jsonParams.get();
    }


    /**
     * 取Header值
     * @param hName
     * @return
     */
    public String getHeader(String hName) { return headers.get(hName.toLowerCase()); }


    /**
     * 取cookie值
     * @param cName cookie 名
     * @return cookie 值
     */
    public String cookie(String cName) {
        if (cookies() == null) return null;
        return cookies().get(cName);
    }


    // 请求属性集
    public String getProtocol() { return protocol; }
    public String getMethod() { return method; }
    public String getRowUrl() { return rowUrl; }
    public String getVersion() { return version; }
    public String getBodyStr() { return bodyStr; }
}
