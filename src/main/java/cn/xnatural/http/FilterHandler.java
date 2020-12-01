package cn.xnatural.http;

/**
 * Filter
 */
abstract class FilterHandler implements Handler {


    @Override
    public String getType() { return FilterHandler.class.getSimpleName(); }


    @Override
    public boolean match(HttpContext ctx) { return true; }
}