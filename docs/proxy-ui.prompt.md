currently :app inject an httpClient to endpoint according to proxy settings. providers use this httpClient to 1. retrieve data; 2. return with playbackSpec so that player can use it for playback; 3. injected to js host.http.get/post/put/delete so js providers use the proxied http service transparently

new requirements:
1. remove validateFields from proxy providers, use client.test() same style as content providers.
2. inject proxyClient to content endpoint
3. proxyClient provide .getHttpClient() and .getConfig()
4. js provider should wrap host.http with proxy if provided, and pass proxyConfig via bootstrap data, so js providers can access the configuration if they need to build their own client (e.g. TDlib)
5. add a CtrlUI() to proxy provider contract. in home screen, user access CtrlUI() by click an endpoint, access edit page via menu modifier.
6. implement a new proxy provider: clash controller

clash controller:
fields: url, secret, name
CtrlUI: 
[button: refresh] [input: subscription url] [icon button: setting]
[lazy grid: grid of chips(proxy lines)]
refresh: fetch proxy lines from subscription url + test speed, update grid
input: 2-stage active input, same as :core:form
setting: navi to edit page
chip: line name (center), latency (small bottom right), click to set as active proxy, lite-colored bg according to latency (green <100ms, yellow <300ms, red >300ms). active proxy chip should be highlighted with a border.
