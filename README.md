# 介绍
轻量级 http 服务 基于 jdk aio

[文档](https://gitee.com/xnat/http/wikis)

# 安装教程
```xml
<dependency>
    <groupId>cn.xnatural.http</groupId>
    <artifactId>http</artifactId>
    <version>1.0.7</version>
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

# demo 
http://xnatural.cn:9090/

test:test

# [快速开始](https://gitee.com/xnat/http/wikis/%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B?sort_id=3198051)
## 手动执行链
```java
HttpServer server = new HttpServer().buildChain((chain -> {
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
## [控制层类](https://gitee.com/xnat/http/wikis/%E6%8E%A7%E5%88%B6%E5%B1%82%E7%B1%BB@Ctrl?sort_id=3198014)
```java
HttpServer server = new HttpServer().ctrls( // 添加Controller层类
    MainCtrl.class, TestCtrl.class
).start();
```

## 大文件分片上传
> 文件分片上传实现原理
* http头: x-pieceupload-id: 上传id(用于分辨是否是同一个文件上传) 每个分片上传都必传
* http头: x-pieceupload-end: true/false: 是否结束, 最后一个分片必传
* http头: x-pieceupload-filename: xxx.txt 文件原始名
* http头: x-pieceupload-progress: get: 获取进度
  ```json
    {
        "code": "00",
        "data": {
            "uploadId": "上传id",
            "left": "后端还剩多少个分片未读取. int",
            "isEnd": "后端是否处理完所有. boolean"
        }
    }
  ```

>> 步骤1: 第一个分片请求: x-pieceupload-id, x-pieceupload-filename(可选)


# 参与贡献
xnatural@msn.cn
