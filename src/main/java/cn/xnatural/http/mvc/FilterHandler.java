package cn.xnatural.http.mvc;

import cn.xnatural.http.HttpContext;

/**
 * Filter
 */
abstract class FilterHandler extends Handler {


    @Override
    String type() { return FilterHandler.class.getSimpleName(); }


    @Override
    boolean match(HttpContext ctx) { return true; }
}