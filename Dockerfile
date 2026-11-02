# ===== 多阶段构建 =====

# 阶段1: 构建前端
FROM node:22-alpine AS frontend-build

WORKDIR /app/vue-code

# 设置 npm 镜像源
RUN npm config set registry https://registry.npmmirror.com

# 先复制依赖文件，利用缓存
COPY vue-code/package.json vue-code/package-lock.json ./
RUN npm ci

# 复制前端源码并构建
COPY vue-code/ ./
RUN npm run build:spring

# 阶段2: 构建后端 JAR
FROM eclipse-temurin:21-jdk-alpine AS backend-build

WORKDIR /app

# 配置阿里云 Maven 镜像
RUN mkdir -p /root/.m2 && echo '<?xml version="1.0" encoding="UTF-8"?><settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd"><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><name>Aliyun Maven</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

# 先复制 Maven 配置和 pom.xml，利用缓存
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw

# 复制前端构建产物到 static 目录
COPY --from=frontend-build /app/vue-code/../src/main/resources/static src/main/resources/static/

# 复制后端源码
COPY src/ src/

# 构建 JAR（跳过测试）
RUN ./mvnw clean package -DskipTests

# 阶段3: 提供Java 21运行时
FROM eclipse-temurin:21-jre-jammy AS java-runtime

# 阶段4: 运行时镜像
# 必须与 pom.xml 中的 Playwright Java 版本严格匹配，镜像内含对应 Chromium 及系统依赖。
# 从上一阶段覆盖 Java 运行时，确保应用固定使用 Java 21。
FROM mcr.microsoft.com/playwright/java:v1.64.0-noble AS runtime

LABEL maintainer="IAMLZY"
LABEL description="XianYu Seller Console - local seller operations console"

WORKDIR /app

# 覆盖官方镜像中的JDK 17；Playwright浏览器固定安装在/ms-playwright。
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk

# 容器内必须由 Docker 重启策略拉起（restart-mode=self 会让容器主进程退出）
ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:${PATH}" \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    JAVA_OPTS="-Xms256m -Xmx512m" \
    SERVER_PORT=12400 \
    XIANYU_ASSISTANT_BIND_ADDRESS=0.0.0.0 \
    XIANYU_ASSISTANT_PRINT_RAW_MESSAGE=false \
    XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=exit

# 在镜像构建期验证Java主版本和Chromium目录，防止基础镜像漂移后静默缺失依赖。
RUN mkdir -p /app/dbdata /app/logs \
    && java -XshowSettings:properties -version 2>&1 | grep -q "java.specification.version = 21" \
    && find "${PLAYWRIGHT_BROWSERS_PATH}" -mindepth 1 -maxdepth 1 -type d -name 'chromium-*' | grep -q . \
    && chown -R pwuser:pwuser /app

# 从构建阶段复制 JAR
COPY --from=backend-build --chown=pwuser:pwuser /app/target/XianYuSellerConsole-*.jar app.jar

# 暴露端口
EXPOSE 12400

USER pwuser

# 启动命令
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT} -jar app.jar"]

