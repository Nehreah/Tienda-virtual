# Contrato técnico: operaciones del catálogo

Este documento define el comportamiento que deben respetar rutas, servicios, UI y tests. Es complemento del plan, no código ejecutable.

## 1. Operaciones

| Operación | Clave ya existe | Clave no existe | Efecto permitido |
|---|---|---|---|
| Crear manual | Rechazar con conflicto | Crear | Una fila nueva |
| Editar manual | Actualizar campos permitidos | 404/no encontrado | No cambiar clave |
| Importar nuevos | Omitir e informar duplicado | Crear | Nunca actualizar existentes |
| Actualizar desde Excel | Actualizar campos permitidos | Omitir e informar ID desconocido | Nunca crear |
| Archivar | Inactivar/archivar | 404/no encontrado | Conservar historial |
| Eliminar definitivo | Solo si no hay referencias | 404/no encontrado | Borrado físico seguro y auditado |

## 2. Identidad

- La identidad del producto es `key` (ID de negocio), no el nombre, SKU externo, URL o número de fila.
- Validar `trim(key).isNotBlank()`.
- La comparación para detectar conflictos debe coincidir con el índice único de PostgreSQL sobre `UPPER(key)`. Aplicar la misma regla en memoria, Excel y dashboard.
- Un ID con espacios exteriores se puede normalizar quitando esos espacios antes de validar. No permitir que diferencias de mayúsculas creen productos distintos.

## 3. Campos de catálogo y campos protegidos

La implementación debe documentar el mapeo exacto entre cabeceras Excel y propiedades del dominio. Lista inicial sugerida:

**Maestros editables, previa validación:** `item`, `descripcion`, `tipo`, `marca`, `condicion`, `ubicacion`, `link`, `fotos`, `precioCompra`, `precioPublicado`, `cantidad`/cantidad maestra y `estado` si la transición de estado está permitida por las reglas de dominio.

**Protegidos contra reemplazo masivo:** `key` en edición, `precioMinimo`, `costoTotal`, `costoLote`, `fechaVenta`, `vendidoEn`, registros de ventas, reservas, inversiones por socio, historial, conversaciones, estados de pago, IDs externos de publicación y valores calculados por triggers.

**Requieren análisis especial antes de escribir:** `cantidad` y `estado`. El sistema tiene flujos operacionales de stock y ventas; Excel no debe reponer unidades reservadas ni reabrir productos vendidos accidentalmente. Si `cantidad` representa el stock maestro, el servicio debe traducirlo a una operación segura y no escribir sobre un saldo reservado sin reconciliación.

La lista es deliberadamente explícita: la IA implementadora debe inspeccionar el esquema real y los triggers antes de habilitar campos adicionales.

## 4. Celdas vacías

En `UPDATE_EXISTING`, una celda vacía significa **no modificar** por defecto. No convertirla automáticamente en `null`, cadena vacía o cero. Si se requiere borrar un dato, debe existir una opción explícita, documentada y visible en la vista previa.

En `IMPORT_NEW`, aplicar defaults de dominio y mostrarlos en la previsualización. Los campos obligatorios vacíos causan error antes de escribir.

## 5. Informe JSON lógico

El informe puede serializarse con el mecanismo de respuesta ya usado por Ktor. Debe contener al menos:

```json
{
  "mode": "IMPORT_NEW",
  "fileHash": "sha256...",
  "summary": {
    "rowsRead": 0,
    "created": 0,
    "updated": 0,
    "unchanged": 0,
    "duplicateIdsInDatabase": 0,
    "unknownIds": 0,
    "duplicateIdsInFile": 0,
    "validationErrors": 0,
    "blockedByDomainRules": 0,
    "databaseErrors": 0
  },
  "rows": [
    {"row": 2, "id": "ABC-1", "status": "DUPLICATE_ID", "message": "Ya existe; no se modificó"}
  ]
}
```

Los valores anteriores son un esquema ilustrativo, no un resultado real. Usar enums/DTOs tipados en Kotlin y evitar construir JSON por concatenación manual.

Estados de fila recomendados: `CREATED`, `UPDATED`, `UNCHANGED`, `DUPLICATE_ID`, `DUPLICATE_IN_FILE`, `ID_NOT_FOUND`, `VALIDATION_ERROR`, `BLOCKED_BY_DOMAIN_RULE`, `DATABASE_ERROR`.

## 6. Endpoints orientativos

Alinear los nombres finales con el estilo del proyecto y evitar romper rutas existentes:

- `POST /api/catalog/products` — crear manualmente.
- `PUT /api/catalog/products/{key}` — editar manualmente.
- `POST /api/catalog/products/{key}/archive` — archivar.
- `DELETE /api/catalog/products/{key}` — eliminar definitivamente si es seguro.
- `POST /api/excel/preview?mode=IMPORT_NEW` — previsualizar importación de nuevos.
- `POST /api/excel/preview?mode=UPDATE_EXISTING` — previsualizar actualización.
- `POST /api/excel/confirm?mode=IMPORT_NEW` — confirmar altas.
- `POST /api/excel/confirm?mode=UPDATE_EXISTING` — confirmar actualizaciones.

Los nombres son sugerencias: pueden mantenerse `/api/excel/preview` y `/api/excel/confirmar` si el modo es explícito y validado. No mantener dos rutas que hagan lo mismo con reglas diferentes.

## 7. Transacciones y caché

- La verificación de existencia y la escritura se repiten en la transacción de confirmación.
- No usar `upsert` para el modo `IMPORT_NEW`.
- No usar `upsert` con entidades parciales para `UPDATE_EXISTING`.
- Después del commit, recargar productos afectados o el catálogo completo desde el repositorio.
- Si la transacción falla, no actualizar `AppStore` y no reportar éxito.
- La base de datos impone la unicidad final para solicitudes simultáneas.

## 8. Eliminación segura

`hasHistoryOrReferences(key)` es una primera barrera, no reemplaza las restricciones FK. Si devuelve `true`, bloquear borrado definitivo. Si devuelve `false`, intentar eliminar en transacción y capturar violaciones FK. El producto no debe desaparecer de la memoria antes de confirmar el borrado.

## 9. Auditoría e idempotencia

La idempotencia de importación debe incluir `hash del archivo + modo`. Los conteos y errores deben guardarse en `tienda.importaciones` o en una estructura compatible. Usar el usuario real autenticado si está disponible. No registrar secretos ni tokens.
