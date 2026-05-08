# TODOs
- [x] fix audio in background
- [ ] folder, single media folder, series
- [ ] sprite
- [ ] proxy server
- [ ] telegram, auto-proxy

## Player
- [-] collapse imports
- [x] pagedown -> menu
- [ ] emby resolve external
- [ ] srt/vss/ass as profile
now, emby is hard coded to convert pgs to ass. and the return url has api token attach which result in incompatibility with exo player. and there code to manage this. 

I want to change the way to: 
1. for any provider (smb/emby/future ones), the player call resolve external, the provider in this function convert to a url that exo can work with
2. instead of madatorily convert pgs to ass, pass supported subtitle to setCapabilities, provider do convertion accordingly.

- [ ] IME still shown
- [ ] investigate initialPositionMs
- [ ] check if same local cache used for smb subtitles as thumb
- [x] overlay control logic is verbose and messy and too stateful
