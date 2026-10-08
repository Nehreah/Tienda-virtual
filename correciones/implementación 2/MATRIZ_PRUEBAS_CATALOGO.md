# Matriz de pruebas de aceptación — Catálogo y Excel

Ejecutar primero la suite actual para registrar la línea base. Luego añadir pruebas unitarias, de rutas, repositorio y, cuando sea posible, integración contra PostgreSQL de pruebas. No afirmar que pasó una prueba que no se ejecutó.

| ID | Escenario | Resultado esperado |
|---|---|---|
| CAT-001 | Crear producto con ID nuevo desde dashboard | Se guarda en BD, aparece en dashboard y sobrevive al reinicio |
| CAT-002 | Crear manualmente ID ya existente, incluso con mayúsculas distintas | Conflicto claro; no se modifica el producto existente |
| CAT-003 | Editar producto existente | Solo cambian campos permitidos; ID permanece igual |
| CAT-004 | Enviar formulario incompleto o precio/cantidad inválidos | Validación clara; no se escribe nada |
| CAT-005 | Dashboard con BD configurada | Lista proviene de BD; no muestra silenciosamente caché desactualizada |
| CAT-006 | BD caída durante escritura | Error controlado; caché no cambia ni se declara éxito |
| XLS-001 | Importar Excel con todos los IDs nuevos | Se crean todos los válidos en una transacción |
| XLS-002 | Importar nuevos con un ID que ya existe | Ese ID no cambia; informe `DUPLICATE_ID` |
| XLS-003 | Importar nuevos con IDs mezclados nuevos/existentes | Se crean solo los nuevos; se reportan los existentes sin modificarlos |
| XLS-004 | Excel contiene dos filas con el mismo ID ignorando mayúsculas | No se crean dos productos; informe de duplicado interno |
| XLS-005 | Actualizar desde Excel con ID existente | Actualiza solo campos permitidos |
| XLS-006 | Actualizar desde Excel con ID desconocido | No crea el producto; informa `ID_NOT_FOUND` |
| XLS-007 | Actualizar Excel con celda vacía | El campo se conserva, salvo borrado explícito configurado |
| XLS-008 | Actualizar Excel que intenta cambiar `key` | Rechazo o ignorar con error explícito; nunca renombrar silenciosamente |
| XLS-009 | Excel con formato/cabeceras incorrectos | Vista previa indica errores; no se escribe nada |
| XLS-010 | Archivo sobre el límite permitido | HTTP 413 y ninguna modificación |
| XLS-011 | Error en una fila durante modo atómico | Se revierte toda la operación y no se refresca caché |
| XLS-012 | Mismo archivo importado dos veces en modo nuevos | Segunda ejecución no duplica ni actualiza; informe idempotente/duplicados |
| XLS-013 | Mismo archivo primero en modo nuevos y luego en modo actualizar | La idempotencia distingue modos; el segundo uso se evalúa según modo actualizar |
| DB-001 | Dos importaciones concurrentes intentan crear el mismo ID | Una sola fila; la otra operación reporta conflicto sin actualizarla |
| DB-002 | Actualización de un producto vendido | No reabre ni sobrescribe datos de venta; aplica reglas de dominio |
| DB-003 | Producto con ventas o reservas se intenta eliminar | Se bloquea; datos históricos intactos; se recomienda archivar |
| DB-004 | Producto sin referencias se elimina definitivamente | Borrado y auditoría confirmados; caché se actualiza tras commit |
| DB-005 | Archivar producto | Se conserva el registro y deja de estar disponible para negociación/publicación según reglas existentes |
| DB-006 | Triggers de precio mínimo/costo total | La BD sigue calculando esos campos; aplicación no los impone desde Excel |
| DB-007 | Reparto por socios y métricas | Sin cambios colaterales en alta/edición/importación de catálogo |
| INT-001 | Webhook Shopify después de editar catálogo | Stock y procesamiento de órdenes continúan funcionando |
| INT-002 | Reserva activa al actualizar producto | No se pierde ni se libera accidentalmente la reserva |
| INT-003 | Publicación en canales externos | La edición no falsifica IDs externos ni declara sincronización exitosa si no ocurrió |
| SEC-001 | Usuario no autenticado intenta mutar catálogo | Rechazado por la protección existente |
| SEC-002 | Texto de producto contiene HTML/script | Se muestra escapado; no ejecuta código |

## Comandos de verificación

Desde la raíz del proyecto:

```bash
./gradlew test --console=plain
```

En Windows:

```bat
gradlew.bat test --console=plain
```

También se puede usar `python run_tests.py`. Si la suite necesita secretos o una base externa, documentar las dependencias y separar pruebas unitarias de integración. No ejecutar migraciones en la base real sin backup, revisión y confirmación humana.
