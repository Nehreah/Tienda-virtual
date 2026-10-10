# Fase 6.3: Dockerfile multi-etapa para Tienda Virtual Multicanal
# Etapa 1: compilación con Gradle y JDK 21
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copiar ficheros Gradle primero (cache de dependencias)
COPY gradlew ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Copiar módulos
COPY shared-kernel/ shared-kernel/
COPY commerce/ commerce/
COPY messaging/ messaging/
COPY infra/ infra/
COPY adapters-channels/ adapters-channels/
COPY adapters-shopify/ adapters-shopify/
COPY adapters-meta/ adapters-meta/
COPY app/ app/

RUN chmod +x gradlew && ./gradlew :app:installDist --no-daemon -q

# Etapa 2: imagen de ejecución — solo JRE (sin compilador ni fuentes)
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Tienda Virtual Multicanal"
LABEL org.opencontainers.image.description="Bot de ventas multicanal semiautogestionado"

WORKDIR /app

# Usuario sin privilegios
RUN useradd -r -s /bin/false appuser && chown appuser /app
USER appuser

# Copiar distribución compilada
COPY --from=builder /build/app/build/install/app/ ./

# Puerto de escucha (configurable via PORT env var)
EXPOSE 8080

# Variables de entorno requeridas en producción (ver local.properties.example):
# DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD
# DASHBOARD_PASSWORD (>= 12 chars)
# APP_ENV=production
# META_APP_SECRET, META_WEBHOOK_VERIFY_TOKEN
# SHOPIFY_WEBHOOK_SECRET (si se usa Shopify)
# GEMINI_API_KEY (si se quiere LLM real)

ENTRYPOINT ["bin/app"]
