# Manual de cambios v6 — aplicación inicial del plan técnico

Fecha: 10 de octubre de 2026

## Cambios aplicados

### 1. Consultas SQL parametrizadas
Se reemplazó la interpolación de valores externos en las llamadas SQL de `ChatRoutes.kt` y `VentasRoutes.kt` por parámetros de Exposed (`?` + `args`). Esto afecta toma/devolución de conversaciones, lectura de mensajes, registro y estado de envío de mensajes, stream de eventos, ventas manuales, logística, reparto JSON, anulación y acciones de sincronización. Los textos con apóstrofes ya no se incorporan como fragmentos SQL.

### 2. Validación del estado al devolver una conversación
`ChatRoutes.kt` rechaza estados fuera de la lista permitida (`IDLE`, `NEGOTIATING`, `RESERVED`, `ESCALATED_HUMAN`).

### 3. Docker Compose
- El valor predeterminado de `APP_ENV` pasa a `local`, compatible con el parser documentado en el plan.
- La URL local de PostgreSQL declara `sslmode=disable` explícitamente para el contenedor de desarrollo. En producción, define `DATABASE_URL` con el modo TLS exigido por tu proveedor.
- Se retiró la publicación del puerto 5432 al host; la base sigue accesible desde el servicio de aplicación en la red privada de Compose.
- `POSTGRES_PASSWORD` ahora es obligatoria, sin contraseña por defecto.
- Se añadieron variables opcionales para integraciones y proxy; `CHANNEL_WRITE_ENABLED` continúa desactivado por defecto.

## Pendiente; no se afirma como terminado

Esta entrega es una primera aplicación del plan, no la finalización de sus siete fases. Sigue pendiente:
- Auditoría con identidad real del operador: las rutas siguen usando `dashboard` como usuario porque `requireDashboardAuth` no expone aquí una identidad individual.
- Convertir la edición de artículos en PATCH real; no se enlazó la edición en UI hasta resolverlo.
- CSP/htmx y extracción de scripts a `/static/`.
- Correcciones SQL de V002 para carrera del bot, alta de primera conversación y `requiere_atencion`.
- Interfaz funcional para Socios, Inventario y Reparaciones.
- Chat SSE completo, notificaciones, conexiones limitadas y ventana de 24 horas con política para ausencia de fecha.
- Sincronización saliente real con Shopify y acciones manuales visibles para Meta/Marketplace.
- Recuperación de trabajos atascados, reconciliación y fuente única de inventario.
- Pruebas con PostgreSQL real y compilación.

## Validación ejecutada
Se intentó `./gradlew check test`. No pudo comenzar porque el entorno no logró descargar Gradle desde `services.gradle.org` (error de red `UnknownHostException`). Por tanto, **no se confirma que el proyecto compile ni que las pruebas pasen**. Antes de desplegar, ejecuta `./gradlew check test` en un entorno con Gradle descargable y configura una instancia PostgreSQL 16 para las pruebas SQL.

## Siguiente orden recomendado
1. Ejecutar pruebas/compilación y resolver cualquier incompatibilidad de tipos.
2. Corregir `V002` y la auditoría del operador.
3. Arreglar CSP y carcasa del dashboard.
4. Implementar las tres pestañas editables.
5. Terminar chat y sincronización en ramas separadas.
