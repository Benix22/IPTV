# Parrilla

Reproductor de listas IPTV (M3U/M3U8). Funciona de dos formas:

- **Solo `index.html`**: sin servidor ni instalación. Los datos quedan en el `localStorage` del navegador.
- **Con `server.js`**: un servidor de Node con SQLite que guarda la lista, los favoritos y los recientes, para verlos igual desde cualquier navegador o dispositivo.

La página detecta sola si hay servidor. Si no lo hay, se comporta como siempre.

## Uso

1. Abre la página y pulsa **Cargar lista**.
2. Sube tu `.m3u`/`.m3u8`, pega su contenido o indica una URL.
3. Elige un canal en la parrilla.

El indicador de la cabecera muestra dónde se guardan tus datos: «Guardado en servidor» o «Solo este navegador».

## Funciones

- Parser M3U con `tvg-logo`, `group-title` y `#EXTGRP`.
- Búsqueda sin acentos, filtro por grupo, favoritos y recientes.
- Reproducción HLS (`.m3u8`) con [hls.js](https://github.com/video-dev/hls.js) y MPEG-TS (`.ts`) con [mpegts.js](https://github.com/xqq/mpegts.js).
- Atajos: flechas arriba/abajo cambian de canal, `F` pantalla completa, `M` silencio, `Espacio` pausa, `/` buscar.

## Con base de datos

Requiere Node 22.13 o superior. No tiene dependencias que instalar.

```
PARRILLA_TOKEN=cambia-esto npm start
```

Abre http://127.0.0.1:3000. La base de datos queda en `./data/parrilla.db`.

| Variable | Por defecto | Qué hace |
| --- | --- | --- |
| `PORT` | `3000` | Puerto. |
| `HOST` | `127.0.0.1` | Interfaz. Usa `0.0.0.0` para abrirlo a la red. |
| `DATA_DIR` | `./data` | Carpeta de la base de datos. |
| `PARRILLA_TOKEN` | vacío | Si se define, la API exige ese token. La página lo pide la primera vez. |

Con Docker:

```
docker build -t parrilla .
docker run -d --name parrilla -p 3000:3000 -v parrilla-data:/data -e PARRILLA_TOKEN=cambia-esto parrilla
```

### Cómo se sincroniza

- Al abrir la página, manda el servidor. Si el servidor está vacío y el navegador tiene datos, se suben una vez.
- Cada cambio (cargar lista, favorito, canal visto) se envía al servidor y también se guarda en el navegador.
- Si dos navegadores cambian a la vez, gana el último en guardar. Recarga para ver los cambios del otro.
- La base de datos es única: quien tenga el token ve los mismos datos. No hay cuentas de usuario.

### Seguridad

La lista guardada suele contener tu usuario y contraseña del proveedor. Con la base de datos:

- Define siempre `PARRILLA_TOKEN` si escuchas fuera de `127.0.0.1`.
- Ponlo detrás de HTTPS (por ejemplo con Caddy o nginx), porque el token y la lista viajan en cada petición.
- No publiques el contenido de `data/`. Ya está en `.gitignore`.

### API

Todas las rutas con `Authorization: Bearer <token>` si hay token.

- `GET /api/state` devuelve `{ "kv": { clave: valor } }`.
- `PUT /api/kv/<clave>` con `{ "value": "texto" }`.
- `DELETE /api/kv/<clave>`.
- `GET /healthz` no necesita token.

Claves permitidas: `parrilla.text`, `parrilla.label`, `parrilla.fav`, `parrilla.recent`.

## Limitaciones del navegador

- Si el servidor de un canal no permite CORS, el navegador no lo reproducirá. Usa «Copiar enlace» y ábrelo en VLC.
- Desde una página `https` el navegador bloquea los canales `http`. Abierto en local, o servido por `http` desde tu red, no ocurre.
- La carga de la lista por URL también necesita CORS; si falla, sube el archivo.

Parrilla no incluye canales. Usa solo listas que tengas contratadas o autorizadas.

## Publicar

- **Solo `index.html`**: cualquier hosting estático (Vercel, Netlify, GitHub Pages). Aquí los datos siguen siendo locales al navegador.
- **Con base de datos**: necesita un host donde corra Node y haya disco persistente (un VPS, un contenedor con volumen). En Vercel el disco es efímero, así que SQLite no persiste; haría falta cambiar la base de datos por una externa.
