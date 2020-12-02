package cn.xnatural.http;

/**
 * Filter
 * 一个请求可对应多个 {@link FilterHandler}
 */
abstract class FilterHandler implements Handler {


    @Override
    public String getType() { return FilterHandler.class.getSimpleName(); }


    @Override
    public boolean match(HttpContext ctx) { return true; }
}