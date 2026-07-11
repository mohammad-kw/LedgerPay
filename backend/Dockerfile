# ============================================================================
# LedgerPay backend - production container image (for Render)
# ============================================================================
# Two-stage build:
#   Stage 1 ("build") compiles the app into a runnable fat JAR using Maven.
#   Stage 2 ("run")   copies ONLY that JAR into a small JRE image, so the
#                     final image doesn't carry Maven or the source tree.
# ----------------------------------------------------------------------------

# ---- Stage 1: build --------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the POM first and pre-download dependencies. This layer is cached and
# only re-runs when pom.xml changes, so most rebuilds are fast.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy the source and build the JAR. Tests already run in CI, so we skip them
# here to keep deploy builds fast.
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Stage 2: run ----------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built fat JAR from the build stage under a stable name.
COPY --from=build /app/target/*.jar app.jar

# Render injects the port to listen on via the PORT env var; our
# application.properties already reads ${PORT:8080}, so nothing else is needed.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
