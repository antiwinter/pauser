# TODOs
- [x] fix audio in background
- [x] folder, single media folder, series
- [ ] sprite
- [ ] proxy server
- [ ] telegram, auto-proxy
- [-] collapse imports

## Player
- [x] pagedown -> menu
- [ ] merge infoOSD w/ playbackOverlay,when buffering: show playbackOverlay, allow pause
- [ ] next episode
- [ ] emby resolve external
- [ ] srt/vss/ass as profile
now, emby is hard coded to convert pgs to ass. and the return url has api token attach which result in incompatibility with exo player. and there code to manage this. 

I want to change the way to: 
1. for any provider (smb/emby/future ones), the player call resolve external, the provider in this function convert to a url that exo can work with
2. instead of madatorily convert pgs to ass, pass supported subtitle to setCapabilities, provider do convertion accordingly.

- [x] IME still shown
- [ ] investigate initialPositionMs
- [ ] check if same local cache used for smb subtitles as thumb
- [x] overlay control logic is verbose and messy and too stateful


8. CodecCapabilities interface may be undersized
The plan defines VideoCodecCapability(mimeType, maxWidth, maxHeight). But AndroidDeviceProfileBuilder maps to Emby's DeviceProfile which includes container formats, bitrate limits, audio channel caps, and profile/level conditions. Before finalizing CodecCapabilities, read AndroidDeviceProfileBuilder.kt and verify every field it passes to DeviceProfile is covered — or document what gets dropped and why.

Minor
MediaArt.DrawableRes(resId: Int) in CatalogContracts.kt — doesn't import Android types so it won't block kotlin("jvm") compilation, but providers on non-Android targets can never meaningfully produce it. Low priority, but worth noting as tech debt.
Steps 1 + 3 could be a single commit — both remove Context from provider-api (one from resolvePlayback, one from bootstrap). Splitting them means two intermediate broken states.
PlayerRoute.kt call site — inst.resolvePlayback(itemRefDecoded, resumeMs, app) passes the app Context. After Step 2, this third argument disappears. The plan mentions "callers to update" but doesn't name this file specifically.