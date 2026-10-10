# Plantillas de WhatsApp Business — Fase 6.4

## Plantilla: `bienvenida_privacidad`

**Categoría:** UTILITY  
**Idioma:** es  

**Header (texto):** ¡Hola! Bienvenido/a a {{1}}

**Body:**
```
Hola {{2}} 👋, gracias por escribirnos.

Al continuar la conversación, aceptas que tratemos tus datos de contacto para atender tu solicitud de compra, de acuerdo con nuestra Política de Privacidad (Ley 1581/2012).

¿En qué producto estás interesado/a?
```

**Variables:**
- `{{1}}`: Nombre del comercio
- `{{2}}`: Nombre del cliente (si disponible) o "Cliente"

**Footer:** Puedes pedir más info sobre tus datos: [correo privacidad]

---

## Plantilla: `pago_confirmado`

**Categoría:** UTILITY  
**Idioma:** es  

**Body:**
```
✅ *Pago confirmado* para {{1}}.

Tu pedido de *{{2}}* por {{3}} ha sido verificado.

Procederemos con el envío en breve. Te avisaremos cuando tu paquete esté en camino.

¡Gracias por tu compra! 🎉
```

**Variables:**
- `{{1}}`: Nombre del cliente
- `{{2}}`: Nombre del producto
- `{{3}}`: Precio acordado

---

## Cómo registrar estas plantillas

1. En **Meta Business Manager** → WhatsApp → Plantillas de mensajes.
2. Crear nueva plantilla con el nombre exacto (`bienvenida_privacidad`, `pago_confirmado`).
3. Copiar el cuerpo del mensaje anterior.
4. Esperar aprobación de Meta (24–48 h).
5. Anotar el **nombre de la plantilla aprobada** en `local.properties` como:
   ```
   WA_TEMPLATE_BIENVENIDA=bienvenida_privacidad
   WA_TEMPLATE_PAGO_CONFIRMADO=pago_confirmado
   ```
