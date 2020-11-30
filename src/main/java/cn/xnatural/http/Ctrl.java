package cn.xnatural.http;

import java.lang.annotation.*;

/**
 * 标明是个控制器(Controller)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Ctrl {
    /**
     * 路径前缀
     * @return
     */
    String prefix() default "";
}
