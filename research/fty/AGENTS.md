# Research Agents & Local Sources

> Make sure to update/create relevant docs when we made new progress on our research.

## Local Source Code

FongMi/TV source code is available at `~/src/TV` (downloaded 2026-05-21).

Key files relevant to this research:

| File | Purpose |
|------|---------|
| `catvod/src/main/java/com/github/catvod/crawler/Spider.java` | The abstract Spider interface — the only contract between app and JAR |
| `app/src/main/java/com/fongmi/android/tv/api/loader/JarLoader.java` | DexClassLoader logic, Spider instantiation and caching |
| `app/src/main/java/com/fongmi/android/tv/api/loader/BaseLoader.java` | Top-level dispatcher: routes `api` field to JarLoader / JsLoader / PyLoader |
| `app/src/main/java/com/fongmi/android/tv/api/loader/JsLoader.java` | JS spider loader (drpy2/quickjs) |
| `app/src/main/java/com/fongmi/android/tv/api/loader/PyLoader.java` | Python spider loader (chaquo) |
