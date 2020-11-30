package cn.xnatural.http;

import java.lang.annotation.*;

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
