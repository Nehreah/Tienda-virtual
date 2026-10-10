# Manual de cambios v9 — Continuación de Fase 3

**Fecha:** 10 de octubre de 2026  
**Proyecto:** Tienda Virtual Multicanal (`Tienda-virtual-main`)  
**Estado:** cambios de código realizados; compilación Kotlin y pruebas de integración no confirmadas en este entorno.

Este documento registra lo que se modificó en esta entrega, lo que queda incompleto y los pasos necesarios para continuar. No debe interpretarse como certificación de que el sistema esté listo para producción.

## 1. Cambios realizados en esta entrega

### 1.1 Edición de inventario con preservación de campos
- Se añadió `ArticuloLifecycleService.buscar(ProductKey)` como consulta de lectura del artículo actual.
- La ruta `PUT /api/articulos/{key}` ahora busca el artículo existente y combina los campos omitidos con sus valores guardados. Esto corrige el riesgo previo de reemplazar datos omitidos por valores por defecto (por ejemplo, costo 0, cantidad 1 o estado Publicado).
- Se agregaron validaciones para costo, precio publicado, cantidad y estado; valores numéricos negativos o inválidos devuelven `400 Bad Request`.
- Para los campos de texto opcionales: campo omitido conserva su valor; campo enviado vacío limpia el valor opcional. El nombre vacío conserva el nombre existente.
- Se conserva la ruta HTTP `PUT` por compatibilidad. Aunque evita la pérdida de datos por campos omitidos, semánticamente sería más claro añadir una ruta `PATCH` para actualizaciones parciales en una futura revisión.

### 1.2 Matriz de inversión accesible desde Inventario
- Se agregó un panel «Distribución de inversión por artículo» a la vista del catálogo.
- `dashboard.js` carga `/api/inventario/tabla` al entrar a la vista `catalogo`.
- Los controles de `InventarioRenderer` ahora actualizan el contenedor `#inventario-inversion-panel`, en vez de `#main-content`, que no existe en la estructura actual del dashboard.
- La ruta ya existente permite fijar porcentajes y repartir partes iguales. La base de datos sigue siendo la autoridad para las restricciones de suma y para impedir cambios en artículos vendidos.

### 1.3 Documentación y nota de htmx
- Se conserva `PENDIENTE_CAMBIAR_HTMX_OFICIAL.md`. `htmx-lite.js` sigue siendo una implementación local limitada, no la biblioteca oficial htmx.
- Se mantiene la documentación de fases anteriores y este manual registra las nuevas modificaciones.

## 2. Cambios de fases anteriores que siguen presentes

- Navegación y carga bajo demanda de las vistas Socios y Reparaciones.
- Ruta para renombrar socios.
- Ruta y renderizado inicial de reparaciones conectados al repositorio PostgreSQL cuando la base está disponible.
- Estilos locales del dashboard y recursos JavaScript locales; no se añadió una dependencia de CDN para resolver estas vistas.
- Nota pendiente para sustituir `htmx-lite.js` por una versión oficial, fijada y comprobada de htmx.

Consultar también `MANUAL_DE_CAMBIOS_v8.md` y `PENDIENTE_CAMBIAR_HTMX_OFICIAL.md`.

## 3. Lo que NO se hizo todavía

- No se creó una interfaz completa de creación, edición, archivado y restauración de artículos desde el catálogo. Las rutas de backend ya existentes no equivalen a una interfaz de usuario terminada.
- No se añadió una interfaz completa para gestionar socios (mensajes de error consistentes, confirmaciones, edición integrada y recuperación de estados en todas las acciones).
- No se implementó liquidación contable de socios, registro de aportes en pesos ni saldo pendiente de pago. El modelo existente distribuye porcentajes por artículo; no debe confundirse con un libro contable de capital o pagos.
- No se implementó importación/exportación específica de reparaciones a Excel.
- No se añadieron paginación, filtros avanzados ni búsqueda para las tablas de socios, artículos o reparaciones.
- No se sustituyó `htmx-lite.js` por la distribución oficial htmx.
- No se completó el chat operativo en tiempo real ni la integración real de sincronización con Shopify/Meta; son fases posteriores.
- No se modificaron las migraciones existentes ni se recreó una base de datos.
- No se verificó un despliegue en un entorno de producción ni con datos reales.

## 4. Limitaciones técnicas de esta entrega

- En este entorno, la compilación de Gradle no se pudo completar porque el wrapper requiere descargar Gradle y el entorno no pudo resolver `services.gradle.org` (falta de conectividad de red). Por tanto, no se puede afirmar que Kotlin compile ni que pasen las pruebas automatizadas.
- La comprobación de sintaxis de JavaScript sí puede ejecutarse localmente, pero no demuestra que las rutas Ktor, SQL, transacciones, CSP y comportamiento del navegador funcionen de extremo a extremo.
- Las rutas de reparación requieren PostgreSQL y sus migraciones. No existe un repositorio en memoria equivalente para esa funcionalidad.
- El nombre de auditoría para ciertas operaciones sigue siendo `dashboard`; no se dispone aún de una identidad individual del operador en este flujo.
- La matriz de inversión usa clases CSS tipo Tailwind en su renderer actual, pero el dashboard no carga Tailwind desde CDN. La funcionalidad puede operar con CSS incompleto; se necesita adaptar todo el renderer a las clases CSS locales para lograr una presentación coherente.
- La edición parcial sigue usando `PUT` para mantener compatibilidad. Una API formal `PATCH`, con contrato de campos omitidos/vacíos documentado, sería más clara.
- No se hizo una prueba real de las restricciones diferidas de PostgreSQL para porcentajes, ni de los triggers de costo mínimo y reparación.

## 5. Validación ejecutada / pendiente

### Ejecutable en este entorno
- `node --check app/src/main/resources/static/htmx-lite.js`
- `node --check app/src/main/resources/static/dashboard.js`
- Revisión estática de que el panel de inversión está presente y la vista `catalogo` carga `/api/inventario/tabla`.

### Pendiente de ejecutar en un entorno con Gradle y PostgreSQL
1. `./gradlew check test`.
2. Aplicar las migraciones previstas sobre una base de pruebas limpia.
3. Probar `PUT /api/articulos/{key}` enviando un único campo y confirmar que los otros valores permanecen iguales.
4. Probar costo/precio/cantidad negativos, valores no numéricos, precio publicado vacío y estado inválido.
5. Probar matriz de inversión con suma 0, menor que 100, igual a 100 y mayor que 100; verificar también que un artículo vendido no se pueda alterar.
6. Probar crear/renombrar/archivar/reactivar socios y las reglas de máximo de socios y nombres únicos.
7. Guardar una reparación y verificar en PostgreSQL que se actualicen costo total y precio mínimo por los triggers previstos.
8. Probar navegación, autenticación, CSP y las interacciones en un navegador real.

## 6. Próximos pasos recomendados

1. Ejecutar compilación y pruebas automatizadas en un equipo con Gradle disponible y acceso a dependencias.
2. Corregir cualquier error de compilación antes de ampliar funciones.
3. Completar el renderer de inversión con CSS local, sin Tailwind CDN.
4. Completar CRUD de inventario con formulario de edición que use el contrato parcial y confirmación de archivado.
5. Completar el flujo de socios y documentar las decisiones de negocio sobre liquidación y aportes.
6. Añadir importación/exportación de reparaciones si se confirma el formato de Excel esperado.
7. Sustituir `htmx-lite.js` por htmx oficial siguiendo la nota específica, después de pruebas de CSP y de todas las acciones `GET`, `POST`, `PUT` y `DELETE`.
8. Solo entonces continuar con chat, sincronización de canales y preparación de producción.

## 7. Estado general

**Entrega parcial de desarrollo, no certificada para producción.** La corrección más importante de esta versión es evitar que una actualización de inventario con campos omitidos reemplace datos existentes por valores por defecto. La matriz de inversión queda accesible desde el catálogo, pero la compilación Kotlin y las pruebas de integración continúan pendientes por las limitaciones descritas.
