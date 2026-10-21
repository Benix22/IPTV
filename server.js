'use strict';
/*
 * Parrilla: servidor mínimo.
 * Sirve index.html y guarda lista, favoritos y recientes en SQLite.
 * Sin dependencias: usa node:sqlite (Node 22.13 o superior).
 *
 * Variables de entorno:
 *   PORT            puerto (3000)
 *   HOST            interfaz (127.0.0.1; usa 0.0.0.0 en contenedor)
 *   DATA_DIR        carpeta de la base de datos (./data)
 *   PARRILLA_TOKEN  si se define, la API exige "Authorization: Bearer <token>"
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '127.0.0.1';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const TOKEN = process.env.PARRILLA_TOKEN || '';
const MAX_BODY = 32 * 1024 * 1024;
const KEYS = new Set(['parrilla.text', 'parrilla.label', 'parrilla.fav', 'parrilla.recent']);
const INDEX = path.join(__dirname, 'index.html');

fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'parrilla.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS kv (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
  );
`);
const qAll = db.prepare('SELECT key, value FROM kv');
const qSet = db.prepare(
  'INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?) ' +
  'ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at'
);
const qDel = db.prepare('DELETE FROM kv WHERE key = ?');

function sha(s) { return crypto.createHash('sha256').update(s).digest(); }

function authorized(req) {
  if (!TOKEN) return true;
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  return !!m && crypto.timingSafeEqual(sha(m[1]), sha(TOKEN));
}

function send(res, code, body, type) {
  const data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(code, {
    'Content-Type': type || 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('Cuerpo demasiado grande'), { code: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;

    if (req.method === 'GET' && (p === '/' || p === '/index.html')) {
      return send(res, 200, fs.readFileSync(INDEX), 'text/html; charset=utf-8');
    }
    if (req.method === 'GET' && p === '/healthz') {
      return send(res, 200, { ok: true });
    }

    if (p.startsWith('/api/')) {
      if (!authorized(req)) return send(res, 401, { error: 'token requerido' });

      if (req.method === 'GET' && p === '/api/state') {
        const kv = {};
        for (const r of qAll.all()) kv[r.key] = r.value;
        return send(res, 200, { kv });
      }

      const m = /^\/api\/kv\/([^/]+)$/.exec(p);
      if (m) {
        const key = decodeURIComponent(m[1]);
        if (!KEYS.has(key)) return send(res, 400, { error: 'clave no permitida' });

        if (req.method === 'PUT') {
          let body;
          try { body = JSON.parse(await readBody(req)); } catch (e) {
            if (e.code === 413) return send(res, 413, { error: e.message });
            return send(res, 400, { error: 'JSON no válido' });
          }
          if (!body || typeof body.value !== 'string') return send(res, 400, { error: 'falta value (texto)' });
          qSet.run(key, body.value, Date.now());
          return send(res, 200, { ok: true });
        }
        if (req.method === 'DELETE') {
          qDel.run(key);
          return send(res, 200, { ok: true });
        }
      }
      return send(res, 404, { error: 'no encontrado' });
    }

    send(res, 404, { error: 'no encontrado' });
  } catch (e) {
    console.error(e);
    if (!res.headersSent) send(res, 500, { error: 'error interno' });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Parrilla en http://${HOST}:${PORT}  (datos en ${DATA_DIR})`);
  if (!TOKEN) {
    console.log('AVISO: sin PARRILLA_TOKEN la API es pública para quien llegue al puerto.');
    if (HOST !== '127.0.0.1' && HOST !== 'localhost') {
      console.log('AVISO: escuchas fuera de localhost sin token. Tu lista puede incluir usuario y contraseña del proveedor.');
    }
  }
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => { server.close(() => { db.close(); process.exit(0); }); });
}
