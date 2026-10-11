# Manual: cómo crear, cambiar y proteger la clave del tablero

La clave protege el tablero, la exportación de Excel (con costos y precio mínimo) y la subida de Excel.
Este documento corresponde a la **Fase 1 (tarea 1.1)** del plan de implementación.

---

## 0. Qué cambia según el entorno (`APP_ENV`)

| `APP_ENV` | Clave obligatoria | Dónde escucha el servidor | Si falta la clave |
|---|---|---|---|
| `local` (por defecto) | No, pero se recomienda | **Solo `127.0.0.1`** (tu propia máquina) | Aviso en consola; el tablero no pide clave y nadie más puede conectarse |
| `production` | **Sí, 12 caracteres o más** | Todas las interfaces (`0.0.0.0`) | **La app no arranca** y explica por qué |

Un valor distinto de `local` o `production` (por ejemplo `prodution`) también impide arrancar: un error de tipeo nunca se interpreta como "local".

---

## 1. Crear la clave la primera vez

`./start.sh` ya genera una clave aleatoria y la escribe en `local.properties` la primera vez que lo ejecutas. Para un servidor
(o si prefieres crearla tú), genera una clave larga y aleatoria. No uses nombres, fechas ni palabras del negocio.

```bash
openssl rand -base64 24
# ejemplo de salida: 3q9fX1mB7sV0uK2eYtR8nLhPzWc4JdAo
```

Guárdala en un gestor de contraseñas. Luego ponla en `local.properties` (en el servidor, **nunca en git**):

```properties
APP_ENV=production
DASHBOARD_USER=admin
DASHBOARD_PASSWORD=3q9fX1mB7sV0uK2eYtR8nLhPzWc4JdAo
```

Restringe el archivo a tu usuario: `chmod 600 local.properties`.

También puedes usar variables de entorno con los mismos nombres. **Si existen ambas, gana la variable de entorno.**

> Los valores de ejemplo (`change_me`, `cambiar_esta_clave`, `shpat_xxx...`) se reconocen y se ignoran: una clave de ejemplo
> cuenta como "vacía" y en producción la app no arranca.

---

## 2. Entrar al tablero

El navegador te lleva a un **formulario de ingreso** (`/login`). Entra con `admin` (o el valor de `DASHBOARD_USER`) y tu clave.
Al entrar queda una **cookie de sesión** (HttpOnly, `SameSite=Strict`, y `Secure` en producción) que dura **8 horas**.
El botón **Salir** (arriba a la derecha) cierra la sesión de verdad. Usa siempre **HTTPS**: con HTTP la clave viaja legible.

`curl` y los scripts siguen funcionando con HTTP Basic (`-u usuario:clave`), igual que antes.

### Usuario de solo lectura (opcional)

Para un socio o el contador que solo necesita **mirar**, define un segundo usuario:

```properties
DASHBOARD_VIEWER_USER=socio
DASHBOARD_VIEWER_PASSWORD=otra-clave-larga-de-12-o-mas
```

- Los dos van juntos (uno solo es un error de configuración), el usuario debe ser distinto del administrador y, en producción,
  la clave también necesita 12 caracteres o más.
- Ve todo el tablero y puede exportar el Excel, pero **cualquier acción que modifique datos responde 403**
  ("Tu usuario es de solo lectura").

### Sesiones: `SESSION_SECRET`

- Sin `SESSION_SECRET`, la app genera un secreto al arrancar: **todas las sesiones se cierran cada vez que se reinicia**.
- Con `SESSION_SECRET` fijo (`openssl rand -base64 32`) las sesiones sobreviven a un reinicio. **Cambiarlo cierra todas las
  sesiones de golpe** (úsalo si sospechas que una cookie se filtró).
- La cookie no contiene la clave: solo usuario, rol y vencimiento, con firma. Una cookie alterada se rechaza.

Para subir o simular desde un navegador no hay que hacer nada especial. Si automatizas con `curl`, los `POST` deben llevar la
cabecera `Origin` con el host de la app (defensa contra CSRF, tarea 1.4):

```bash
curl -u admin:TU_CLAVE -H "Origin: https://tu-dominio.com" \
     -F "file=@NEGOCIO.xlsx" https://tu-dominio.com/api/excel/upload
```

Los `GET` (por ejemplo `/api/excel/export`) no la necesitan.

---

## 3. Cambiar la clave

1. Genera otra con `openssl rand -base64 24`.
2. Reemplaza el valor de `DASHBOARD_PASSWORD`.
3. **Reinicia la app.** La clave se lee al arrancar, así que el cambio no aplica sin reinicio.
4. Las sesiones abiertas **siguen valiendo hasta que vencen** (8 horas) o se reinicia la app sin `SESSION_SECRET`. Para cerrarlas
   todas ya, cambia también `SESSION_SECRET`. Luego entra con la clave nueva; usar la vieja cuenta como intento fallido (ver §5).

Cambia la clave cada 90 días y **de inmediato** si alguien que la conocía deja el equipo o si sospechas que se filtró.

---

## 4. Si olvidaste la clave

No hay recuperación por correo. Entra al servidor, genera una clave nueva, edítala en `local.properties` y reinicia.

---

## 5. Qué hace la app para protegerte

- **En producción no arranca** si la clave falta o tiene menos de 12 caracteres (o si `DASHBOARD_USER` está vacío).
- **En local sin clave solo escucha en `127.0.0.1`**, así que nadie de tu red puede abrir el tablero.
- **Bloqueo por intentos fallidos** (formulario y HTTP Basic comparten el mismo contador): 5 claves incorrectas en 5 minutos bloquean esa IP. Mientras dura el bloqueo la app responde
  `429 Too Many Requests` con la cabecera `Retry-After`, **aunque la clave sea correcta**. La espera es de 1 minuto y se duplica
  cada vez que se repite (2, 4, 8... hasta un máximo de 1 hora). Si pasa más de 1 hora sin intentos, se olvida el historial.
  Abrir el tablero por primera vez (sin credenciales) **no** cuenta como intento fallido.
- **Defensa CSRF:** los `POST` del tablero (incluido el login y el cierre de sesión) solo se aceptan si su `Origin` (o `Referer`) es el propio host,
  aunque lleven credenciales o cookie válidas; además la cookie es `SameSite=Strict`.
- **Roles:** `ADMIN` hace todo; el usuario de lectura solo consulta.
- **Comparación en tiempo constante** y la clave nunca se escribe en los registros.

### Si usas un proxy inverso (nginx, Caddy...)

- Define `TRUST_PROXY=true` **solo** si hay un proxy de confianza delante. Así el límite de intentos usa la IP real del cliente
  (`X-Forwarded-For`) y no la del proxy (si no, un atacante bloquearía a todos). Sin proxy déjalo en `false`.
- El proxy debe enviar la cabecera `Host` original (en nginx: `proxy_set_header Host $host;`) y, con `TRUST_PROXY=true`,
  `X-Forwarded-For` y `X-Forwarded-Host`. Si no, los `POST` del tablero se rechazan con 403.

Pendiente opcional: guardar `DASHBOARD_PASSWORD_HASH` (bcrypt o argon2) en lugar de la clave en texto plano, y usuarios con
nombre propio por persona en lugar de dos cuentas compartidas.
