package cn.xnatural.http;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口响应数据结构
 * @param <T>
 */
public class ApiResp<T> implements Serializable {
    public static String OK_CODE = "00";
    public static String FAIL_CODE = "01";
    /**
     * 请求是否成功
     * 00: 正常
     * 01: 通用错误
     */
    private String code;
    /**
     * 请求返回的数据
     */
    private T      data;
    /**
     * 当前请求返回说明
     */
    private String desc;
    /**
     * 返回处理流水号
     */
    private String traceNo;
    /**
     * 标记(调用方的入参, 原样返回)
     */
    private String mark;


    public static <T> ApiResp<T> ok() { return new ApiResp().setCode(OK_CODE); }


    public static <T> ApiResp ok(T data) { return new ApiResp().setCode(OK_CODE).setData(data); }


    public static <T> ApiResp of(String code, String desc) { return new ApiResp().setCode(code).setDesc(desc); }


    public static <T> ApiResp fail(String errMsg) { return new ApiResp().setCode(FAIL_CODE).setDesc(errMsg); }


    /**
     * 一般用法 ApiResp.ok().attr("aaa", 111).attr("bbb", 222)
     *
     * @param attrName 属性名
     * @param attrValue 属性值
     * @return
     */
    public ApiResp<Map<String, Object>> attr(String attrName, Object attrValue) {
        if (data == null) {
            data = (T) new LinkedHashMap<String, Object>(7);
        }
        if (!(data instanceof Map)) {
            throw new IllegalArgumentException("data类型必须为Map类型");
        }
        ((Map) data).put(attrName, attrValue);
        return (ApiResp<Map<String, Object>>) this;
    }


    public ApiResp<Map<String, Object>> attrs(Map<String, Object> attrs) {
        attrs.forEach((s, o) -> attr(s, o));
        return (ApiResp<Map<String, Object>>) this;
    }


    public ApiResp desc(String desc) {this.desc = desc; return this;}


    @Override
    public String toString() {
        return ApiResp.class.getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[code=" + code + ", traceNo=" + traceNo + ", desc=" + desc + ", mark: " + mark + ", data=" + data +"]";
    }


    public String getCode() { return code; }

    public ApiResp<T> setCode(String code) {
        this.code = code;
        return this;
    }

    public T getData() { return data; }

    public ApiResp<T> setData(T data) {
        this.data = data;
        return this;
    }

    public String getDesc() { return desc; }

    public ApiResp<T> setDesc(String desc) {
        this.desc = desc;
        return this;
    }

    public String getTraceNo() { return traceNo; }

    public ApiResp<T> setTraceNo(String traceNo) {
        this.traceNo = traceNo;
        return this;
    }

    public String getMark() { return mark; }

    public ApiResp<T> setMark(String mark) {
        this.mark = mark;
        return this;
    }
}