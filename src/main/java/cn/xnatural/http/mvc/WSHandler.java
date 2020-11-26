package cn.xnatural.http.mvc;

abstract class WSHandler extends PathHandler {
    @Override
    String type() { return WSHandler.class.getSimpleName(); }
}
