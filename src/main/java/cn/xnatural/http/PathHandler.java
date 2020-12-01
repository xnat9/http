package cn.xnatural.http;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 路径处理器
 */
abstract class PathHandler implements Handler {

    abstract String path();


    // 路径块. /test/pp -> ['test', 'pp']
    private LazySupplier<String[]> _pieces = new LazySupplier<>(() -> {
        String p = path();
        if (p == null) throw new IllegalArgumentException("PathHandler path must not be null");
        if (p == "/") return new String[]{"/"};
        return Handler.extract(p).split("/");
    });

    protected String[] pieces() {
        return _pieces.get();
    }


    // 匹配的先后顺序, 越大越先匹配
    private LazySupplier<Double> _order = new LazySupplier<>(() -> {
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
    });

    @Override
    public double getOrder() {
        return _order.get();
    }


    @Override
    public String getType() {
        return PathHandler.class.getSimpleName();
    }


    /**
     * 路径匹配
     *
     * @param hCtx
     * @return
     */
    @Override
    public boolean match(HttpContext hCtx) {
        // 不匹配: 请求路径片 少于 当前handler路径片
        if (hCtx.pieces.size() < pieces().length) return false;
        for (int i = 0; i < pieces().length; i++) { // 依次遍历路径的每个分片进行匹配
            if (pieces()[i].startsWith(":")) { // 路径变量
                int index = pieces()[i].indexOf('.');
                if (index == -1) { // 冒号变量片. 例: ":fName"
                    String v;
                    if ((i + 1) == pieces().length && hCtx.pieces.size() > pieces().length) {
                        // 最后一个. 例: 请求路径: /p1/p2/p3, 当前Handler路径: /p2/:var, 则var路径变量的值为: p2/p3
                        v = hCtx.pieces.stream().skip(i).collect(Collectors.joining("/"));
                    } else v = hCtx.pieces.get(i);
                    hCtx.pathToken.put(pieces()[i].substring(1), v); // 填充路径变量
                } else { // 冒号变量片. 例: ":fName.js"
                    int index2 = hCtx.pieces.get(i).indexOf('.');
                    if (index2 > 0 && Objects.equals(pieces()[i].substring(index), hCtx.pieces.get(i).substring(index2))) {
                        hCtx.pathToken.put(pieces()[i].substring(1, index), hCtx.pieces.get(i).substring(0, index2)); // 填充路径变量
                    } else return false;
                }
            } else if (!Objects.equals(pieces()[i], hCtx.pieces.get(i))) {// 路径不匹配: 只有要一个路径片不一样
                hCtx.pathToken.clear();
                return false;
            }
        }
        return true;
    }

}
