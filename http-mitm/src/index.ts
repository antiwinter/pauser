import http from 'http';
import httpProxy from 'http-proxy';
import { URL } from 'url';

interface ProxyConfig {
  listenPort: number;
  targetHost: string;
  logLevel: 'minimal' | 'detailed';
}

const config: ProxyConfig = {
  listenPort: parseInt(process.env.PROXY_PORT || '8096'),
  targetHost: process.env.TARGET_HOST || 'http://your-emby-server:8096',
  logLevel: (process.env.LOG_LEVEL as 'minimal' | 'detailed') || 'detailed',
};

const proxy = httpProxy.createProxyServer({});

const formatHeaders = (headers: http.IncomingHttpHeaders): string => {
  const important = ['content-type', 'content-length', 'user-agent', 'range', 'x-emby-authorization'];
  return important
    .filter(key => headers[key])
    .map(key => `${key}: ${headers[key]}`)
    .join(', ');
};

const logRequest = (req: http.IncomingMessage) => {
  const timestamp = new Date().toISOString();
  console.log(`\n[${timestamp}] ${req.method} ${req.url}`);

  if (config.logLevel === 'detailed') {
    console.log(`Headers: ${formatHeaders(req.headers)}`);
  }
};

const logResponse = (proxyRes: http.IncomingMessage, req: http.IncomingMessage, startTime: number) => {
  const duration = Date.now() - startTime;
  const contentLength = proxyRes.headers['content-length'] || 'unknown';

  console.log(`← ${proxyRes.statusCode} ${req.url} (${duration}ms, ${contentLength} bytes)`);

  if (config.logLevel === 'detailed') {
    console.log(`Response Headers: ${formatHeaders(proxyRes.headers)}`);
  }
};

proxy.on('proxyReq', (proxyReq, req) => {
  const startTime = Date.now();
  (req as any).startTime = startTime;
  logRequest(req);
});

proxy.on('proxyRes', (proxyRes, req) => {
  const startTime = (req as any).startTime || Date.now();
  logResponse(proxyRes, req, startTime);
});

proxy.on('error', (err, req, res) => {
  console.error(`[ERROR] ${req.method} ${req.url}:`, err.message);
  if (res && 'writeHead' in res && !res.headersSent) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end('Proxy error: ' + err.message);
  }
});

const server = http.createServer((req, res) => {
  const localPatterns = [
    /^\/socket$/,
    /^\/emby\/socket$/,
  ];

  const isLocalRequest = localPatterns.some(pattern => pattern.test(req.url || ''));

  if (isLocalRequest) {
    console.log(`[BYPASS] Local request detected: ${req.url} - not proxying`);
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Local endpoint - bypassed by proxy');
    return;
  }

  proxy.web(req, res, {
    target: config.targetHost,
    changeOrigin: true,
  });
});

server.listen(config.listenPort, () => {
  console.log('='.repeat(60));
  console.log('Emby MITM Proxy Started');
  console.log('='.repeat(60));
  console.log(`Listening on: http://0.0.0.0:${config.listenPort}`);
  console.log(`Forwarding to: ${config.targetHost}`);
  console.log(`Log level: ${config.logLevel}`);
  console.log('\nConfigure your TV client to use this proxy as Emby server');
  console.log('='.repeat(60));
});

process.on('SIGINT', () => {
  console.log('\n\nShutting down proxy...');
  server.close();
  process.exit(0);
});
