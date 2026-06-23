# Build stage - Usando Amazon Corretto 11 com Maven
FROM public.ecr.aws/amazonlinux/amazonlinux:2023 AS build
WORKDIR /app

# Instalar Java 11 e Maven
RUN yum install -y java-11-amazon-corretto-devel maven tar gzip && \
    yum clean all

# Copiar e construir o projeto backend
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage - Usando Amazon Corretto 11
FROM public.ecr.aws/amazoncorretto/amazoncorretto:11
WORKDIR /app

# Instalar curl e shadow-utils (contém useradd) para healthcheck e criar usuário
RUN yum install -y curl shadow-utils && \
    yum clean all && \
    useradd -r -u 1001 appuser

# Copiar o JAR buildado
COPY --from=build /app/target/ProcStudioSigner2.jar app.jar

# Definir propriedade
RUN chown appuser:appuser app.jar

USER appuser

# Expor porta da API
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8080/api/v1/health || exit 1

# Executar em modo API por padrão
ENTRYPOINT ["java", "-jar", "app.jar", "--api"]
