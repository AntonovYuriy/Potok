# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Cache the Gradle distribution and dependencies between builds
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 potok
USER potok

COPY --from=build /workspace/build/libs/*.jar app.jar

# Tuned for small instances: size the heap from the container limit
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
