# Emby MITM Proxy

HTTP/HTTPS proxy for debugging Emby client behavior (VidHub, Infuse Pro, etc.)

## Purpose

Intercept and log all HTTP requests between TV clients and the Emby server to understand:
- Request patterns during pause/resume operations
- Connection management strategies
- Headers and parameters used by working clients

## Setup

```bash
cd http-mitm
npm install
npm run build
```

## Usage

### Basic Usage

```bash
# Set target Emby server
export TARGET_HOST="http://192.168.1.100:8096"

# Start proxy (listens on port 8096 by default)
npm start
```

### Configuration

Environment variables:

- `PROXY_PORT` - Port to listen on (default: 8096)
- `TARGET_HOST` - Remote Emby server URL (required)
- `LOG_LEVEL` - `minimal` or `detailed` (default: detailed)

### Example

```bash
# Detailed logging
TARGET_HOST="http://192.168.1.50:8096" PROXY_PORT=8096 npm start

# Minimal logging
TARGET_HOST="http://192.168.1.50:8096" LOG_LEVEL=minimal npm start
```

## Client Configuration

Point your TV Emby client to use the proxy machine's IP:

```
Old: http://192.168.1.50:8096
New: http://192.168.1.10:8096  (proxy machine)
```

## What It Logs

- Request method, URL, timestamp
- Important headers (Range, User-Agent, X-Emby-Authorization)
- Response status, size, duration
- Errors and connection issues

## Local Request Bypass

Requests to local-only endpoints (like `/socket`) are automatically bypassed and not forwarded to the remote server.

## Comparing Clients

1. Start proxy pointing to your remote Emby server
2. Configure VidHub to use proxy
3. Play video → pause → resume → observe logs
4. Configure Infuse Pro to use proxy  
5. Repeat test → compare request patterns

Look for differences in:
- Request timing
- Range header usage
- Keep-alive behavior
- Session management calls
