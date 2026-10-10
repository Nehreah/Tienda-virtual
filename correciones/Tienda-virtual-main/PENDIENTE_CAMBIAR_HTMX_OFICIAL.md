# Pendiente para revisar después: reemplazar htmx-lite por htmx oficial

**No olvidar:** antes de dar por terminada la Fase 2 o desplegar en producción, sustituir `app/src/main/resources/static/htmx-lite.js` por la biblioteca oficial htmx, descargada desde una fuente oficial y fijada a una versión concreta.

## Pasos
1. Consultar la documentación oficial y elegir una versión estable compatible con el proyecto.
2. Descargar y guardar el archivo de distribución en `app/src/main/resources/static/` (no depender de un CDN en tiempo de ejecución).
3. Actualizar `DashboardRenderer.kt` para cargar el archivo oficial local y retirar `htmx-lite.js`.
4. Verificar compatibilidad de `hx-get`, `hx-post`, `hx-put`, `hx-delete`, `hx-target`, `hx-swap`, formularios y eventos.
5. Comprobar que la CSP `script-src 'self'` sigue funcionando sin `unsafe-inline` para scripts.
6. Ejecutar `./gradlew check test` y probar en navegador con `APP_ENV=production`; revisar consola y pestaña Network.
7. Eliminar este recordatorio solo después de confirmar las pruebas.

**Importante:** `htmx-lite.js` es una implementación local limitada, no la biblioteca oficial ni un reemplazo completo de htmx.
