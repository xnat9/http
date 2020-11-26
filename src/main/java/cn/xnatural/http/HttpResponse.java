package cn.xnatural.http;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * http 响应对象
 */
public class HttpResponse {
    protected Integer status;
    protected final Map<String, String>  headers = new HashMap<>();
    protected final Map<String, String>  cookies = new HashMap<>();
    protected final AtomicBoolean        commit  = new AtomicBoolean(false);
    static final    Map<Integer, String> statusMsg;
    static final String CONTENT_TYPE = "content-type";


    static {
        Map<Integer, String> m = new HashMap<>();
        m.put(100, "CONTINUE"); m.put(101, "SWITCHING_PROTOCOLS"); m.put(102, "PROCESSING"); m.put(103, "EARLY_HINTS");
        m.put(200, "OK"); m.put(201, "CREATED"); m.put(202, "ACCEPTED"); m.put(203, "NON_AUTHORITATIVE_INFO");
        m.put(204, "NO_CONTENT"); m.put(205, "RESET_CONTENT"); m.put(206, "PARTIAL_CONTENT"); m.put(207, "MULTI_STATUS");
        m.put(208, "ALREADY_REPORTED"); m.put(226, "IM_USED"); m.put(300, "MULTIPLE_CHOICES"); m.put(301, "MOVED_PERMANENTLY");
        m.put(302, "FOUND"); m.put(303, "SEE_OTHER"); m.put(304, "NOT_MODIFIED"); m.put(305, "USE_PROXY");
        m.put(307, "TEMPORARY_REDIRECT"); m.put(308, "PERMANENT_REDIRECT"); m.put(400, "BAD_REQUEST"); m.put(401, "UNAUTHORIZED");
        m.put(402, "PAYMENT_REQUIRED"); m.put(403, "FORBIDDEN"); m.put(404, "NOT_FOUND"); m.put(405, "METHOD_NOT_ALLOWED");
        m.put(406, "NOT_ACCEPTABLE"); m.put(407, "PROXY_AUTH_REQUIRED"); m.put(408, "REQUEST_TIMEOUT"); m.put(409, "CONFLICT");
        m.put(410, "GONE"); m.put(411, "LENGTH_REQUIRED"); m.put(412, "PRECONDITION_FAILED"); m.put(413, "PAYLOAD_TOO_LARGE");
        m.put(414, "URI_TOO_LONG"); m.put(415, "UNSUPPORTED_MEDIA_TYPE"); m.put(416, "RANGE_NOT_SATISFIABLE"); m.put(417, "EXPECTATION_FAILED");
        m.put(418, "IM_A_TEAPOT"); m.put(421, "MISDIRECTED_REQUEST"); m.put(422, "UNPROCESSABLE_ENTITY"); m.put(423, "LOCKED");
        m.put(424, "FAILED_DEPENDENCY"); m.put(426, "UPGRADE_REQUIRED"); m.put(428, "PRECONDITION_REQUIRED"); m.put(429, "TOO_MANY_REQUESTS");
        m.put(431, "HEADER_FIELDS_TOO_LARGE"); m.put(451, "UNAVAILBLE_FOR_LEGAL_REASONS"); m.put(500, "INTERNAL_SERVER_ERROR"); m.put(501, "NOT_IMPLEMENTED");
        m.put(502, "BAD_GATEWAY"); m.put(503, "SERVICE_UNAVAILABLE"); m.put(504, "GATEWAY_TIMEOUT"); m.put(505, "HTTP_VER_NOT_SUPPORTED");
        m.put(506, "VARIANT_ALSO_NEGOTIATES"); m.put(507, "INSUFFICIENT_STORAGE"); m.put(508, "LOOP_DETECTED"); m.put(510, "NOT_EXTENDED");
        m.put(511, "NETWORK_AUTH_REQUIRED");
        statusMsg = Collections.unmodifiableMap(m);
    }


    HttpResponse status(int status) {this.status = status; return this;}
    HttpResponse statusIfNotSet(int status) {if (this.status == null) this.status = status; return this;}


    /**
     * 设置cookie
     * @param cName cookie 名
     * @param cValue cookie 值
     * @param maxAge 单位:秒
     * @param domain
     * @param path
     * @param secure
     * @param httpOnly
     * @return
     */
    HttpResponse cookie(
            String cName, String cValue, Integer maxAge, String domain, String path, Boolean secure, Boolean httpOnly
    ) {
        cookies.put(cName,
                (cValue == null ? "" : cValue)
                        + (maxAge == null ? "" : "; max-age="+maxAge)
                        + (domain == null ? "" : "; domain="+domain)
                        + (path == null ? "" : "; path="+path)
                        + (secure == true ? "; secure": "")
                        + (httpOnly == true ? "; httpOnly": "")
        );
        return this;
    }


    /**
     * {@link #cookie(String, String, Integer, String, String, Boolean, Boolean)}
     * @param cName
     * @param cValue
     * @param maxAge
     * @return
     */
    HttpResponse cookie(String cName, String cValue, Integer maxAge) {
        return cookie(cName, cValue, maxAge, null, null, null, null);
    }


    /**
     * 让 cookie 过期
     * @param cName cookie 名
     */
    HttpResponse expireCookie(String cName) { return cookie(cName, "", 0); }


    /**
     * 设置header
     * @param hName
     * @param hValue
     * @return
     */
    HttpResponse header(String hName, Object hValue) {
        headers.put(hName.toLowerCase(), hValue == null ? null : hValue.toString());
        return this;
    }


    /**
     * 获取header值
     * @param hName
     * @return
     */
    String header(String hName) { return headers.get(hName.toLowerCase()); }


    /**
     * 控制缓存失效时间
     * @param maxAge
     * @return
     */
    HttpResponse cacheControl(Integer maxAge) {
        header("cache-control", "max-age=" + maxAge);
        return this;
    }


    HttpResponse contentType(CharSequence contentType) { return header("content-type", contentType); }


    HttpResponse contentTypeIfNotSet(CharSequence contentType) {
        if (!headers.containsKey(CONTENT_TYPE)) {
            header(CONTENT_TYPE, contentType);
        }
        return this;
    }


    HttpResponse contentLengthIfNotSet(int length) {
        if (!headers.containsKey("content-length")) {
            header("content-length", length);
        }
        return this;
    }
}
