# 多阶段构建：镜像里只留 JRE，不留 Maven 与大体积构建缓存
# TODO(编码当天核对): 基础镜像版本

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
# 媒体处理（抽音轨）在本镜像内的 worker 侧完成；不依赖宿主机 ffmpeg。
# 若 worker 单独拆容器，则把 ffmpeg 装到 worker 镜像里。
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
