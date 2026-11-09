function createEsharexServer({
  express,
  createHttpServer,
  SocketServer,
  multer,
  crypto,
  path,
  os,
  fs,
  publicDir,
  dataDir,
  port = 8080
}) {
  const app = express();
  const httpServer = createHttpServer(app);
  const io = new SocketServer(httpServer);
  const uploadsDir = path.join(dataDir, 'uploads');
  fs.mkdirSync(uploadsDir, { recursive: true });

  app.use(express.static(publicDir));
  app.use('/uploads', express.static(uploadsDir));

  const storage = multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, uploadsDir),
    filename: (_req, file, cb) =>
      cb(null, crypto.randomUUID() + path.extname(file.originalname))
  });

  const upload = multer({
    storage,
    limits: { fileSize: 1024 * 1024 * 1024 }
  });

  const messages = [];

  function getLocalIP() {
    const networks = os.networkInterfaces();
    for (const entries of Object.values(networks)) {
      for (const net of entries || []) {
        if (net.family === 'IPv4' && !net.internal) return net.address;
      }
    }
    return '127.0.0.1';
  }

  app.get('/api/health', (_req, res) => {
    res.json({ ok: true, port });
  });

  app.get('/api/server-info', (_req, res) => {
    const ip = getLocalIP();
    res.json({ ip, port, address: `http://${ip}:${port}` });
  });

  app.post('/api/upload', upload.single('file'), (req, res) => {
    if (!req.file) {
      return res.status(400).json({ success: false, error: 'No file received' });
    }

    let replyTo = null;
    try {
      if (req.body.replyTo) replyTo = JSON.parse(req.body.replyTo);
    } catch (_error) {}

    const message = {
      id: crypto.randomUUID(),
      type: 'file',
      url: '/uploads/' + req.file.filename,
      filename: req.file.originalname,
      mime: req.file.mimetype,
      username: String(req.body.username || 'Guest').slice(0, 40),
      caption: req.body.caption || '',
      replyTo,
      timestamp: Date.now()
    };

    messages.push(message);
    io.emit('message', message);
    res.json({ success: true, message });
  });

  io.on('connection', socket => {
    socket.emit('history', messages);

    socket.on('chat', data => {
      if (!data || typeof data.text !== 'string' || !data.text.trim()) return;

      const message = {
        id: crypto.randomUUID(),
        type: 'text',
        text: data.text.slice(0, 10000),
        username: String(data.username || 'Guest').slice(0, 40),
        replyTo: data.replyTo || null,
        timestamp: Date.now()
      };

      messages.push(message);
      io.emit('message', message);
    });
  });

  return {
    app,
    httpServer,
    io,
    listen() {
      return new Promise((resolve, reject) => {
        httpServer.once('error', reject);
        httpServer.listen(port, '0.0.0.0', () => {
          httpServer.removeListener('error', reject);
          console.log(`eShareX server listening on http://0.0.0.0:${port}`);
          resolve(httpServer);
        });
      });
    }
  };
}

module.exports = { createEsharexServer };
