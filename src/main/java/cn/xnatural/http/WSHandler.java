package cn.xnatural.http;

abstract class WSHandler extends PathHandler {
    @Override
    String type() { return WSHandler.class.getSimpleName(); }
}
