# ============================================================
# 微信云托管部署用 Dockerfile（多阶段构建）
# 位置必须在本仓库根目录；服务监听端口 8080（与云托管「监听端口」一致）
# 数据库/微信凭据等一律走云托管环境变量注入，不写进镜像
# ============================================================

# ---------- 构建阶段：Maven + JDK17 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先只拷 pom 下载依赖，命中 Docker 层缓存，改代码不用重新拉依赖
COPY backend/pom.xml .
# 构建期走国内 Maven 镜像（中央仓库对云构建机出口 IP 会 429 限流）
COPY docker/maven-settings.xml /root/.m2/settings.xml
RUN mvn -B -s /root/.m2/settings.xml dependency:go-offline

COPY backend/src ./src
RUN mvn -B -s /root/.m2/settings.xml clean package -DskipTests

# ---------- 运行阶段：仅 JRE，镜像更小 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai

# 容器内存感知的 JVM 参数，适配云托管按量内存
COPY --from=build /app/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
