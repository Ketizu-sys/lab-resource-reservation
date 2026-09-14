# 第一阶段使用完整 JDK 和 Maven Wrapper 编译应用。
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace/app

# 先复制构建描述和源码，再执行一次跳过测试的干净构建。
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

RUN ./mvnw clean install -DskipTests
# 解开 Spring Boot Fat Jar，便于下一阶段按依赖层复制并启动。
RUN mkdir -p target/dependency && (cd target/dependency; jar -xf ../*.jar)

# 第二阶段只保留体积更小的 JRE 和实际运行文件。
FROM eclipse-temurin:21-jre-alpine
VOLUME /tmp
# Compose 使用 curl 调用独立的 Actuator 端口执行容器健康检查。
RUN apk add --no-cache curl
ARG DEPENDENCY=/workspace/app/target/dependency
# 分别复制第三方依赖、元信息和项目自身编译结果。
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app
# 使用 classpath 直接运行 Spring Boot 主类。
ENTRYPOINT ["java","-cp","app:app/lib/*","com.azki.reservation.ReservationApplication"]
