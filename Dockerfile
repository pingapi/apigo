# 运行时镜像：JDK 25（Temurin JRE）
# 基础镜像用 debian(jammy) 而不是 alpine(musl)：平台支持 duckdb 等原生 JDBC 驱动，
# glibc 版 .so 在 musl 下需要 gcompat 才能加载，容易出现「本地能跑、容器报 UnsatisfiedLinkError」。
FROM eclipse-temurin:25-jre-jammy

# 版本号由构建参数注入，避免 LABEL 与 pom 的 revision 脱节
ARG APP_VERSION=6.4.0
LABEL version="${APP_VERSION}"

# 构建产物实际名为 ${project.artifactId}-${project.version}.jar（如 apigo-admin-6.4.0.jar），
# CI 需重命名后放入构建上下文，或通过 --build-arg APP_JAR=... 指定
ARG APP_JAR=apigo-admin.jar
COPY ${APP_JAR} /app.jar

ENV LANG="en_US.UTF-8"
EXPOSE 9377

# 数据源由环境变量注入（application.yml 已按 ${DB_URL:...} 占位）：
#   docker run -e DB_URL=... -e DB_USER=... -e DB_PWD=...
# 注意：
#   1) 这里不能写 ENV DB_URL=""，否则变量"存在但为空"，会顶掉 yml 里的默认值导致启动失败；
#   2) CMD 的 exec 形式不做 shell 展开，不能写成 --spring.datasource.url=${DB_URL}。

# JVM 参数说明：
#   UseCompactObjectHeaders  JDK 25 正式参数（JEP 519），大量 SQL 行对象场景降低堆占用
#   MaxRAMPercentage         容器内默认仅 25% 内存做堆，SQL 平台大结果集需要更高上限
#   ExitOnOutOfMemoryError   OOM 后快速退出，交给编排重启，避免半死不活
#   enable-native-access     JDK 24+ 收紧 JNI，duckdb 等原生库需要显式放行，否则满是告警
CMD ["java", \
     "-XX:+UseCompactObjectHeaders", \
     "-XX:MaxRAMPercentage=70", \
     "-XX:+ExitOnOutOfMemoryError", \
     "--enable-native-access=ALL-UNNAMED", \
     "-Duser.timezone=Asia/Shanghai", \
     "-jar", "/app.jar"]
