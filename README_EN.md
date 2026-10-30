<div align="center">
  <h3>✨ ApiGo-Api ✨</h3>

  <p><i>Java Multi-database plug-in data source framework，SQL to API</i></p>
  <p>
    <a href="README.md">简体中文</a> | <b>English</b>
  </p>

  <p><sub>✦ ✦ ✦</sub></p>

</div>

# Introduction
**ApiGo** is an Multi-database plug-in data source framework, SQL2API. It deeply integrates Spring Boot and MyBatis to deliver dynamic data sources, dynamic SQL, functions and stored procedures.
It supports MCP integration with AI office platforms such as WorkBuddy, and ChatGPT,
and supports SQL/NoSQL data sources including MySQL, Oracle, PostgreSQL, SQL Server, TiDB, ES and Hive.
After writing SQL online with visual tooling, you can instantly generate an API and expose it as a service, with one-click online/offline switching. This reduces the SQL work for common APIs and lets developers focus on more complex business logic.
It supports MyBatis tag syntax (e.g. `if`), data masking, data transformation, national cryptographic encryption, collaborative development, API orchestration and more. It can be integrated with microservice gateways to support third-party APIs, authentication, rate limiting, caching, alerting/monitoring and unified logging — a one-stop API data service that
helps enterprises upgrade the value of their data assets and becomes an important driver of digital transformation.

![img.png](doc/home2.png)
## Community Edition Features
- **Workbench**: Visually write SQL and publish it as an API endpoint. Supports executing multiple SQL statements at once and MyBatis tag syntax for dynamic SQL and dynamic tags.
- **Data Source**: Data source list and data query, supporting a wide range of SQL/NoSQL databases.
- **Application Credentials**: Create and manage applications using AppKey authentication and signature authentication, with online authorization for API calls.
- **API List**: View in-development and published APIs, and manage online/offline status, rate limiting, etc.
- **API Docs**: Generate online API documentation with online testing.
- **Metrics**: Display statistics for API call metrics by scenario.
- **API Logs**: View the call logs and request details of published APIs.

## API Development Flow
![img.png](doc/dev_flow.png)
## Modules
~~~
cn.crabc    
├── apigo-api               // Business modules
│     └── apigo-admin        // Startup module
│     └── apigo-core        // Core module
│           └── static       // Frontend static pages [9377]
│     └── apigo-datasource   // Data source loading module
│     └── apigo-spi          // Plugin definition module
├── crabc-spring-boot-starter // Maven dependency for third-party project integration
├── db                       // SQL scripts
├──pom.xml                   // Dependencies
~~~

## Version Notes
The `dev` branch uses Spring Boot 4.1.1 and requires JDK 21 or above. \
The `jdk-8` branch uses Spring Boot 2.7.x and requires JDK 8.

## Community Edition Benchmark Report

> Tool: JMeter | Environment: 4C8G server, JDK 21

| Parameter | Value      |
|:--|:----------|
| API under test | 1          |
| Concurrent threads | 100       |
| Duration | 180 s      |

| Key metric | Value             |
|:--|:---------------|
| **QPS (throughput)** | **287.8 req/s** |
| Total requests | 51,959         |
| Successes | 51,959         |
| Failures | 0              |
| **Failure rate** | **0.00%**      |
| Average response time | 286 ms         |
| Max response time | 1,118 ms       |
| P50 | 291 ms         |
| P90 | 457 ms         |
| P95 | 602 ms         |
| P99 | 785 ms         |


## Enterprise Edition
https://www.apigo.cn

## Enterprise Edition Architecture

## Run & Startup
```
1. First run the db/dml.sql script to create the tables and initialize data. MySQL and PostgreSQL are supported; MySQL 8.0+ and PostgreSQL 14+ are recommended.
2. Run crabc-api/ AdminApplication.java from your IDE.
```
Access URL: http://127.0.0.1:9377 \
Username / Password: admin/admin123

## Official Website
Website: https://www.apigo.cn

User Guide: http://docs.apigo.cn

GitHub: https://github.com/pingapi/apigo

## Commercial License
Software copyright has been registered; the open-source edition is for personal learning use only. \
The enterprise edition is more powerful — for commercial use please join the group and contact us.

## Maven Integration of apigo

```
<dependency>
    <groupId>cn.crabc</groupId>
    <artifactId>apigo-spring-boot-starter</artifactId>
    <version>6.3.1</version>
</dependency>
```
Add the following annotation to your application startup class:
```
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
```
Integration demo: <a href="https://gitee.com/linebyte/crabc-spring-boot-starter-demo" style="text-decoration: none;" target="_blank">crabc-spring-boot-starter-demo</a>

## Docker Startup
First run the db/dml.sql script to create the tables and initialize data. MySQL 8.0+ is recommended.

X86/AMD architecture
```
docker run -p 9377:9377 -e JWT_SECRET="$JWT_SECRET" -e DB_URL="jdbc:mysql://localhost:3306/apigo" -e DB_USER="root" -e DB_PWD="root" -d --name apigo crabc/apigo:latest
```
ARM architecture
```
docker run -p 9377:9377 -e JWT_SECRET="$JWT_SECRET" -e DB_URL="jdbc:mysql://localhost:3306/apigo" -e DB_USER="root" -e DB_PWD="root" -d --name apigo crabc/crabc-admin:latest
```
Access URL: http://127.0.0.1:9377
Username / Password: admin/admin123

## Run on Windows Desktop
First run the db/dml.sql script to create the tables and initialize data. MySQL 8.0+ is recommended.
```
java -jar crabc-api.jar JWT_SECRET="$JWT_SECRET" --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/crabc --spring.datasource.username=root --spring.datasource.password=root 
```

## AI Office Tool Integration (MCP)

ApiGo ships with a built-in MCP Server (Streamable HTTP). AI office tools/Agents only need **1 URL + 1 Token** to complete the entire flow of "create data source → write SQL → create API → publish → authorize → check logs" through conversation (27 tools in total).

### Connection URL

| Scenario | MCP Server URL |
|:--|:--|
| Local / IDE debugging | `http://127.0.0.1:9377/mcp` |
| Intranet / Docker deployment | `http://<server-ip>:9377/mcp` |
| Public access | `https://<domain>/mcp` (HTTPS reverse proxy required) |


### mcpServers Configuration

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


## Screenshots
### API Development
![img.png](doc/dev_en.png)
#### API Properties
![img.png](doc/detail.png)
#### Response Parameters
![img.png](doc/param.png)
#### Online Preview
![img_2.png](doc/img_test.png)
#### API List
![img.png](doc/apis_en.png)
#### API Rate Limiting
![img.png](doc/limit.png)
#### Metrics
![img.png](doc/monitor.png)
#### Log Management
![img.png](doc/logs.png)
#### Data Source List
![img.png](doc/db.png)
#### Data Query
![img.png](doc/data.png)
#### Data Source Types
![img.png](doc/type.png)

## Community Group
**Please click [Star] before joining, thank you.**

## E-mail
iamyuqf@outlook.com
