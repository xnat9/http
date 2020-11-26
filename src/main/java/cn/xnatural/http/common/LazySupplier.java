package cn.xnatural.http.common;

import java.util.function.Supplier;

/**
 * Groovy @Lazy 实现
 * @param <T>
 */
public class LazySupplier<T> implements Supplier<T> {
    private final Supplier<T> supplier;
    // 只执行一次
    private boolean       once = false;
    private T result;

    public LazySupplier(Supplier<T> supplier) {
        if (supplier == null) throw new NullPointerException("supplier is null");
        this.supplier = supplier;
    }

    @Override
    public T get() {
        if (!once) {
            synchronized (this) {
                if (!once) {
                    result = supplier.get();
                    once = true;
                }
            }
        }
        return result;
    }
}
