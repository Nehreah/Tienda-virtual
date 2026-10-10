# Manual de cambios v8 — Fase 3 (primera entrega)

## Objetivo
Comenzar a cablear los módulos de gestión de Socios e Inventario/Reparaciones desde el dashboard.

## Cambios
- Se añadió `PENDIENTE_CAMBIAR_HTMX_OFICIAL.md` con pasos concretos para sustituir `htmx-lite.js` por la distribución oficial local de htmx.
- Se añadió `ReparacionesRoutes.kt`: `GET /ui/reparaciones` lista artículos y `POST /api/reparaciones/{key}` valida el precio y guarda los detalles. Las operaciones requieren autenticación del dashboard y una base de datos conectada.
- Se cableó `ExposedReparacionRepository` en `Application.kt` cuando PostgreSQL está conectado.
- Se añadió `ReparacionesRenderer.kt`, con escape de texto HTML y formulario de precio/detalles. El precio mínimo y costo total siguen siendo responsabilidad de los triggers SQL.
- Se añadió `PUT /api/socios/{id}` para renombrar socios con validaciones del servicio de dominio.
- Se añadieron las vistas de navegación Socios y Reparaciones al dashboard. Los módulos se cargan bajo demanda mediante `fetch`.
- Se añadieron estilos propios para la tabla de reparaciones; no se introdujo dependencia de Tailwind CDN.

## Limitaciones conocidas
- Esta es una primera entrega de la Fase 3, no la fase completa: no incluye todavía matriz de reparto por artículo, importación/exportación específica de reparaciones en Excel, paginación ni el flujo completo de edición de inventario.
- El formulario de reparación realiza un POST tradicional y redirige al dashboard raíz tras guardar.
- El módulo de reparaciones requiere PostgreSQL; no hay repositorio de reparaciones en memoria.
- El nombre de usuario de auditoría se mantiene como `dashboard`; la identidad individual del operador aún no está disponible en el mecanismo de autenticación actual.
- Debe validarse la compilación Kotlin y el comportamiento real en navegador antes de producción.

## Validación recomendada
1. `./gradlew check test`
2. Con PostgreSQL limpio, aplicar migraciones V001–V004.
3. Iniciar en modo producción con credenciales válidas.
4. Abrir Socios y Reparaciones, comprobar carga, edición de precio y validación de valores negativos.
5. Confirmar que guardar una reparación recalcula costo total y precio mínimo en PostgreSQL.
