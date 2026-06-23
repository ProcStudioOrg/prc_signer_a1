# Build stage
FROM maven:3.8-openjdk-11 AS build
#FROM public.ecr.aws/z5y1f1y8/maven:3.6-amazoncorretto-11 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:11-jre
#FROM public.ecr.aws/docker/library/eclipse-temurin:11-jre
WORKDIR /app

# Create non-root user for security
RUN useradd -r -u 1001 appuser

# Copy the built JAR
COPY --from=build /app/target/ProcStudioSigner2.jar app.jar

# Set ownership
RUN chown appuser:appuser app.jar

USER appuser

# Expose API port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8080/api/v1/health || exit 1

# Run in API mode by default
ENTRYPOINT ["java", "-jar", "app.jar", "--api"]
