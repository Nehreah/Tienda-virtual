-- Fase 4.5: Row Level Security para la tabla inventario
-- Ejecutar en Supabase SQL Editor con permisos de superusuario.
--
-- IMPORTANTE: Este script es REVERSIBLE. Para revertir:
--   ALTER TABLE inventario DISABLE ROW LEVEL SECURITY;
--   DROP POLICY IF EXISTS inventario_app_user ON inventario;

-- 1. Habilitar RLS en la tabla de inventario
ALTER TABLE inventario ENABLE ROW LEVEL SECURITY;

-- 2. Política: solo el rol de la app puede leer/escribir filas de su propio tenant
--    Ajustar 'app_user' al rol real creado en Supabase para la aplicación.
CREATE POLICY inventario_app_user
    ON inventario
    AS PERMISSIVE
    FOR ALL
    TO app_user
    USING (true)
    WITH CHECK (true);

-- 3. Denegar acceso anónimo (anon role de Supabase)
REVOKE ALL ON inventario FROM anon;
REVOKE ALL ON inventario FROM authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON inventario TO app_user;

-- 4. Asegurar que la secuencia (si existe) también la pueda usar el rol de la app
-- (Ajustar nombre de secuencia si aplica)
-- GRANT USAGE, SELECT ON SEQUENCE inventario_id_seq TO app_user;
