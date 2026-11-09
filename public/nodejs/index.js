const express = require('express');
const http = require('node:http');
const { Server } = require('socket.io');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const multer = require('multer');
const crypto = require('node:crypto');
const { app: nodeApp } = require('bridge');
const { createEsharexServer } = require('../shared/server-core.cjs');

const publicDir = path.resolve(__dirname, '..');
const dataDir = nodeApp.datadir();

const runtime = createEsharexServer({
  express,
  createHttpServer: http.createServer,
  SocketServer: Server,
  multer,
  crypto,
  path,
  os,
  fs,
  publicDir,
  dataDir,
  port: 8080
});

runtime.listen().catch(error => {
  console.error('Unable to start eShareX server:', error);
});
