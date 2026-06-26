 dumpsys does expose runtime "current HDR" state that the Java API doesn't. From your device (even the emulator shows the fields):

dumpsys display has a HdrBrightnessModifier: block with live state, not just capability:

mMode=NO_HDR — flips to HDR when an HDR layer is active
mIsHdrLayerPresent=false — "is an HDR layer on screen right now", the exact current-mode signal you're after
mHdrListener registered=false, mMaxDesiredHdrRatio
dumpsys SurfaceFlinger adds per-compositor state:

HWC Support: wideColorGamut=… hdr10=… hlg=… dv=… (hardware capability)
connected_display_hdr: true
Hdr Luminance Info: desiredMin/Max… (the negotiated HDR window luminance)
per-layer dumps carry HDR metadata for each active layer when one is present
So mIsHdrLayerPresent + mMode in dumpsys display is the closest thing to a definitive "we are in HDR right now" flag on the platform.

The catch is reachability:

From the app (InfoOverlay badge): not usable. These are DisplayManagerService internals — no public API exposes mIsHdrLayerPresent/mMode, and dumpsys can't be invoked from a normal app (needs shell/system uid or DUMP permission). So for the in-player badge, the content+display-capability heuristic remains the only option.
From the check-device skill (adb host side): usable. I can extend check-device.md to grep mIsHdrLayerPresent/mMode while a video is playing, so you get a definitive HDR-active verdict during testing rather than just capability.