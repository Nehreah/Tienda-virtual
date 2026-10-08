# Instrucciones para actualizar Tienda Virtual Multicanal

## Archivos de entrada
1. `Tienda-virtual-main.zip`: código fuente actual.
2. `borradores_migraciones_V1_V4.tar.gz`: borradores de migraciones y plan previo.
3. Esta carpeta: especificación funcional, contrato técnico y matriz de pruebas actualizados.

## Orden de lectura
1. `PLAN_IMPLEMENTACION_CATALOGO_V2.md`
2. `CONTRATO_TECNICO_CATALOGO.md`
3. `MATRIZ_PRUEBAS_CATALOGO.md`

## Instrucción principal
Actualiza el código existente, no crees un proyecto nuevo. Conserva la arquitectura Kotlin/Ktor, los módulos, las reglas financieras, ventas, reservas, socios, métricas, webhooks, Shopify/Meta y las migraciones ya aplicadas. No elimines funciones existentes para simplificar.

Implementa el CRUD de productos en el dashboard y los dos modos de importación Excel descritos en el plan. Revisa primero los métodos existentes de `InventarioRepository` y `ExposedInventarioRepository`: ya existen métodos para buscar, guardar, archivar, restaurar, eliminar y detectar referencias; evita duplicarlos sin necesidad.

No asumas que una actualización de Excel puede reemplazar el estado operacional completo. Separa los campos maestros editables de los campos derivados/operacionales. No sobrescribas ventas, reservas, asignaciones de socios, métricas, costos calculados por triggers ni stock operacional reservado.

No agregues una migración SQL por costumbre. Primero determina si el esquema existente es suficiente. Si de verdad hace falta una migración, crea la siguiente versión Flyway libre, idempotente cuando sea posible, y documenta por qué es necesaria.

## Entrega esperada de la IA
- Código fuente actualizado y compilable.
- Nuevos/actualizados tests automatizados.
- Resumen de archivos modificados.
- Comandos exactos para ejecutar tests y migraciones.
- Lista de variables de entorno necesarias y riesgos pendientes.
- No afirmar que las pruebas pasan sin haberlas ejecutado y mostrar su resultado.
