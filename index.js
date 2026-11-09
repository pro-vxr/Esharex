import express from 'express';
import { createServer } from 'node:http';
import { Server } from 'socket.io';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import multer from 'multer';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { createEsharexServer } = require('./public/shared/server-core.cjs');
const projectDir = path.dirname(fileURLToPath(import.meta.url));

const runtime = createEsharexServer({
  express,
  createHttpServer: createServer,
  SocketServer: Server,
  multer,
  crypto,
  path,
  os,
  fs,
  publicDir: path.join(projectDir, 'public'),
  dataDir: projectDir,
  port: Number(process.env.PORT || 8080)
});

await runtime.listen();

export default runtime.app;
