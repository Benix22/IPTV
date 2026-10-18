# Parrilla

Reproductor de listas IPTV (M3U/M3U8) en un único archivo HTML, sin build ni dependencias que instalar.

## Uso

1. Abre `index.html` en Chrome, Edge o Firefox (doble clic o servido por HTTPS).
2. Pulsa **Cargar lista** y sube tu archivo `.m3u`/`.m3u8`, pega su contenido o indica una URL.
3. Elige un canal en la parrilla.

La lista se guarda en el `localStorage` del navegador. No se envía nada a ningún servidor.

## Funciones

- Parser M3U con `tvg-logo`, `group-title` y `#EXTGRP`.
- Búsqueda sin acentos, filtro por grupo, favoritos y recientes.
- Reproducción HLS (`.m3u8`) con [hls.js](https://github.com/video-dev/hls.js) y MPEG-TS (`.ts`) con [mpegts.js](https://github.com/xqq/mpegts.js).
- Atajos: flechas arriba/abajo cambian de canal, `F` pantalla completa, `M` silencio, `Espacio` pausa, `/` buscar.

## Limitaciones del navegador

- Si el servidor de un canal no permite CORS, el navegador no lo reproducirá. Usa «Copiar enlace» y ábrelo en VLC.
- Desde una página `https` el navegador bloquea los canales `http`. Abierto en local no ocurre.
- La carga de la lista por URL también necesita CORS; si falla, sube el archivo.

Parrilla no incluye canales. Usa solo listas que tengas contratadas o autorizadas.

## Publicar

Es un sitio estático: sirve `index.html` desde Vercel, Netlify, GitHub Pages o cualquier hosting estático.
