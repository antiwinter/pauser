# TODOs

- [x] split contract into proxy/provider
- [-] what is EndpointConfigRepository?
- [-] StreamRegistrarHolder, PlatformInfoHolder
- [ ] error osd
- [x] move add server/ProviderFieldSpec/ValidationResult to utils.register
- [x] Storage naming unification
- [x] Fix: smb gen cover
- [x] check if same local cache used for smb subtitles as thumb
- [x] align server/source/provider terminology
- [x] fix audio in background
- [x] folder, single media folder, series
- [x] eliminate instance concept, rename provider methods
- [x] service worker like mechanism to map files/smb to url
- [x] customMediaSourceFactory
- [-] collapse imports
- [x] add version display
- [x] Fix: smb loading speed

- [x] split server from app, debug skill
- [x] proxy provider
- [ ] telegram, auto-proxy, extent add server screen & process
- [ ] ali, extent provider support: resolveUrl() => playbackSpec:  alipan.com/xxx

## Browser
- [ ] filter
- [x] detail screen entering rules
- [ ] right most alphabet quick jump
- [ ] recent
- [ ] merge browse/search, use filter to distinguish
- [ ] merge filename/type detector with mimedetector

## js providers
- [x] remove sidecar meta
- [x] research thread safe
- [x] tester on host
- [x] ts file arch
- [x] quickjs exclude std & os
- [ ] bundle/gzip, {fields, providesCover, js: 'string'}
- [ ] common.js

## Player
- [x] Opt: episode track selection should persist on series
- [x] Fix: subtitle position persistence
- [ ] Fix: subtitle should align to bottom vertially
- [ ] Fix: subtitle track/audio track not persistant or not applied on the next run
- [ ] keep 1min played buffer
- [x] Fix: smb slow buffering
- [x] Fix: 5a.mkv audio decoder
- [x] merge infoOSD w/ playbackOverlay
- [x] Opt: start play, don't show controller
- [x] Fix: menu scrolling, navi back
- [x] don't bringup posd when dismiss
- [x] Fix: playbackOSD two phase disappearing
- [x] Opt: subtitle bg
- [x] Fix: buffering pause
- [x] Fix: pause/continue slow buffering
- [x] pagedown -> menu
- [x] emby resolve external
- [x] srt/vss/ass as profile
- [x] IME still shown
- [x] investigate initialPositionMs
- [x] overlay control logic is verbose and messy and too stateful
- [ ] next episode
- [ ] sprite
- [ ] proxied buffering
- [ ] live surface
- [ ] external subtitle preload, system lang candidates, original lang reference line

## Roadmap
- [ ] remove opentune naming
- [ ] HDR support
- [-] release insomnia-x86_64-vx.x.x.apk
- [ ] pro user decoration. ICON/badge

## Providers
- [ ] clash controller
- [ ] tg
- [ ] xhs
- [ ] aliyun
- [ ] catvod