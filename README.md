

# 介绍
**ApiGo** 是接口开发平台，企业项目脚手架，sql2Api，数据预览等。深度整合SpringBoot和Mybatis实现动态数据源和动态SQL、函数和存储过程。
支持 WorkBuddy、千问办公、豆包等 AI 办公平台 MCP 接入，
支持接入（mysql、oracle、postgresql、sqlserver、达梦、TiDB、es和hive）等SQL或/NoSQL数据源，
在线可视化编写SQL后即可快速生成接口对外提供服务，接口一键上下线。减少通用接口的SQL编写，让开发人员专注更复杂的业务逻辑实现。
支持Mybatis中if等标签语法、数据脱敏、数据转换、国密加密、协同开发、接口编排等功能，可集成微服务网关支持接入第三方接口、权限认证、限流、缓存、告警监控和统一日志等一站式API数据服务,
助力于企业数据资产价值升级，成为企业数字化转型的重要推动力。

![img.png](doc/home.png)
## 社区版功能
- **工作台**：可视化编写SQL快速的对外发布成API接口,支持多SQL同时执行、Mybatis标签语法，实现动态SQL和动态标签
- **数据源**：数据源列表和数据查询，支持多种SQL/NoSQL数据库添加。
- **应用凭证**：AppKey认证、签名认证的应用创建管理以及对API调用在线授权。
- **接口列表**：查看开发中和已发布的API接口，可进行上下线管理，限流控制等
- **接口文档**：生成在线接口文档，支持在线测试。
- **指标统计**：展示场景的接口调用指标统计
- **接口日志**：查看发布的接口被调用日志列表和请求详情。

## 接口开发流程
![img.png](doc/dev_flow.png)
## 模块
~~~
cn.crabc    
├── crabc-boot               // 业务模块
│     └── crabc-admin        // 启动模块
│     └── crabc-core        // 基础模块
│           └── static       // 前端静态页面 [9377]
│     └── crabc-datasource   // 数据源加载模块
│     └── crabc-spi          // 插件定义模块
├── crabc-spring-boot-starter // 第三方项目maven集成依赖包
├── db                       // SQL脚本
├──pom.xml                   // 依赖
~~~

## 版本说明
dev 分支框架SpringBoot是4.1.0，jdk-21及以上版本 \
jdk-8 分支框架SpringBoot是2.7.x，jdk-8 

## 社区版压测报告

> 压测工具：JMeter ｜ 部署环境：4c8g 服务器，jdk21

| 压测参数 | 值         |
|:--|:----------|
| 压测接口 | 一个        |
| 并发线程数 | 100       |
| 执行时长 | 180 秒     |

| 核心指标 | 数值             |
|:--|:---------------|
| **QPS（吞吐量）** | **287.8 请求/秒** |
| 总请求数 | 51,959         |
| 成功数 | 51,959         |
| 失败数 | 0              |
| **失败率** | **0.00%**      |
| 平均响应时间 | 286 ms         |
| 最大响应时间 | 1,118 ms       |
| P50 | 291 ms         |
| P90 | 457 ms         |
| P95 | 602 ms         |
| P99 | 785 ms         |


## 企业版
https://apigo.cn

## 企业版架构图

## 运行启动
```
1、先执行db/dml.sql脚本，创建库表和初始化数据，支持mysql和postgresql，建议mysql 8.0+版本和postgresql 14+版本
2、在编辑工具中运行启动 crabc-api/ AdminApplication.java
```
访问地址：http://127.0.0.1:9377 \
账号密码：admin/admin123

## 官网
官网：https://apigo.cn 

使用指南：http://docs.apigo.cn  

github：https://github.com/pingapi/apigo

## 商用授权
已申请软件著作，开源版仅供个人学习使用 \
企业版功能更强大，商用请加群联系\

## 源码地址
github: https://github.com/pingapi/apigo \
gitee: https://gitee.com/pingapi/crabc-api 

## Maven集成apigo

```
<dependency>
    <groupId>cn.crabc</groupId>
    <artifactId>crabc-spring-boot-starter</artifactId>
    <version>6.0.0</version>
</dependency>
```
在程序启动类中添加下面注解
```
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
```
集成Demo参考: <a href="https://gitee.com/linebyte/crabc-spring-boot-starter-demo" style="text-decoration: none;" target="_blank">crabc-spring-boot-starter-demo</a>

## Docker启动
先执行db/dml.sql脚本，创建库表和初始化数据，建议mysql 8.0+版本

X86/AMD架构
```
docker run -p 9377:9377 -e DB_URL=jdbc:mysql://localhost:3306/apigo -e DB_USER=root -e DB_PWD=root -d --name apigo crabc/apigo:latest
```
ARM架构
```
docker run -p 9377:9377 -e DB_URL=jdbc:mysql://localhost:3306/apigo -e DB_USER=root -e DB_PWD=root -d --name apigo crabc/crabc-admin:latest
```
访问地址：http://127.0.0.1:9377
账号密码：admin/admin123

## Windows桌面运行
先执行db/dml.sql脚本，创建库表和初始化数据，建议mysql 8.0+版本
```
java -jar crabc-api.jar --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/crabc --spring.datasource.username=root --spring.datasource.password=root 
```

## AI 办公工具接入（MCP）

ApiGo 内置 MCP Server（Streamable HTTP），AI 办公工具/Agent 只需配置 **1 个 URL + 1 个 Token**，即可通过对话完成「建数据源 → 写 SQL → 建接口 → 发布 → 授权 → 查日志」全流程（共 27 个工具）。

### 连接地址

| 场景 | MCP Server URL |
|:--|:--|
| 本机 / IDE 联调 | `http://127.0.0.1:9377/mcp` |
| 内网 / Docker 部署 | `http://<服务器IP>:9377/mcp` |
| 公网接入 | `https://<域名>/mcp`（需 HTTPS 反向代理） |


### mcpServers 配置

```json
{
  "mcpServers": {
    "apigo": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:9377/mcp",
      "headers": {
        "Authorization": "Bearer sk-xxxxxxxx"
      }
    }
  }
}
```


## 效果截图
### 接口开发
![img.png](doc/dev.png)
#### 接口属性
![img.png](doc/detail.png)
#### 返回参数
![img.png](doc/param.png)
#### 在线预览
![img_2.png](doc/img_test.png)
#### 接口列表
![img.png](doc/apis.png)
#### 接口限流
![img.png](doc/limit.png)
#### 指标统计
![img.png](doc/monitor.png)
#### 日志管理
![img.png](doc/logs.png)
#### 数据源列表
![img.png](doc/db.png)
#### 数据查询
![img.png](doc/data.png)
#### 数据源类型
![img.png](doc/type.png)

## 交流群
**进群前请先点【Star】谢谢**

QQ群(请先Star)：748993036

商用咨询加微信 (请备注apigo)

![img.png](doc/img.png)

## 产品使用登记
以下为部分接入apigo的用户 ，<a href="https://gitee.com/linebyte/crabc/issues/IA98CA" target="_blank"> 点击进行登记</a>

| 产品使用公司 |
|:-------|
| 真情巴士   |
| 阳光智维   |
| 一步数据   |
| 像素云图   |
| 前景科技   |
| 方寸图信息  |
| 鹭栖技术   |
| 循脉医疗   |
| 云上贵州   |
| 蓝典科技   |
| 某市供电局  |
| 启瑞药业   |
| 今日资本   |
| 智汇大成   |
| 中科世通   |
