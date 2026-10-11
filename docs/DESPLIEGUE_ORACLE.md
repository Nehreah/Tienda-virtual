# Despliegue en Oracle Cloud (o cualquier servidor con Docker)

Arquitectura: **Caddy** (HTTPS, puertos 80/443) → **app** (Ktor, solo 127.0.0.1:8080) → **PostgreSQL** (red interna de Docker).

## 1. Servidor
1. Crea la instancia (Ubuntu o Oracle Linux; las Ampere A1 gratuitas, ARM, funcionan: todas las imágenes tienen build `arm64`).
2. En la consola de Oracle: VCN → Security List → permite entrada TCP **80** y **443** (y 22 para SSH). No abras 8080 ni 5432.
3. Firewall del propio servidor (las imágenes de Oracle suelen bloquear todo):
   - Ubuntu: `sudo iptables -I INPUT 6 -p tcp --dport 80 -j ACCEPT && sudo iptables -I INPUT 6 -p tcp --dport 443 -j ACCEPT && sudo netfilter-persistent save`
   - Oracle Linux: `sudo firewall-cmd --permanent --add-service=http --add-service=https && sudo firewall-cmd --reload`
4. Si la máquina tiene 1 GB de RAM, crea swap de 2 GB antes de compilar (`docker build` usa Gradle).
5. Instala Docker y el plugin compose.

## 2. Dominio
Crea un registro DNS tipo A con tu dominio apuntando a la IP pública del servidor. Caddy obtiene el certificado solo.

## 3. Configuración
```bash
cp .env.example .env
nano .env        # completa DOMAIN, POSTGRES_PASSWORD, DASHBOARD_PASSWORD, META_APP_SECRET, META_WEBHOOK_VERIFY_TOKEN...
docker compose up -d --build
docker compose logs -f app
```
`DATABASE_USER` y `DATABASE_PASSWORD` no hacen falta: por defecto usan los de `POSTGRES_*`, así no pueden quedar distintos.

## 4. Webhooks
- Meta: Callback URL `https://TU_DOMINIO/webhooks/meta`, con el mismo `META_WEBHOOK_VERIFY_TOKEN`.
- Shopify: `https://TU_DOMINIO/webhooks/shopify`.

## 5. Notas
- Base externa (Supabase u otra): define `DATABASE_URL=jdbc:postgresql://HOST:5432/postgres?sslmode=require`, `DATABASE_USER` y `DATABASE_PASSWORD`, y elimina el servicio `db`. Sin SSL la app solo acepta bases cuyo host sea `db`, `postgres` o `localhost`.
- `TRUST_PROXY=true` ya viene activo en el compose porque Caddy es el único proxy delante.
- Copias de seguridad: `docker compose exec db pg_dump -U tiendauser tiendadb > respaldo.sql`.
