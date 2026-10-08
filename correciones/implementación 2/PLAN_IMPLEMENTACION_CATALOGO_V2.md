# Plan de implementación actualizado — Catálogo, dashboard y Excel

**Proyecto:** Tienda Virtual Multicanal (Kotlin, Ktor, PostgreSQL/Supabase, HTMX/dashboard)

## 1. Objetivo

Permitir administrar productos desde el dashboard o Excel con dos operaciones de importación inequívocas:

- **Importar productos nuevos:** inserta únicamente claves que todavía no existen. Los IDs duplicados no actualizan nada y se reportan.
- **Actualizar productos desde Excel:** actualiza únicamente productos ya existentes por ID. Los IDs desconocidos no crean productos y se reportan.

Además, permitir crear, consultar, editar, archivar y eliminar productos desde el dashboard, manteniendo coherentes la base de datos, la memoria de la aplicación y los procesos que consumen el catálogo.

## 2. Diagnóstico del código actual

La base ya contiene una buena parte de la infraestructura necesaria:

- `InventarioRepository` declara `findAll`, `findByKey`, `upsert`, `archivar`, `restaurar`, `eliminar` y `hasHistoryOrReferences`.
- `ExposedInventarioRepository` implementa persistencia y tiene protecciones relacionadas con productos vendidos y referencias.
- Hay rutas Excel de vista previa, confirmación, subida y exportación.
- Existe la tabla `tienda.importaciones` para auditoría, además del historial genérico de cambios.
- Existe índice único normalizado para la clave del inventario (`UPPER(key)`).

Pero el comportamiento actual no cumple la nueva regla de negocio:

1. `/api/excel/upload` y `/api/excel/confirmar` recorren los productos y llaman a `upsert`; esto puede actualizar claves existentes. No debe reutilizarse sin una estrategia explícita de inserción exclusiva.
2. La ruta principal del dashboard arma la lista desde `AppStore.products`, no directamente desde el repositorio de base de datos. La memoria puede quedar desfasada o ser la única fuente si la conexión a BD no está configurada.
3. El README confirma que sin `DATABASE_URL` el inventario queda en memoria y se pierde al reiniciar. Para un flujo que promete sincronización con Supabase, los cambios de catálogo deben fallar de forma clara si la persistencia no está disponible, salvo que se esté usando deliberadamente un modo local de desarrollo.
4. Las rutas de Excel toman el contenido completo del archivo en memoria y el límite es de 5 MB; debe mantenerse el límite y validar extensiones, estructura y filas antes de escribir.
5. El proyecto tiene operaciones de archivo/eliminación a nivel de repositorio, pero se debe comprobar su integración completa con las rutas, UI, estado en memoria y reglas de referencias antes de usarlas.

## 3. Principios obligatorios

1. **Supabase/PostgreSQL es la fuente de verdad persistente** cuando `DATABASE_URL` está configurado.
2. La memoria `AppStore` es una proyección/caché, no una segunda fuente de verdad. Tras un commit exitoso, refrescarla desde el repositorio o aplicar el resultado devuelto por la base. Ante error de BD, no modificar la memoria.
3. Comparar IDs de forma consistente con la restricción de BD: quitar espacios exteriores y comparar sin distinguir mayúsculas/minúsculas. No cambiar silenciosamente el ID guardado; conservar la clave original validada. Rechazar IDs vacíos.
4. Todo cambio debe ejecutarse con autenticación del dashboard, validación de dominio y transacción cuando corresponda.
5. Los errores deben ser explícitos y por fila; no devolver “éxito” si alguna operación falló sin reportarlo.
6. Evitar escrituras masivas no atómicas. Si hay un error de base de datos durante una importación confirmada, no debe quedar una importación parcialmente aplicada.
7. No sobrescribir campos operacionales/derivados por importación de catálogo, especialmente ventas, fechas/precio final de venta, reservas, stock reservado, reparto de socios, historial y campos calculados por triggers.
8. Mantener la compatibilidad con Shopify, canales Meta/WhatsApp, webhooks, reservas y métricas. Un cambio del catálogo no debe disparar ventas ni alterar registros históricos.

## 4. Experiencia de usuario del dashboard

En la sección de inventario/catálogo añadir:

- `Agregar producto`.
- `Importar nuevos desde Excel`.
- `Actualizar existentes desde Excel`.
- `Exportar Excel` (mantener lo existente).
- Edición por fila o formulario de detalle.
- `Archivar` y `Eliminar definitivamente` como acciones separadas cuando corresponda.
- Resultado de importación descargable o visible, con resumen y detalle por fila.

Mostrar indicadores: total de productos, creados, actualizados, omitidos por ID duplicado, IDs no encontrados, errores de validación y fallos de persistencia.

### Formulario manual

- El ID/Key es obligatorio y no se debe poder modificar al editar un producto existente. Para cambiarlo, debe ser un flujo específico con comprobación de todas las referencias; por defecto no permitirlo.
- Validar nombre no vacío, precios no negativos, cantidad no negativa, estado válido, fechas coherentes, URL de imágenes y campos requeridos.
- Precio mínimo y costo total son valores calculados por la base/triggers: mostrarlos como derivados y no permitir que el formulario los imponga.
- No permitir cambios que reabran o reescriban ventas por una edición general. La anulación de una venta debe seguir su operación de dominio existente.
- Tras guardar, mostrar el registro devuelto por la base y refrescar el dashboard.

## 5. Contrato de los dos modos Excel

### A. Importar nuevos desde Excel

Endpoint sugerido: `POST /api/excel/import-new` (o nombre coherente con rutas existentes; no romper rutas actuales hasta migrar UI y pruebas).

Semántica estricta por clave normalizada:

- Clave no existe en BD: candidato a crear.
- Clave ya existe en BD: omitir sin cambiar absolutamente ningún campo y añadir al informe `DUPLICATE_ID`.
- Dos filas del archivo tienen la misma clave normalizada: procesar como error/duplicado de archivo; nunca insertar ambas. Recomendación: conservar la primera fila válida como referencia y marcar las posteriores como duplicadas, o rechazar el archivo completo si el contrato de previsualización existente exige atomicidad. El comportamiento debe ser uniforme y visible antes de confirmar.
- Fila inválida: informar fila, columna, clave si existe y motivo.
- Confirmación: insertar los candidatos válidos en una transacción; la unicidad de BD es la última defensa ante importaciones concurrentes.
- Si una clave se inserta concurrentemente entre la previsualización y la confirmación, capturar el conflicto de unicidad, informar como duplicado y no actualizar esa fila.

Informe mínimo por archivo:
`totalFilas`, `creados`, `duplicadosEnBD`, `duplicadosEnArchivo`, `erroresValidacion`, `fallidosBD`, y detalle `{filaExcel, id, estado, motivo}`.

### B. Actualizar existentes desde Excel

Endpoint sugerido: `POST /api/excel/update-existing`.

Semántica estricta por clave normalizada:

- Clave existe: actualizar solo los campos de catálogo permitidos que estén definidos en el contrato.
- Clave no existe: no crear; informar `ID_NOT_FOUND`.
- IDs duplicados dentro del archivo: no ejecutar dos actualizaciones ambiguas. Marcar las filas repetidas y resolver/rechazar conforme a una única política explícita.
- Antes de aplicar, mostrar previsualización con campos que cambiarían y advertencias por producto.
- Confirmar en una transacción. Si la validación de una fila falla, la política por defecto es cancelar la operación completa; solo admitir actualización parcial si se implementa explícitamente, se informa en la UI y se prueba.
- El informe debe distinguir actualizados, sin cambios, IDs desconocidos, duplicados del archivo, bloqueados por reglas de dominio y errores.

### Campos autorizados para Excel

Usar el mapeo ya existente de `ExcelV2Parser` como base, pero definir una lista blanca única para ambos modos. En principio pueden ser campos maestros: nombre, descripción, tipo/categoría, marca, condición, ubicación, link/fotos, precio de compra, precio publicado y cantidad maestra, estado comercial cuando las reglas de dominio lo permitan.

No actualizar desde Excel: `key` de productos existentes, `precio_minimo`, `costo_total`, `costo_lote` calculados; ventas y sus importes/fechas; reservas; inversiones/reparto de socios; mensajes/conversaciones; IDs externos de canal; historial; versiones de sincronización. Revisar especialmente el significado de `cantidad`: no sobrescribir una cantidad operacional o reservada sin pasar por el caso de uso de inventario.

Las celdas vacías requieren una regla documentada: no deben borrar datos por accidente. Para la primera versión, tratar celdas vacías como “sin cambio” en el modo actualizar, salvo que el usuario elija una opción explícita de borrar campos. Para crear, aplicar los valores por defecto existentes y avisarlos en la vista previa.

## 6. Persistencia y sincronización

Implementar un servicio de aplicación único, por ejemplo `CatalogManagementService`, que reciba el repositorio y coordine validación, transacción, informes y refresco de estado. No poner reglas de negocio complejas directamente en el HTML ni duplicarlas en varias rutas.

### Flujo de escritura

1. Autenticar usuario.
2. Validar solicitud/archivo, tamaño y estructura.
3. Normalizar y validar claves.
4. Generar vista previa y clasificación de cada fila.
5. En la confirmación, abrir transacción de BD.
6. Volver a comprobar existencia y restricciones dentro de la transacción; no confiar en la vista previa antigua.
7. Ejecutar inserciones/actualizaciones explícitas, no `upsert` genérico para ambos modos.
8. Guardar resultado/auditoría de importación.
9. Commit.
10. Solo después del commit, refrescar el estado en memoria desde la BD y devolver el informe final.

Si el repositorio o la BD no está disponible, las rutas de gestión persistente deben responder un error de servicio no disponible; no escribir en `AppStore` como si la operación hubiera quedado guardada en Supabase.

### Lecturas

El dashboard debe cargar el inventario desde `dbRepository.findAll()` cuando exista BD configurada. Si falla la lectura, mostrar error controlado; no reemplazarlo silenciosamente por una lista en memoria que pueda parecer actual. El modo memoria puede mantenerse para pruebas/local, pero debe estar claramente diferenciado.

### Concurrencia

- Mantener el índice único case-insensitive de `key`.
- Capturar conflictos únicos de PostgreSQL y transformarlos en resultados de dominio legibles.
- Usar transacciones y, cuando sea necesario, bloqueo/lectura actualizada al editar para evitar actualizaciones perdidas.
- Si existe campo `version`, confirmar que su semántica es coherente; no reutilizarlo sin revisar triggers y contratos de canales.

## 7. Editar, archivar y eliminar

### Editar

Usar `findByKey` para recuperar el estado actual, aplicar cambios permitidos y guardar. No construir una entidad incompleta que reemplace campos no mostrados en el formulario. Evitar que un campo omitido se convierta en `null`, cero o valor por defecto.

### Archivar

Preferir archivar/inactivar para productos que ya se publicaron o tienen referencias. No deben ofrecerse como disponibles para negociación/publicación, pero deben conservarse para historia y métricas.

### Eliminar definitivamente

El botón debe pedir confirmación y explicar que la operación es irreversible. Antes de borrar, ejecutar `hasHistoryOrReferences(key)` y verificar también restricciones de FK en BD.

- Si tiene ventas, reservas, conversaciones u otras referencias: bloquear eliminación definitiva y recomendar archivar.
- Si no tiene referencias: permitir `eliminar` con transacción y auditoría.
- Nunca borrar en cascada registros históricos/financieros para conseguir que la operación pase.
- Ante un conflicto FK, responder con un mensaje entendible y conservar los datos.

## 8. Auditoría e informes

Aprovechar `tienda.importaciones` y `tienda.historial` existentes antes de crear tablas nuevas. Guardar por importación: ID, usuario autenticado, fecha, hash del archivo, modo (`IMPORT_NEW` o `UPDATE_EXISTING`), estado, conteos y detalle de errores/resultados. Si el esquema actual no tiene una columna para el modo, decidir si conviene añadirla con migración Flyway o incluirlo de forma estructurada en `conteos`/metadatos existentes.

No usar un usuario fijo como `dashboard` o `sistema` si se puede recuperar el usuario autenticado; el historial debe permitir rastrear quién cambió qué.

No registrar tokens, contraseñas ni contenido privado de conversaciones en el informe de Excel.

## 9. Rutas y compatibilidad

- Mantener la autenticación `requireDashboardAuth` en todos los endpoints mutantes.
- Mantener límites de carga (actualmente 5 MB) y validar tipo/extensión real de archivo.
- Evitar aceptar nombres de archivos como garantía de que son XLSX: manejar errores del parser con respuesta segura.
- Añadir endpoints REST separados o un parámetro de modo validado; nunca inferir el modo a partir del nombre del botón.
- Revisar la ruta de preview existente: debe mostrar la política del modo elegido y la misma clasificación que usará la confirmación.
- La confirmación debe identificar el archivo por hash y modo; una importación anterior del mismo archivo en modo “nuevos” no debe impedir usarlo posteriormente en modo “actualizar existentes”, ni viceversa. Ajustar la idempotencia para incluir el modo.
- Actualizar el dashboard, documentación y exportación sin romper rutas previas hasta que las nuevas pruebas estén pasando.

## 10. Seguridad y validación

- Todas las operaciones mutantes requieren autenticación.
- Escapar valores en HTML y no interpolar valores de producto directamente dentro de scripts.
- Validar campos del lado servidor aunque el navegador tenga validación.
- Protegerse contra IDs que difieran solo por mayúsculas/espacios.
- Limitar número de filas y tamaño del Excel; evitar cargas abusivas.
- Evitar filtración de stack traces o secretos al usuario.
- No permitir edición de campos sensibles a través de parámetros inesperados.

## 11. Orden de implementación

1. **Inspección y línea base:** ejecutar pruebas existentes; documentar fallos previos antes de cambiar código.
2. **Contrato de campos:** crear DTOs separados para crear/editar/importar; definir lista blanca y semántica de celdas vacías.
3. **Servicio de catálogo:** centralizar operaciones, validaciones, reglas de duplicados y actualización de caché.
4. **CRUD manual:** crear, editar, archivar, eliminar seguro y lectura desde BD.
5. **Excel modo nuevos:** preview, confirmación transaccional, duplicados por ID y reporte.
6. **Excel modo actualizar:** preview de diferencias, actualización solo de existentes, celdas vacías seguras y reporte.
7. **Auditoría:** guardar usuario, modo, hash, conteos y errores.
8. **Dashboard UX:** formularios, confirmaciones, estados de carga y resumen descargable.
9. **Integridad de integraciones:** probar webhooks, ventas, reservas, stock, socios, métricas y publicación en canales.
10. **Pruebas completas y documentación:** ejecutar suite real Gradle, corregir y reportar el resultado real.

## 12. Criterios de aceptación

La actualización se considera completa solo si todos los casos de `MATRIZ_PRUEBAS_CATALOGO.md` pasan y se comprueba que:

- Los datos persistidos sobreviven al reinicio de la aplicación.
- El dashboard refleja los valores de Supabase y no solo los de memoria.
- Importar nuevos nunca modifica un ID existente.
- Actualizar existentes nunca crea un ID desconocido.
- Los fallos no dejan la base y el estado en memoria en desacuerdo.
- Los productos vendidos/referenciados no pierden historia ni métricas.
- Los informes explican cada fila omitida o rechazada.
