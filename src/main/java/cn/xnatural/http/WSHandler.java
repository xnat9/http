package cn.xnatural.http;

/**
 * web socket {@link Handler}
 */
abstract class WSHandler extends PathHandler {

    @Override
    public String getType() { return WSHandler.class.getSimpleName(); }
}
