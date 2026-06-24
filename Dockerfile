FROM --platform=linux/amd64 public.ecr.aws/amazonlinux/amazonlinux:2023 AS backend-build

WORKDIR /app

# Instalar dependências de build
RUN yum install -y \
    java-11-amazon-corretto-devel \
    maven \
    tar \
    gzip && \
    yum clean all

# Copiar e buildar o backend
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests


FROM --platform=linux/amd64 public.ecr.aws/docker/library/node:22 AS frontend-build

WORKDIR /frontend

# Copiar dependências e instalar
COPY frontend/package*.json ./
RUN npm ci

# Copiar código e buildar
COPY frontend/ ./
RUN npm run build

FROM --platform=linux/amd64 public.ecr.aws/amazoncorretto/amazoncorretto:11

WORKDIR /app

# Instalar dependências runtime
RUN yum install -y \
    curl \
    shadow-utils \
    supervisor \
    nginx && \
    yum clean all && \
    useradd -r -u 1001 appuser

# Copiar JAR do backend
COPY --from=backend-build /app/target/ProcStudioSigner2.jar app.jar

# Copiar build do frontend
COPY --from=frontend-build /frontend/dist /usr/share/nginx/html

# Copiar configurações
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/entrypoint.sh /entrypoint.sh

# Criar diretórios de log
RUN mkdir -p /var/log/supervisor /var/log/nginx && \
    chown appuser:appuser /app/app.jar && \
    chmod +x /entrypoint.sh

# Expor portas
EXPOSE 80 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD curl -f http://localhost/ || exit 1

# Entrypoint
ENTRYPOINT ["/entrypoint.sh"]
