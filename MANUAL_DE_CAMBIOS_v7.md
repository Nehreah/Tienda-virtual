# Manual de cambios v7 — Fase 2: base segura del dashboard

**Proyecto:** Tienda Virtual Multicanal  
**Fase:** F2 — Base del dashboard  
**Fecha:** 10 de octubre de 2026

## Objetivo
Reducir los bloqueos del dashboard provocados por scripts en línea y dependencias de CDN, y servir los recursos del navegador desde el propio servidor.

## Archivos modificados

- `app/src/main/kotlin/com/automata/app/Application.kt`
  - CSP actualizada a `script-src 'self'`, sin JavaScript inline ni CDNs externos.
  - Añadida la ruta de recursos estáticos `/static`.
- `app/src/main/kotlin/com/automata/app/dashboard/DashboardRenderer.kt`
  - El inicializador de tema y el script principal se cargan desde archivos locales.
  - Se elimina la dependencia de `unpkg.com` para htmx.
- `app/src/test/kotlin/com/automata/app/dashboard/DashboardRendererTest.kt`
  - Prueba de regresión para verificar referencias locales y ausencia de scripts inline/CDNs.
- `app/src/main/resources/static/theme-init.js`
  - Inicialización temprana del tema claro/oscuro.
- `app/src/main/resources/static/dashboard.js`
  - Lógica de navegación, tema, filtros y confirmación de pagos extraída del HTML.
- `app/src/main/resources/static/htmx-lite.js`
  - Cliente local mínimo para los atributos `hx-get`, `hx-post`, `hx-target` y `hx-swap` usados en el dashboard.

## Decisión técnica importante
No fue posible descargar htmx desde el CDN en este entorno porque no hay resolución DNS/acceso a Internet. Para evitar depender de un archivo que no está incluido en el repositorio, se incorporó `htmx-lite.js`, que cubre únicamente el subconjunto utilizado por el dashboard. **No es una distribución oficial ni un reemplazo completo de htmx.** Si se usan características htmx adicionales, deberán añadirse o empaquetarse una copia oficial revisada de htmx.

## Seguridad
La CSP permite scripts solo desde el mismo origen. Se mantiene `style-src 'unsafe-inline'` porque la interfaz aún contiene CSS dentro de la página. No se afirma que esto complete toda la política de seguridad ni sustituye HTTPS en producción.

## Validación
- `node --check` pasó para `dashboard.js`, `theme-init.js` y `htmx-lite.js`.
- `./gradlew :app:compileKotlin --no-daemon` **no pudo ejecutarse**: Gradle Wrapper no pudo descargar Gradle por `UnknownHostException` de `services.gradle.org`.
- Las pruebas Kotlin añadidas quedan pendientes de ejecución en un entorno con Gradle y dependencias disponibles.

## Pendiente para cerrar F2
- Ejecutar pruebas y revisar la consola del navegador en `APP_ENV=production`.
- Migrar los fragmentos Tailwind de Socios/Inventario/Chat al CSS propio del dashboard.
- Integrar en la carcasa las pestañas Socios, Inventario, Reparaciones, Ventas y Sincronización.
- Verificar que cada botón hx-* apunte a una ruta implementada y que el destino exista.
