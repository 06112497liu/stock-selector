# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# 多阶段构建:build 阶段用 Maven 官方镜像编译出可执行 fat jar,
# runtime 阶段用精简 JRE 只装运行所需,缩小最终镜像体积。
# ---------------------------------------------------------------------------

# ===== build 阶段 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom.xml 并预拉依赖,利用 Docker 层缓存:
# 只要 pom.xml 不变,后续改源码不会重新下载依赖。
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# 再拷源码并打包。镜像构建阶段跳过测试(-DskipTests)加速:
# 126 个测试已在本机 / CI 跑过,无需在每次镜像构建时重复执行。
COPY src ./src
RUN mvn -q -B -DskipTests package

# ===== runtime 阶段 =====
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 数据卷挂载点:SQLite + 行情缓存写到这里(由 MARKET_CACHE_DIR 指向 /data)。
ENV MARKET_CACHE_DIR=/data
VOLUME ["/data"]

# 非 root 用户运行更安全;给它 /data 与 /app 的归属。
RUN useradd -r -u 10001 -m appuser \
    && mkdir -p /data \
    && chown -R appuser:appuser /data /app

# 从 build 阶段拷出唯一的 fat jar(避开 *.jar.original)。
COPY --from=build /build/target/*-SNAPSHOT.jar /app/app.jar
RUN chown appuser:appuser /app/app.jar

USER appuser
EXPOSE 8080

# exec 形式:java 成为 PID 1,正确接收信号;
# JAVA_TOOL_OPTIONS 会被 JVM 自动读取,可在运行时透传代理等系统属性。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
