package cn.xnatural.http;

import java.util.function.Supplier;

/**
 * Groovy @Lazy 实现
 * @param <T>
 */
class Lazies<T> implements Supplier<T> {
    private final Supplier<T> supplier;
    // 只执行一次
    private boolean       once = false;
    private T result;

    public Lazies(Supplier<T> supplier) {
        if (supplier == null) throw new NullPointerException("supplier is null");
        this.supplier = supplier;
    }


    /**
     * 清除
     */
    public void clear() {
        once = false;
        result = null;
    }


    @Override
    public T get() {
        if (!once) {
            synchronized (this) {
                if (!once) {
                    result = supplier.get();
                    if (result != null) once = true; //为空,则重新取
                }
            }
        }
        return result;
    }

    @Override
    public String toString() { return result == null ? null : result.toString(); }
}
