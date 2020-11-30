package cn.xnatural.http;

/**
 * 路径处理器
 */
abstract class PathHandler extends Handler {

    abstract String path();


    // 路径块. /test/pp -> ['test', 'pp']
    private String[] _pieces;
    protected String[] pieces() {
        if (_pieces != null) return _pieces;
        String p = path();
        if (p == null) throw new IllegalArgumentException("PathHandler path must not be null");
        if (p == "/")  _pieces = new String[]{"/"};
        else _pieces = extract(p).split("/");
        return _pieces;
    }


    // 匹配的先后顺序, 越大越先匹配
    private Double _priority;
    protected double priority() {
        if (_priority != null) return _priority;
        if (pieces() == null) return Double.MAX_VALUE;
        double i = pieces().length;
        for (String piece : pieces()) {
            if (piece.startsWith(":")) {
                if (piece.indexOf('.') > 0) i += 0.01d;
                continue;
            } else if (piece.startsWith("~:")) {
                i += 0.001d;
                continue;
            }
            i += 0.1d;
        }
        return i;
    }


    @Override
    double order() { return priority(); }


    @Override
    String type() { return PathHandler.class.getSimpleName(); }


    @Override
    boolean match(HttpContext ctx) {
        if (pieces().length > ctx.pieces.length) return false;
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i].startsWith(":")) {
                int index = pieces[i].indexOf('.');
                if (index == -1) {
                    String v;
                    if ((i + 1) == pieces.length && ctx.pieces.length > pieces.length) { // 最后一个
                        v = ctx.pieces.drop(i).join('/');
                    } else v = ctx.pieces[i];
                    ctx.pathToken.put(pieces[i].substring(1), v);
                } else {
                    int index2 = ctx.pieces[i].indexOf('.');
                    if (index2 > 0 && pieces[i].substring(index) == ctx.pieces[i].substring(index2)) {
                        ctx.pathToken.put(pieces[i].substring(1, index), ctx.pieces[i].substring(0, index2));
                    } else return false;
                }
            } else if (pieces[i] != ctx.pieces[i]) {
                ctx.pathToken.clear();
                return false;
            }
        }
        return true;
    }
