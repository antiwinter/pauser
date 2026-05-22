# QR Code Login Research

## 1. Aliyun Drive (阿里云盘) QR Login

### Flow Overview

1. **Generate QR**: GET `/newlogin/qrcode/generate.do` → returns `{ codeContent, t, ck }`
2. **Display QR**: render `codeContent` (base64 PNG) as QR code image
3. **Poll status**: POST `/newlogin/qrcode/query.do` every ~1s with `t` + `ck`
4. **State machine**:
   - `NEW` → waiting for scan
   - `SCANED` → scanned, waiting for user to confirm on phone
   - `CONFIRMED` → proceed to token exchange (contains `bizAction.pds_login_result.accessToken`)
   - `EXPIRED` → abort, regenerate
   - `CANCELED` → abort
5. **Token exchange** (after CONFIRMED):
   - POST `https://auth.aliyundrive.com/v2/oauth/token_login` `{ token }` → `goto` URL with `?code=`
   - POST `https://api.aliyundrive.com/token/get` `{ code }` → `{ access_token, refresh_token, expires_in }`
6. **Persist**: `access_token` + `refresh_token` stored as fields

### Key Parameters

| Param | Description |
|---|---|
| `t` | Timestamp token, identifies the QR session |
| `ck` | Cookie/session token |
| `codeContent` | Base64-encoded QR image |
| `access_token` | Long-lived API token (expires in hours) |
| `refresh_token` | Used to obtain new `access_token` |

### Polling Interval
1 second. Token expires in ~5 minutes.

---

## 2. Telegram QR Login

### Flow Overview

All communication is over MTProto (Telegram's binary protocol).

1. **Get token**: `auth.loginToken` → returns a binary token + `dc_id`
2. **Encode QR**: token is base64-encoded and rendered as QR code (URL: `tg://login?token=<base64>`)
3. **Long-poll**: `auth.loginTokenWait` blocks until:
   - Mobile app scans and confirms → session created
   - Token expires (~60s) → request new token, regenerate QR
4. **Optional 2FA**: if account has two-step verification, `auth.checkPassword` must be called after QR confirmation
5. **Session established**: MTProto session is now active

### Key Differences vs Aliyun

| Aspect | Aliyun | Telegram |
|---|---|---|
| Transport | REST/HTTPS | MTProto (binary) |
| Token lifetime | ~5 minutes | ~60 seconds |
| Polling style | Active polling every 1s | Long-poll (server holds connection) |
| Status steps | NEW → SCANED → CONFIRMED | Just waiting → confirmed |
| Post-login | OAuth code exchange | Session directly active |

---

## 3. Common Patterns Across Both

1. **Two-phase UI**: display QR → wait for scan feedback → show confirmation state
2. **Polling loop**: client repeatedly checks status; UI reflects `NEW`/`SCANED`/`CONFIRMED`/`EXPIRED` states
3. **No user-entered credentials**: the flow is entirely driven by the QR scan — the field form must be replaced or augmented
4. **Result is still a credential map**: after login, the outcome is a set of tokens (`access_token`, `refresh_token`, etc.) equivalent to the `Map<String, String>` that `validateFields()` returns today
5. **Expiry/refresh**: tokens have limited lifetimes and need refresh; the existing architecture stores whatever `validateFields()` returns, so refresh tokens can be included in `fields`
