package cn.xnatural.http;

import java.lang.annotation.*;

/**
 * 控制层 路径
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Path {
    /**
     * 接口路径
     * @return
     */
    String[] path();
    /**
     * get,post,delete
     * @return
     */
    String method() default "";
    /**
     * 指定 request 接收那些 Content-Type
     * application/json, multipart/form-data, application/x-www-form-urlencoded, text/plain
     * @return
     */
    String[] consumer() default {};
    /**
     * 指定 response的 Content-Type
     * @return
     */
    String produce() default "";
}
