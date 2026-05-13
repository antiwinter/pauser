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
