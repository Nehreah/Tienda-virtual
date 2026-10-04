-- Fase 5 / Excel V2: Sincronizar columnas de inventario con NEGOCIO.xlsx
-- Permite guardar marca, condición, cantidad (stock maestro) y ubicación física en Supabase.

ALTER TABLE tienda.inventario ADD COLUMN IF NOT EXISTS marca TEXT;
ALTER TABLE tienda.inventario ADD COLUMN IF NOT EXISTS condicion TEXT;
ALTER TABLE tienda.inventario ADD COLUMN IF NOT EXISTS cantidad INT NOT NULL DEFAULT 1;
ALTER TABLE tienda.inventario ADD COLUMN IF NOT EXISTS ubicacion TEXT;

COMMENT ON COLUMN tienda.inventario.marca IS 'Marca del producto (ej. Apple, Samsung)';
COMMENT ON COLUMN tienda.inventario.condicion IS 'Condición comercial (ej. Nuevo, Reacondicionado, Usado)';
COMMENT ON COLUMN tienda.inventario.cantidad IS 'Cantidad en stock maestro según matriz Excel';
COMMENT ON COLUMN tienda.inventario.ubicacion IS 'Ubicación física en bodega o estante';
