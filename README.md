### 介绍
轻量级 http 服务 基于 jdk aio

[文档](https://gitee.com/xnat/http/wikis)

### 安装教程
```
<dependency>
    <groupId>cn.xnatural.http</groupId>
    <artifactId>http</artifactId>
    <version>1.0.5</version>
</dependency>
```
### 打包编译(保留方法参数名, 非arg0/arg1...)
maven
```
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.8.0</version>
    <configuration>
        <source>1.8</source>
        <target>1.8</target>
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

### demo 
http://xnatural.cn:9090/

test:test

### [快速开始](https://gitee.com/xnat/http/wikis/%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B?sort_id=3198051)
#### 手动执行链
```
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
#### [控制层类](https://gitee.com/xnat/http/wikis/%E6%8E%A7%E5%88%B6%E5%B1%82%E7%B1%BB@Ctrl?sort_id=3198014)
```
HttpServer server = new HttpServer().ctrls( // 添加Controller层类
    MainCtrl.class, TestCtrl.class
).start();
```

### 参与贡献
xnatural@msn.cn
