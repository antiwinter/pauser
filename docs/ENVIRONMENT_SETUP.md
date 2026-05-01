# Android development environment (Windows)

This project is a standard **Android Gradle** app. You can do almost everything from **Android Studio**; a separate JDK is optional if you only use the IDE.

## 1. Install Android Studio (required)

1. Download **Android Studio** from: [https://developer.android.com/studio](https://developer.android.com/studio)
2. Run the installer. Accept the default options unless you have a reason to change them.
3. On first launch, complete the **Setup Wizard** and let it download the **Android SDK**.

Android Studio includes a **JDK** used to build projects (often shown as **jbr** or **Embedded JDK**). You do **not** need to install Java separately for day-to-day work inside the IDE.

## 2. Open this project

1. Start Android Studio.
2. **File → Open** and select the `opentune` folder (the one that contains `settings.gradle.kts`).
3. When prompted, **Trust** the project.
4. Wait for **Gradle sync** to finish (status bar at the bottom). The first sync downloads dependencies and can take several minutes.

### Android SDK location (`local.properties`)

Gradle needs to know where the SDK is. Android Studio normally creates:

`local.properties`

in the project root, with a line like:

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

If sync fails with an SDK error, copy `local.properties.example` to `local.properties` and set `sdk.dir` to your SDK path (from Android Studio: **Settings → Languages & Frameworks → Android SDK → Android SDK Location**).

## 3. Install SDK packages (for this app)

In Android Studio: **Settings → Languages & Frameworks → Android SDK**

- **SDK Platforms** tab: install **Android 15** (API **35**) — matches `compileSdk` / `targetSdk`.
- **SDK Tools** tab: ensure **Android SDK Build-Tools**, **Android SDK Platform-Tools**, and **Android Emulator** are installed.

## 4. TV emulator (recommended for testing)

1. **Tools → Device Manager** (or **More Actions → Virtual Device Manager**).
2. **Create device** → category **TV** → pick a **Android TV** profile (e.g. 1080p).
3. Choose a **system image** with the same API level you installed (e.g. API 35 if available, or the closest TV image).
4. Finish the wizard, then **Run** the app and select this emulator.

For a physical **Android TV** or **Chromecast with Google TV**, enable **Developer options** and **USB debugging** (or wireless debugging) and connect the device; it should appear as a deployment target.

## 5. Run OpenTune

1. Select the **app** run configuration (usually `app`).
2. Choose your **TV emulator** or **physical device**.
3. Click **Run** (green triangle).

## 6. Command-line builds (optional)

If you want to run Gradle in **PowerShell** or **cmd** (e.g. `.\gradlew.bat assembleDebug`):

1. Install a **JDK 17** (e.g. [Eclipse Temurin 17](https://adoptium.net/) — use the MSI and accept the install).
2. Set **JAVA_HOME** to the JDK folder (e.g. `C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot`).
3. Add `%JAVA_HOME%\bin` to your **PATH**.

Alternatively, point Gradle at Android Studio’s bundled runtime (no separate JDK install):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

(Adjust the path if Android Studio is installed elsewhere.)

## 7. Troubleshooting

| Problem | What to try |
|--------|-------------|
| Gradle sync failed | Check internet; **File → Invalidate Caches / Restart**; confirm `local.properties` and `sdk.dir`. |
| No TV emulator | Install a TV system image in **SDK Manager**; create a device under **TV** in Device Manager. |
| `JAVA_HOME` / Java errors when using `gradlew.bat` | Set `JAVA_HOME` to JDK 17 or Android Studio `jbr` (see section 6). |

## What you installed (summary)

| Component | Role |
|-----------|------|
| **Android Studio** | IDE, SDK Manager, Emulator, Gradle sync, Run/Debug |
| **Android SDK** | Build tools, platform APIs, emulator images |
| **JDK 17** | Required for command-line `gradlew` if you do not use Studio’s `jbr` |

You can start with **only Android Studio** and add a standalone JDK later if you need terminal builds or CI.
