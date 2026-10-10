# Manual de Cambios v5 — Fase F0 (Red de Seguridad y Base de Build)

Este documento registra los cambios realizados durante la **Fase F0 (Red de Seguridad y Base de Build)** del Plan de Implementación Arquitectónica Modular expuesto en `correciones/Plan_de_Implementacion_Arquitectura_Modular.pdf`.

---

## 1. Resumen de Cambios

| Elemento | Estado | Detalle |
|---|---|---|
| Catálogo de Versiones (`gradle/libs.versions.toml`) | ✅ Creado | Centralización de versiones para Kotlin, Ktor, Exposed, Flyway, Logback, POI, Hikari, PostgreSQL, H2 y JUnit. |
| Alineación de Toolchain JDK | ✅ Actualizado | Configuración de `jvmToolchain(21)` en `build.gradle.kts` raíz para alinear con la imagen Docker `eclipse-temurin:21-jdk` (Resolución de H9). |
| Aislamiento de H2 | ✅ Actualizado | Traslado de la dependencia de H2 de `implementation` a `testImplementation` en `infra/build.gradle.kts` (Resolución de H10). |
| Logging Estructurado con SLF4J | ✅ Completado | Reemplazo de 36 llamadas a `System.err.println` / `println` / `System.out.println` en código de producción por loggers SLF4J (`LoggerFactory`) (Resolución de H8). |
| Integración Continua (GitHub Actions) | ✅ Creado | Archivo `.github/workflows/ci.yml` configurado para validación automatizada de compilación y pruebas unitarias con JDK 21. |
| Verificación del Build | ✅ Verificado | Ejecución completa de `./gradlew check test` y `./gradlew assemble` sin errores. |

---

## 2. Archivos Modificados / Creados

### Nuevos Archivos
- `gradle/libs.versions.toml`
- `.github/workflows/ci.yml`
- `MANUAL_DE_CAMBIOS_v5.md`

### Archivos Modificados
- `build.gradle.kts`
- `infra/build.gradle.kts`
- `app/build.gradle.kts`
- `commerce/build.gradle.kts`
- `messaging/build.gradle.kts`
- `adapters-channels/build.gradle.kts`
- `adapters-meta/build.gradle.kts`
- `adapters-shopify/build.gradle.kts`
- `adapters-channels/src/main/kotlin/com/automata/adapters/channels/MetaWebhookReceiver.kt`
- `app/src/main/kotlin/com/automata/app/pipeline/InboundMessagePipeline.kt`
- `infra/src/main/kotlin/com/automata/infra/config/AppConfig.kt`
- `infra/src/main/kotlin/com/automata/infra/db/DatabaseFactory.kt`
- `infra/src/main/kotlin/com/automata/infra/db/ExposedInventarioRepository.kt`
- `infra/src/main/kotlin/com/automata/infra/excel/ExcelV2Parser.kt`
- `app/src/main/kotlin/com/automata/app/Application.kt`

---

## 3. Justificación Técnica de los Cambios

### 3.1. Version Catalog (`libs.versions.toml`) y Toolchain JDK 21 (H9, H10)
Se estableció Gradle Version Catalog para eliminar versiones hardcodeadas y duplicadas a través de los 8 módulos del proyecto. Se actualizó el `jvmToolchain` de JDK 17 a JDK 21 LTS en el script de build raíz para garantizar paridad exacta entre el entorno de desarrollo local y la compilación en contenedores Docker (`Dockerfile`). Además, la dependencia del driver H2 en el módulo `:infra` se movió estrictamente a `testImplementation` para evitar contaminar el classpath de producción.

### 3.2. Sustitución de `println` y `System.err.println` por SLF4J / Logback (H8)
Se eliminaron todas las impresiones por consola directa en código de producción (`src/main`), reemplazándolas por instancias de `org.slf4j.Logger`. Esto asegura:
- Formato de logs estructurado y configurable mediante Logback (`logback-classic`).
- Adecuada captura y trazabilidad de stacktraces en bloques `catch`.
- Integración limpia con stdout/stderr en contenedores Docker para colectores de logs.

### 3.3. Integración Continua (`ci.yml`)
Se implementó la canalización de CI utilizando GitHub Actions con las siguientes etapas:
1. `checkout` del repositorio.
2. Configuración de JDK 21 Temurin.
3. Caché de dependencias de Gradle.
4. Ejecución de `./gradlew check assemble` y `./gradlew test`.
