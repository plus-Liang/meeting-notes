# 多阶段构建：镜像里只留 JRE，不留 Maven 与大体积构建缓存
# TODO(编码当天核对): 基础镜像版本

# 第三方镜像走 REGISTRY_PREFIX（国内加速源），由 compose build args 传入，默认直连
ARG REGISTRY_PREFIX=""
FROM ${REGISTRY_PREFIX}library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM ${REGISTRY_PREFIX}library/eclipse-temurin:21-jre
WORKDIR /app
# 媒体处理（抽音轨）在本镜像内的 worker 侧完成；不依赖宿主机 ffmpeg。
# 若 worker 单独拆容器，则把 ffmpeg 装到 worker 镜像里。
# 国内网络：apt 换源（archive.ubuntu.com 走宿主代理会间歇 502）。注意源在
# deb822 格式的 /etc/apt/sources.list.d/*.sources 里，必须 grep 定位真实文件再 sed
ARG APT_MIRROR=mirrors.tuna.tsinghua.edu.cn
RUN if [ -n "$APT_MIRROR" ]; then \
      grep -rlE "archive\.ubuntu\.com|security\.ubuntu\.com" /etc/apt 2>/dev/null \
        | xargs -r sed -i "s|archive.ubuntu.com|$APT_MIRROR|g; s|security.ubuntu.com|$APT_MIRROR|g" ; \
    fi \
 && apt-get update -o Acquire::Retries=5 \
 && apt-get install -y --no-install-recommends -o Acquire::Retries=5 ffmpeg curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
