package cn.xnatural.http;

import java.lang.annotation.*;


/**
 * 表明 websocket 注解
 * 接收websocket请求
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WS {

    /**
     * 路径
     * @return
     */
    String path() default "";
}
