package cn.xnatural.http;

import java.lang.annotation.*;

/**
 * 请求过虑器
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Filter {
    /**
     * 优先级 越大越先执行
     * @return
     */
    int order() default 0;
}
