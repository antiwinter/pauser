# OpenTune

Android TV client for **Emby** libraries and **SMB** file playback using **Media3 / ExoPlayer** and system **MediaCodec** decoders (no bundled FFmpeg).

## First-time setup

If you are new to Android development, follow **[docs/ENVIRONMENT_SETUP.md](docs/ENVIRONMENT_SETUP.md)** to install Android Studio, the SDK, and (optionally) a JDK for command-line builds.

After the environment is ready, open this folder in Android Studio and run the **app** configuration on an **Android TV** emulator or device.

## Command-line build (optional)

With JDK 17 on `PATH` or `JAVA_HOME` set:

```powershell
.\gradlew.bat assembleDebug
```

## License

Add a `LICENSE` file (for example Apache-2.0) before you publish the project publicly.


# TODOs
- [x] fix audio in background
- [ ] folder, single media folder, series
- [ ] sprite
- [ ] proxy server
- [ ] telegram, auto-proxy

## Player
- [ ] collapse imports
- [ ] check if same local cache used for smb subtitles as thumb
- [ ] emby resolve external
- [ ] srt/vss/ass as profile
- [ ] pagedown -> menu
- [ ] IME still shown
- [x] overlay control logic is verbose and messy and too stateful

1. Two views — necessary? What's the benefit?
                                                                                                                                                                                                                                              
  Honestly, no — it's not necessary right now. The split exists for a hypothetical future where you'd have a different PlayerView subclass for non-TV (e.g., phone/tablet), but the current codebase only has one: OpenTuneTvPlayerView.

  The current structure is:

  OpenTunePlayerView (Compose AndroidView wrapper)
    └── inflates layout → casts to PlayerView
         └── (view as? OpenTuneTvPlayerView) → configures TV-specific props

  The benefit in theory: If you later build a mobile/tablet PlayerView subclass, you can plug it in without touching the Compose wrapper. The AndroidView factory is decoupled from the TV-specific subclass.

  The cost: An extra layer of indirection, a fragile as? cast, and two files where one would suffice. OpenTunePlayerView doesn't add meaningful abstraction — it's essentially a configuration pass-through.

  My take: If you're only targeting Android TV (which this project clearly is, given the @tv imports), you could merge them. The Compose wrapper (OpenTunePlayerView) could directly reference OpenTuneTvPlayerView and the cast goes away.
  Keep the split only if you actually plan to support a different form factor with a different view subclass.

  ---
  2. Are key events handled in the view the right way?

  This is the correct pragmatic approach for Media3, but it's not ideal in a pure MVVM sense. Let me explain:

  Why it's done in the view:
  - Media3's PlayerView.dispatchKeyEvent() consumes DPAD events before they reach any setOnKeyListener — the only way to intercept is to subclass PlayerView and override dispatchKeyEvent. This is a Media3 framework quirk, not an
  architectural choice.
  - The transport logic (seek ±15s, play/pause) is tightly coupled to Player API calls (p.seekTo(), p.pause()), which need the player reference — the view already has it.

  The delegation pattern makes it work:
  OpenTuneTvPlayerView.dispatchKeyEvent()
    → if onDpadKey != null → delegate (overlay owns navigation)
    → else → do transport (seek, play/pause)

  This is essentially a chain of responsibility — the view handles the low-level key interception (which it must do because of Media3), but delegates the high-level routing to whoever set onDpadKey.

  The clean alternative would be to use a KeyEventModifier or a Focusable composable overlay that intercepts keys at the Compose level before they reach the AndroidView. But this doesn't work well with Android TV's focus system and
  Media3's view-internal key handling.

  My take: It's acceptable. The view handles input interception (a view-layer concern), but the navigation logic (which menu item, what action) lives in the Compose layer via callbacks. The coupling to Player for transport is a necessary
  evil from Media3's design. The main risk is that the view file has grown to 277 lines with domain logic mixed in (seek amounts, controller visibility, settings popup dismissal via reflection) — but most of that is Media3 plumbing rather
   than business logic.
