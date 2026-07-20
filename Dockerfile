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
# VERSION (raiz do repo) vai para /VERSION — o vite.config lê ../VERSION
COPY VERSION /VERSION
COPY frontend/ ./
RUN npm run build

FROM --platform=linux/amd64 public.ecr.aws/amazonlinux/amazonlinux:2023

WORKDIR /app

# Instalar dependências runtime
RUN yum install -y \
    java-11-amazon-corretto \
    shadow-utils \
    python3 \
    python3-pip \
    nginx && \
    yum clean all

# Instalar supervisor via pip
RUN pip3 install supervisor

# Criar usuário não-root
RUN useradd -r -u 1001 appuser

# Copiar JAR do backend
COPY --from=backend-build /app/target/ProcStudioSigner2.jar app.jar

# Versão da aplicação (usada pela notificação de deploy no entrypoint)
COPY VERSION /app/VERSION

# Copiar build do frontend
COPY --from=frontend-build /frontend/dist /usr/share/nginx/html

# Copiar configurações
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/entrypoint.sh /entrypoint.sh

# Criar diretórios de log e de dados (SQLite de usage tracking)
RUN mkdir -p /var/log/supervisor /var/log/nginx /app/data && \
    chown appuser:appuser /app/app.jar /app/data && \
    chmod +x /entrypoint.sh

# Usage tracking: banco SQLite local. Monte /app/data como volume no host/ECS
# para persistir o histórico entre deploys.
ENV USAGE_DB_PATH=/app/data/usage.sqlite3

# Expor portas
EXPOSE 80 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD curl -f http://localhost/ || exit 1

# Entrypoint
ENTRYPOINT ["/entrypoint.sh"]
