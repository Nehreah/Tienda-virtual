# Pendiente: probar htmx oficial en el navegador (v11)

**Estado:** `htmx-lite.js` ya fue retirado. El dashboard carga `/static/htmx.min.js` (htmx 1.9.10, archivo local ya presente en
`app/src/main/resources/static/`) con la configuración de la etiqueta `<meta name="htmx-config">`:
`allowEval:false`, `allowScriptTags:false`, `selfRequestsOnly:true`, `historyEnabled:false`, `includeIndicatorStyles:false`.

**No se ha probado en un navegador ni compilado.** Antes de producción:

1. Verificar la integridad del archivo contra la fuente oficial (npm `htmx.org@1.9.10`, `dist/htmx.min.js`; no se pudo comprobar sin red):
   `sha256sum app/src/main/resources/static/htmx.min.js` → `b3bdcf5c741897a53648b1207fff0469a0d61901429ba1f6e88f98ebd84e669e`
2. Ejecutar `./gradlew check test` y arrancar con `APP_ENV=production` (CSP `script-src 'self'`); abrir la consola y la pestaña Network.
3. Probar: abrir cada subpágina (Socios, Reparaciones, Inventario), crear/editar/archivar/restaurar/eliminar un artículo, agregar y retirar un
   socio, registrar y anular una liquidación, cambiar un % de inversión, "Partes Iguales", "Reconciliar Canales", métricas y simulador de chat.
4. Comprobar que los errores de validación (HTTP 400/409 en HTML) aparecen dentro del panel y que 401/403/429/errores de red muestran el aviso.
5. Revisar que no queden `onclick=`/`<script>` en línea: `VentasRenderer.kt` y `ChatRenderer.kt` aún los tienen (la CSP los bloquea).
6. Borrar este archivo solo cuando todo lo anterior pase.
