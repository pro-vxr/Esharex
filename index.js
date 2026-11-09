import express from 'express';
import { createServer } from 'http';
import { Server } from 'socket.io';
import { fileURLToPath } from 'url';
import path from 'path';
import os from 'os';
import fs from 'fs';
import multer from 'multer';
import crypto from 'crypto';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const httpServer = createServer(app);
const io = new Server(httpServer);
const PORT = 8080;

const isAndroid = os.platform() === 'android';
const dataDir = isAndroid ? path.join(os.homedir(), 'esharex-data') : __dirname;
const uploadsDir = path.join(dataDir, 'uploads');

if (!fs.existsSync(uploadsDir)) {
    fs.mkdirSync(uploadsDir, { recursive: true });
}

app.use(express.static(path.join(__dirname, 'public')));
app.use('/uploads', express.static(uploadsDir));

const storage = multer.diskStorage({
    destination: function (req, file, cb) {
        cb(null, uploadsDir);
    },
    filename: function (req, file, cb) {
        cb(null, crypto.randomUUID() + path.extname(file.originalname));
    }
});

const upload = multer({ storage: storage });
const messages = [];

function getLocalIP() {
    if (isAndroid) {
        try {
            const nets = os.networkInterfaces();
            for (const name of Object.keys(nets)) {
                for (const net of nets[name]) {
                    if (net.family === 'IPv4' && !net.internal) return net.address;
                }
            }
        } catch (e) {}
        return '192.168.43.1';
    }
    const nets = os.networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === 'IPv4' && !net.internal) return net.address;
        }
    }
    return 'localhost';
}

app.get('/api/server-info', (req, res) => {
    const ip = getLocalIP();
    res.json({ ip: ip, port: PORT, address: `http://${ip}:${PORT}` });
});

app.post('/api/upload', upload.single('file'), (req, res) => {
    if (!req.file) return res.status(400).json({ success: false });
    let parsedReply = null;
    if (req.body.replyTo) {
        try { parsedReply = JSON.parse(req.body.replyTo); } catch (e) {}
    }
    const msg = {
        id: crypto.randomUUID(),
        type: 'file',
        url: '/uploads/' + req.file.filename,
        filename: req.file.originalname,
        mime: req.file.mimetype,
        username: req.body.username,
        caption: req.body.caption || '',
        replyTo: parsedReply,
        timestamp: Date.now()
    };
    messages.push(msg);
    io.emit('message', msg);
    res.json({ success: true });
});

io.on('connection', (socket) => {
    socket.emit('history', messages);
    socket.on('chat', (data) => {
        const msg = {
            id: crypto.randomUUID(),
            type: 'text',
            text: data.text,
            username: data.username,
            replyTo: data.replyTo || null,
            timestamp: Date.now()
        };
        messages.push(msg);
        io.emit('message', msg);
    });
});

httpServer.listen(PORT, '0.0.0.0', () => {
    console.log(`eShareX running at http://${getLocalIP()}:${PORT}`);
});

export default app;
