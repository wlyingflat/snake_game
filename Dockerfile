# 使用 Maven 官方镜像作为构建阶段
FROM maven:3.8-openjdk-17 AS builder
WORKDIR /app
COPY pom.xml .
# 下载依赖（利用 Docker 缓存）
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段使用轻量 JRE 镜像
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 创建非 root 用户运行
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser
USER appuser

# 复制构建产物
COPY --from=builder --chown=appuser:appgroup /app/target/*-jar-with-dependencies.jar app.jar

# 暴露默认端口（可通过环境变量覆盖）
EXPOSE 8080 8081 8082

# 启动命令，通过环境变量指定运行模式
ENTRYPOINT ["sh", "-c", "java -jar app.jar $JAVA_OPTS"]
