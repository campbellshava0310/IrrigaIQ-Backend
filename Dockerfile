# syntax=docker/dockerfile:1.7

# ---- Build: resolve dependencies in their own layer, then package and explode the jar ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/irrigaiq-backend-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime: JRE only, non-root, dependency layers first for cache-friendly rebuilds ----
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system irrigaiq && useradd --system --gid irrigaiq --no-create-home irrigaiq
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER irrigaiq
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
