# 介绍
轻量级 http 服务 基于 jdk aio

# 安装教程
```xml
<dependency>
    <groupId>cn.xnatural.http</groupId>
    <artifactId>http</artifactId>
    <version>1.0.8</version>
</dependency>
```
# 打包编译(保留方法参数名, 非arg0/arg1...)
maven
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.8.0</version>
    <configuration>
        <source>1.8</source>
        <target>1.8</target>
        <!-- 编译时保留方法参数名 -->
        <parameters>true</parameters>
    </configuration>
</plugin>
```
gradle
```
compileGroovy {
    groovyOptions.parameters = true
}
```

# 快速开始
## 属性配置
*  hp: 绑定ip:端口. 例: localhost:7070 or 127.0.0.1:7070 or :7070 
*  textBodyMaxLength: 文本body长度限制. 默认10M
*  textPartValueMaxLength: 文本part值最大长度限制. 默认5M
*  filePartValueMaxLength: 文件part值最大长度限制(即: 单个请求上传单文件最大长度限制). 默认20M
*  fileMaxLength: 文件最大长度限制(即: 分片上传的最大文件限制). 默认200M
*  maxConnection: 最大连接数. 默认 128
```java
Map<String, Object> attrs = new HashMap<>();
attrs.put("hp", ":7070");
attrs.put("fileMaxLength", 1024 * 1024 * 500); // 500M
ThreadPoolExecutor exec = new ThreadPoolExecutor(
    4, 8, 6, TimeUnit.HOURS,
    new LinkedBlockingQueue<>(100000),
    new ThreadFactory() {
      final AtomicInteger i = new AtomicInteger(1);
      @Override
      public Thread newThread(Runnable r) {
          return new Thread(r, "http-" + i.getAndIncrement());
      }
    }
);
final HttpServer server = new HttpServer(attrs, exec);
```
## 手动执行链
```java
final HttpServer server = new HttpServer().buildChain((chain -> {
    // 手动自定义添加接口
    chain.get("get", hCtx -> {
        hCtx.render("get ... ");
    }).post("post", hCtx -> {
        hCtx.render("post ...");
    }).prefix("testPrefix", ch -> { // 多层路径. /testPrefix/test2222
        ch.get("test2222", hCtx -> {
            hCtx.render("test2222");
        });
    });
})).start();

// http://localhost:7070/get
// http://localhost:7070/post
// http://localhost:7070/testPrefix/test2222

```
## 控制层类
```java
final HttpServer server = new HttpServer().ctrls( // 添加Controller层类
    MainCtrl.class, TestCtrl.class
).start();
```
### @Ctrl 表明是个控制层类
```java
@Ctrl(prefix = "test")
public class TestCtrl {
    // 处理器(路由)方法
}
```
### @Path 路径处理器
```java
@Ctrl(prefix = "test")
public class TestCtrl {
    //@Path 定义一个路径处理器, 接收 /test/handler1 路径的请求
    @Path(path = "handler1")
    ApiResp get() { return ApiResp.ok(); }

    // 主页
    @Path(path = {"index.html", "/"})
    File index(HttpContext ctx) {
        ctx.response.cacheControl(10); // 设置缓存
        return new File("src/test/main/resources/static/index.html");
    }

    //接收参数
    @Path(path = "handler2")
    ApiResp get(Integer p1, String p2, HttpServer server, HttpContext hCtx) {
        return ApiResp.ok().attr("p1", p1);
    }

    // 接收form 表单提交. consumer 指定 request 接收那些 Content-Type
    @Path(path = "form", consumer = "application/x-www-form-urlencoded")
    ApiResp form(Integer p1, String p2, HttpContext ctx) {
        return ApiResp.ok(ctx.request.getFormParams());
    }

    // json 参数
    @Path(path = "json", consumer = "application/json")
    ApiResp json(HttpContext ctx) {
        return ApiResp.ok(ctx.request.getJsonParams());
    }

    // 响应json结果 . produce 指定 response的 Content-Type
    // 1. produce = "application/json"; 2. 返回 ApiResp对象
    @Path(path = "json", produce = "application/json")
    Object jsonResponse() { return obj; }

    // 异步响应(手动ctx.render). 非void返回为默认接口响应
    @Path(path = "async")
    void async(String p1, HttpContext ctx) {
        ctx.render(
                ApiResp.ok("p1: " + p1 + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
        );
    }

    // 路径变量
    @Path(path = "js/:fName")
    File js(String fName, HttpContext ctx, HttpServer server) {
        if ("pro".equals(server.getStr("profile", null))) {
            ctx.response.cacheControl(1800);
        }
        return new File("src/test/main/resources/static/js/" + fName);
    }
}
```
#### @Path 接收的参数类型
String, Boolean, Short, Integer, BigInteger, Long, Double, Float, BigDecimal, URI, URL, FileData,
String[], Boolean[], Short[], Integer[], BigInteger[], Long[], Double[], Float[], BigDecimal[], URI[], URL[], FileData[]
HttpContext, HttpServer

## 过滤器@Filter
> /test/ 路径开头的所有请求, 都会按顺序执行 filter. order 越大越先执行

> 方法必须返回void
```java
@Ctrl(prefix = "test")
public class TestCtrl {
    @Filter(order = 1)
    void filter1(HttpContext ctx) {
        log.info("filter1 ============");
    }

    @Filter(order = 2)
    void filter2(HttpContext ctx) {
        log.info("filter2 ============");
    }

    // 定义一个路径 /test/get 的请求
    @Path(path = "get")
    ApiResp get(Integer p1, String p2) {
        return ApiResp.ok().attr("p1", p1).attr("p2", p2);
    }
}
```

## Session
```java
// 设置Session属性
hCtx.setSessionAttr("username", "方羽");
```
```java
// 删除Session属性
hCtx.removeSessionAttr("username");  // 或者
hCtx.setSessionAttr("username", null);
```
```java
// 获取Session属性
hCtx.getSessionAttr("username");
```

### Session 实现: [对象缓存](https://gitee.com/xnat/app#%E7%AE%80%E5%8D%95%E7%BC%93%E5%AD%98-cachesrv)
```java
// 1. 设置session委托函数.(操作内存/数据库/redis等的委托操作映射)
final HttpServer server = new HttpServer() {
    @Override
    protected Map<String, Object> sessionDelegate(HttpContext hCtx) { buildSessionDelegate(hCtx); }
}
```
```java
// 2. 创建session委托操作映射(即: 操作session只需要像操作Map一样, map中数据具体保存在哪由实现具体决定)
protected Map<String, Object> buildSessionDelegate(HttpContext hCtx) {
    Map<String, Object> sData;
    String sId = hCtx.request.getCookie("sessionId");
    Duration expire = Duration.ofMinutes(getInteger("session.expire", 30))

    String cKey
    if (!sId || ((cKey = "session_" + sId) && (sData = cacheSrv.get(cKey)) == null)) {
      sId = UUID.randomUUID().toString().replace("-", "")
      cKey = "session_" + sId
      log.info("New session '{}'", sId)
    }

    if (sData == null) {
        sData = new ConcurrentHashMap<>();
        cacheSrv.set(cKey, sData, expire);
    } else {
        cacheSrv.expire(cKey, expire);
    }
    sData.put("id", sId);
    hCtx.response.cookie("sessionId", sId, (int) expire.getSeconds(), null, "/", false, false);
    return sData;
}
```

### Session redis实现
```java
protected Map<String, Object> buildSessionDelegate(HttpContext hCtx) {
    Map<String, Object> sData;
    String sId = hCtx.request.getCookie("sessionId");
    Duration expire = Duration.ofMinutes(getInteger("session.expire", 30))
   
    String cKey
    if (sId == null || ((cKey = "session_" + sId) && redis.exists(cKey))) { // 创建新的Session
        sId = UUID.randomUUID().toString().replace("-", "")
        cKey = "session_" + sId
        log.info("New session '{}'", sId)
    }

    sData = new Map<String, Object>() {
        @Override
        int size() { redis.exec {jedis -> jedis.hkeys(cKey).size()} }
        @Override
        boolean isEmpty() { redis.exec {jedis -> jedis.hkeys(cKey).isEmpty()} }
        @Override
        boolean containsKey(Object key) { redis.hexists(cKey, key?.toString()) }
        @Override
        boolean containsValue(Object value) { redis.exec {jedis-> jedis.hvals(cKey).contains(value)} }
        @Override
        Object get(Object key) { redis.hget(cKey, key?.toString()) }
        @Override
        Object put(String key, Object value) { redis.hset(cKey, key?.toString(), value instanceof Collection ? value.join(',') : value?.toString(), expire.seconds.intValue()) }
        @Override
        Object remove(Object key) { redis.hdel(cKey, key?.toString()) }
        @Override
        void putAll(Map<? extends String, ?> m) { m?.each {e -> redis.hset(cKey, e.key, e.value.toString(), expire.seconds.intValue())} }
        @Override
        void clear() { redis.del(cKey) }
        @Override
        Set<String> keySet() { redis.exec {jedis-> jedis.hkeys(cKey)} }
        @Override
        Collection<Object> values() { redis.exec {jedis-> jedis.hvals(cKey)} }
        @Override
        Set<Map.Entry<String, Object>> entrySet() {
            redis.exec {jedis -> jedis.hgetAll(cKey).entrySet()}
        }
    }
    sData.put("id", sId);
    hCtx.response.cookie("sessionId", sId, (int) expire.getSeconds(), null, "/", false, false);
    return sData;
}
```

## websocket
```java
// 保存websocket 连接
final Set<WebSocket> wss = ConcurrentHashMap.newKeySet();
```
### 接收端
```java
// 定义一个接收websocket请求的Handler
@WS(path = "msg")
void wsMsg(WebSocket ws) {
    log.info("WS connect. {}", ws.getSession().getRemoteAddress());
    ws.listen(new WsListener() {
        @Override
        public void onClose(WebSocket wst) { wss.remove(wst); }

        @Override
        public void onText(String msg) { // websocket 文本通信
            log.info("test ws receive client msg: {}", msg);
        }

        @Override
        public void onBinary(byte[] msg) {
            // 接收byte数据  
        }
    });
    wss.add(ws);
}
```

### 广播数据
```java
wss.forEach(ws -> ws.send("hello"));
```

## 大文件分片上传
### 汇聚流: [ConvergeInputStream](https://gitee.com/xnat/http/blob/master/src/main/java/cn/xnatural/http/ConvergeInputStream.java)
> 顺序汇聚 多个流到一个流 直到 结束
* 流1(InputStream)    |
* 流2(InputStream)    | ==> 汇聚流 ==> 读取(阻塞)
* 流3(InputStream)    |

### 文件分片上传实现
* http头: x-pieceupload-id: 上传id(用于分辨是否是同一个文件上传) 每个分片上传都必传
* http头: x-pieceupload-end: true/false: 是否结束, 最后一个分片必传
* http头: x-pieceupload-filename: xxx.txt 文件原始名
* http头: x-pieceupload-progress: get: 获取进度
  ```json
    {
        "code": "00",
        "data": {
            "uploadId": "上传id",
            "fileId": "文件唯一标识名",
            "left": "后端还剩多少个分片未读取. int",
            "isEnd": "后端是否处理完所有分片流. boolean"
        }
    }
  ```
### Controller层 接收端
```java
@Path(path = "upload")
void upload(FileData file) {
    file.transferTo(new File(System.getProperty("java.io.tmpdir")));
    log.info("upload file: " + file);
}
```
### 前端例子
[test.html](https://gitee.com/xnat/http/raw/master/src/test/main/resources/static/test.html)

## 文件下载
```java
  @Path(path = "download/:fName")
  File download(String fName, HttpContext ctx) {
      File f = new File(System.getProperty("java.io.tmpdir"), (fName == null ? "tmp.xlsx" : fName));
      ctx.response.contentDisposition("attachment;filename=" + f.getName());
      return f;
  }
```
### 分块控制
```java
final HttpServer server = new HttpServer() {
    // 根据不同请求自定义分块传输大小
    protected int chunkedSize(HttpContext hCtx, int size, Class type) {
        int chunkedSize = -1;
        if (File.class.equals(type)) {
          if (size > 1024 * 1024) { // 大于1M
            chunkedSize = 1024 * 1024;
          } else if (size > 1024 * 80) { // 大于80K
            chunkedSize = 1024 * 20;
          }
        // 小文件不需要分段传送. 限于带宽(带宽必须大于分块的最小值, 否则会导致前端接收数据不全)
        } else if (byte[].class.equals(type)) {
            if (size > 1024 * 1024) { // 大于1M
                chunkedSize = 1024 * 1024;
            } else if (size > 1024 * 80) { // 大于80K
                chunkedSize = 1024 * 20;
            }
        } else {
            if (size > 1024 * 1024 * 10) throw new RuntimeException("body too large, > " + (1024 * 1024 * 10));
        }
        // TODO 其它类型暂时不分块
        return chunkedSize;
    }
};
```

## 统一错误处理
```java
final HttpServer server = new HttpServer() {
    /**
     * 错误处理
     * @param ex 异常 {@link Throwable}
     * @param hCtx {@link HttpContext}
     */
    protected void errHandle(Throwable ex, HttpContext hCtx) {
        if (ex instanceof AccessControlException) {
            log.error("Request Error '" + hCtx.request.getId() + "', url: " + hCtx.request.getRowUrl() + ", " + ex.getMessage());
            hCtx.render(ApiResp.of("403", ex.getMessage()));
            return;
        }
        log.error("Request Error '" + hCtx.request.getId() + "', url: " + hCtx.request.getRowUrl(), ex);
        hCtx.render(ApiResp.fail((ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : ""))));
    }
}
```

## Api 权限
```java
// 验证
hCtx.auth("login")
// 判断
boolean has = hCtx.hasAuth("login")
```
### 实现
```java
final HttpServer server = new HttpServer() {
  /**
   * 是否存在权限
   * @param permissions 权限名 验证用户是否有此权限
   * @return true: 存在
   */
  protected boolean hasAuth(HttpContext hCtx, String... permissions) {
      if (permissions == null || permissions.length < 1) return false;
      Set<String> pIds = hCtx.getAttr("permissions", Set.class); // 请求变量, 缓存解析好的权限标识
      if (pIds == null) {
          Object ps = hCtx.getSessionAttr("permissions"); // 权限标识用,分割
          if (ps == null) return false;
          pIds = Arrays.stream(ps.toString().split(",")).collect(Collectors.toSet());
          hCtx.setAttr("permissions", pIds);
      }
      for (String permission : permissions) {
          if (pIds.contains(permission)) return true;
      }
      return false;
    }
}
```

## 轻量级http
> 极简抽象HTTP(web)请求流程
1. HttpServer#doAccept 接收新连接 HttpAioSession
2. HttpAioSession#doRead 接收数据
3. HttpDecoder#decode 解析成 HttpRequest
4. HttpServer#receive 接收请求封装处理上下文 HttpContext
5. Chain#handle 找到匹配的Handler 并执行
6. HttpContext#render 渲染响应给调用方

# demo
http://xnatural.cn:9090/

test:test

# TODO
* 等待所有HttContext提交

# 参与贡献
xnatural@msn.cn
