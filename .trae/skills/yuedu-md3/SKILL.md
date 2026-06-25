---
name: "yuedu-md3"
description: "Project knowledge for the 悦读 (legado-with-MD3) Android app — a Compose + MD3 fork of Legado. Invoke whenever working on this project: building, debugging, adding features, fixing BUGs, modifying resources, app name, icons, or asking questions about its structure."
---

# 悦读 (legado-with-MD3) Project Knowledge

This skill captures the **verified, non-obvious facts** about the project so future agents don't re-discover them. **Read this first** before analyzing, building, or modifying anything in the project.

---

## 1. Project Identity

| Property | Value | Notes |
|---|---|---|
| **Display name** | 悦读 (Debug suffix: 悦读·D) | Was originally "Legado" / "阅读" |
| **Package (namespace)** | `io.legado.app` | Code under this package |
| **ApplicationId** | `io.legato.kazusa` | ⚠️ Note the typo: `legato` (not `legado`) |
| **Debug applicationId** | `io.legato.kazusa.debug` | `applicationIdSuffix = ".debug"` |
| **Version (debug)** | `3.26.13_debug` | `versionNameSuffix = "_debug"` |
| **Repository root** | `e:\New\yueduMD3\legado-with-MD3\` | |
| **Build script** | `e:\New\yueduMD3\build_and_install.ps1` | Verified PowerShell script |

---

## 2. Build Environment (Windows workstation, verified)

| Tool | Path / Value | Critical notes |
|---|---|---|
| **JAVA_HOME** | `C:\Program Files\Android\Android Studio\jbr` | **Must use this** — has working `jlink.exe` for AGP's JdkImageTransform. Other JDKs (e.g. 8, 17 standalone) are NOT installed. |
| **ANDROID_HOME** | `C:\Users\Squema-Mini\AppData\Local\Android\Sdk` | Also set `ANDROID_SDK_ROOT` to same path |
| **adb** | `C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe` | Not on PowerShell PATH by default |
| **aapt2** | `C:\Users\Squema-Mini\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe` | Use this for inspecting APKs |
| **Gradle version** | 9.4.1 (auto-downloaded on first run) | Wrapper at `gradle/wrapper/gradle-wrapper.properties` |
| **Connected device** | `MFH6USKFWWJZHUHA` (arm64-v8a) | USB debug, re-run `adb devices` if it drops |
| **TRAE sandbox** | Allows `C:\Users\Squema-Mini\AppData\Local\Android\Sdk` (configured by user) | Required for AGP to auto-install missing build-tools |

### Install `build-tools 36.0.0` (required by AGP 9.2.1)

Local SDK has 34.0.0 / 36.1.0 / 37.0.0 but AGP 9.2.1 demands **exactly 36.0.0**. AGP will auto-download from `dl.google.com` if:
1. TRAE sandbox is unblocked for the SDK path
2. Network can reach `dl.google.com` (manifest fetch can timeout)

---

## 3. Build Configuration (already verified to work)

| Setting | Value | Source |
|---|---|---|
| AGP | 9.2.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.3.21 | `gradle/libs.versions.toml` |
| KSP | 2.3.6 | `gradle/libs.versions.toml` (uses KSP, NOT KAPT) |
| compileSdk | 37 | `app/build.gradle.kts` (overrides root's 36) |
| minSdk | 26 | `app/build.gradle.kts` |
| targetSdk | 37 | `app/build.gradle.kts` |
| Java target | 21 | (JBR is the only JDK available) |

### gradle.properties — current workarounds (DO NOT REMOVE)

```properties
org.gradle.configuration-cache=true
org.gradle.daemon=false                          # Single-use Daemon
ksp.incremental=false                            # ⚠️ CRITICAL: KSP 2.x cross-drive bug workaround
```

**`ksp.incremental=false`** is required because the project lives on `E:\` but Gradle cache is on `C:\`. KSP 2.x throws `IllegalArgumentException: this and base files have different roots` in incremental mode across drives. Removing this re-introduces the crash.

---

## 4. ⚠️ Resource Override Mechanism (KEY DISCOVERY)

**AGP buildType-specific resources override main resources.** The project uses this for the `·D` debug suffix:

| Path | Purpose | Overrides |
|---|---|---|
| `app/src/main/res/values/strings.xml` | Base strings | — |
| `app/src/debug/res/values/strings.xml` | Debug-build-specific strings | Overrides `app_name` in main for debug builds |
| `app/src/debug/res/values-zh-rCN/strings.xml` | Same, Chinese | Overrides `app_name` in main for debug zh-CN |

**Current content of `app/src/debug/res/values/strings.xml`:**
```xml
<resources>
    <string name="app_name">悦读·D</string>
    <string name="receiving_shared_label">悦读·D·搜索</string>
</resources>
```

**When renaming the app or changing any string, ALWAYS update BOTH main and debug (and release if it exists) res directories.** AAPT2 dump will show the `legado·D` / `阅读·D` value if you forget the debug override — don't be fooled, it's a feature not a bug.

### Release vs Debug naming convention
- **Debug**: `悦读·D` (the `·D` suffix marks the build as Debug to avoid installing over the release version)
- **Release**: `悦读` (no suffix)
- `Restore.kt:331` uses this suffix to switch launcher icons

---

## 5. AndroidManifest Placeholder Pattern

The manifest uses `${app_name}` placeholder, replaced by `app/build.gradle.kts`:

```kotlin
manifestPlaceholders["app_name"] = "@string/app_name"
```

The resource reference is then resolved at compile time to the merged string value. AGP does NOT add a `·D` suffix automatically — that's done via the `app/src/debug/res/` resource override (see section 4).

---

## 6. Build / Install / Verify Commands

All commands assume `e:\New\yueduMD3\` is the working directory.

### Build + Install (preferred, uses the verified script)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "e:\New\yueduMD3\build_and_install.ps1"
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s "MFH6USKFWWJZHUHA" install -r -t "e:\New\yueduMD3\legado-with-MD3\app\build\outputs\apk\app\debug\app-app-arm64-v8a-debug.apk"
```

The `build_and_install.ps1` script:
- Sets `JAVA_HOME` / `ANDROID_HOME` correctly
- Runs `gradlew.bat :app:assembleDebug --no-daemon`
- Captures full log to `e:\New\yueduMD3\build_output.log`

### Inspect APK metadata

```powershell
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe" dump badging "e:\New\yueduMD3\legado-with-MD3\app\build\outputs\apk\app\debug\app-app-arm64-v8a-debug.apk"
```

Look for `application-label` (per-locale) and `application: package=...`.

### Launch on device

```powershell
& adb -s MFH6USKFWWJZHUHA shell monkey -p io.legato.kazusa.debug -c android.intent.category.LAUNCHER 1
```

### First-build time budget

- First build (cold): 5–10 min (downloads Gradle 9.4.1, AGP, dependencies)
- Incremental build (no resource change): ~10–13 s
- Resource-only change (strings/icons): ~11 s, only 9 tasks re-execute

---

## 7. Module Structure

```
legado-with-MD3/
├── app/                          # Main app module (com.android.application)
│   ├── build.gradle.kts          # Has the resource override + manifestPlaceholders
│   └── src/main/
│       ├── java/io/legado/app/   # All app code (Compose, ViewModel, etc.)
│       ├── res/                  # Main resources
│       └── assets/web/help/md/   # Help Markdown files
├── modules/
│   ├── book/                     # Library module (book parsing)
│   └── rhino/                    # Library module (JS engine)
├── baselineprofile/              # Baseline profile module
├── settings.gradle               # Includes :app, :modules:book, :modules:rhino, :baselineprofile
└── gradle/libs.versions.toml     # Version catalog
```

**Compose is everywhere.** Look for `@Composable` functions; legacy XML layouts only exist where required by platform (e.g. some system UIs).

---

## 8. Code Conventions (verified across the codebase)

| Convention | Example |
|---|---|
| **MVI pattern** | `*Contract.kt` defines `*State` / `*Intent` / `*Effect`; `*ViewModel.kt` processes Intents; `*Controller.kt` (if exists) consumes Effects |
| **Compose** | Material 3 + Expressive APIs (M3 Expressive 1.0+); uses `LocalContext`, `LocalHapticFeedback`, `Activity.showDialogFragment(...)` for View interop |
| **DI** | Koin (`koinViewModel()`, `koin-androidx-compose`) |
| **Coroutines** | `kotlinx.coroutines` (Flow, StateFlow, SharedFlow); `appCtx.longToastOnUi(...)` extension |
| **Logging** | Timber / `appCtx.toastOnUi` / `appCtx.longToastOnUi` (splitties.init) |
| **Reactive DB** | Room with KSP code-gen (not KAPT) |
| **No code comments** | Project style: no `//` or `/* */` comments in production code unless requested |
| **String resources** | Always in `strings.xml`; never hardcoded user-facing strings in Kotlin |
| **Imports** | Stdlib first, then Android, then third-party, then project (IntelliJ default) |
| **kotlinx-coroutines** | Always import `withContext`, `Dispatchers`, etc. explicitly |
| **TODO comments** | Common in MVI migrations; `is Foo -> { /* TODO */ }` patterns indicate dead code to clean up |

### Icon design rules (verified, see section 12 pitfalls)

1. **Background MUST be 100% opaque, full canvas, no rounded-corner alpha mask.** Launchers interpret transparent regions as "show the system background color" (often white). The launcher's own mask (circle, squircle, teardrop) will be applied on top — the background just needs to fill the entire canvas with solid pixels.
2. **All meaningful content (text, book, bookmark) must be inside the inner 60% of the canvas.** The outer 40% can be clipped by launcher masks on Android 8+ and especially on MIUI/EMUI/OneUI launchers.
3. **For adaptive icons (Android 8+), foreground image is on a transparent background** — only the inner ~66% of the 108x108dp canvas is the "safe zone". The launcher color/mask lives in the background layer.

---

## 9. Known BUGs Already Fixed (don't re-investigate)

These were identified and fixed on 2026-06-11. The fixes are in the working tree. Do not "discover" them again.

| # | File | Issue | Fix |
|---|---|---|---|
| 1 | [RegexExtensions.kt:69-78](file:///e:/New/yueduMD3/legado-with-MD3/app/src/main/java/io/legado/app/utils/RegexExtensions.kt#L69-L78) | `appCtx.restart()` 3 s after regex timeout forced full app restart | Removed the `appCtx.restart()` + `handler.postDelayed(3000)`. Exception now propagates to `ContentProcessor.kt:180` / `BookChapter.kt:129` which auto-disable the rule. Toast message changed to "已自动跳过该规则". |
| 3 | [ThemeImportExport.kt](file:///e:/New/yueduMD3/legado-with-MD3/app/src/main/java/io/legado/app/help/config/ThemeImportExport.kt) | `content://` URIs (SAF-picked files) silently dropped when exporting theme assets | Extracted `readAssetBytes(path)` helper that uses `appCtx.contentResolver.openInputStream(...)` for `content://` and `File.readBytes()` otherwise. Both `exportAssets()` and `exportCoverAssets()` use it. |
| 4 | [ReplaceRuleScreen.kt:351-354](file:///e:/New/yueduMD3/legado-with-MD3/app/src/main/java/io/legado/app/ui/replace/ReplaceRuleScreen.kt#L351-L354) | "帮助" menu item only closed the menu, did nothing | Added `showRegexHelp(context)` suspend function that reads `assets/web/help/md/regexHelp.md` and shows via `TextDialog(title, content, Mode.MD)` (positional args, NOT `text=...`). |
| 2 | [ReadBookContract.kt](file:///e:/New/yueduMD3/legado-with-MD3/app/src/main/java/io/legado/app/ui/book/read/ReadBookContract.kt), [ReadBookViewModel.kt](file:///e:/New/yueduMD3/legado-with-MD3/app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt), [ReadBookController.kt](file:///e:/New/yueduMD3/legado-with-MD3/app/src/main/java/io/legado/app/ui/book/read/ReadBookController.kt) | `ToggleBrightnessAuto` Intent/Effect was dead code (no UI trigger) | Removed from Contract (Intent + Effect), ViewModel dispatch, and Controller handler. |

---

## 10. App Name and Icon — Current State (after 2026-06-11)

| Field | Value | File |
|---|---|---|
| `app_name` (default) | `悦读` | `app/src/main/res/values/strings.xml` |
| `app_name` (zh-CN) | `悦读` | `app/src/main/res/values-zh-rCN/strings.xml` |
| `app_name` (zh-HK) | `悦读` | `app/src/main/res/values-zh-rHK/strings.xml` |
| `app_name` (zh-TW) | `悦读` | `app/src/main/res/values-zh-rTW/strings.xml` |
| `app_name` (debug) | `悦读·D` | `app/src/debug/res/values/strings.xml` |
| `app_name_a` | `悦读·A` | `app/src/main/res/values*/strings.xml` |
| `receiving_shared_label` | `悦读·搜索` (or `悦读·D·搜索` in debug) | `app/src/main/res/values*/strings.xml` + `app/src/debug/res/values*/strings.xml` |
| `ic_launcher_background` | `#4C475F` | `app/src/main/res/values/colors.xml` |
| Adaptive icon foreground | `drawable/ic_launcher_foreground.png` (432×432) | Replaces former XML vector |
| Adaptive icon foreground (monochrome) | `drawable/ic_launcher_foreground_m.png` | Replaces former XML vector |
| Legacy icons | `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.webp` + `ic_launcher_round.webp` | All 5 densities, generated via Python+PIL |

The icon design is: dark gray-purple gradient rounded square background, white "悦读" calligraphy (Source Han Serif SC Heavy), open book illustration, orange bookmark. **Python generation script** at `e:\New\yueduMD3\generate_icons.py` (kept at repo root for re-generation).

---

## 11. Pre-flight Checklist Before Any Build

Run these quick checks to avoid re-discovering the same environment issues:

1. **Java 21 + jlink.exe present?**
   ```powershell
   Test-Path "C:\Program Files\Android\Android Studio\jbr\bin\jlink.exe"
   ```
   If false, point `JAVA_HOME` to the JBR.

2. **`build-tools 36.0.0` installed?**
   ```powershell
   Test-Path "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt2.exe"
   ```
   If false, the build will try to auto-install. Make sure TRAE sandbox allows the SDK path.

3. **Device connected?**
   ```powershell
   & "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
   ```
   Should show `MFH6USKFWWJZHUHA device`. If empty, user needs to enable USB debugging.

4. **AAPT link errors?** Search for `colorBackground` (must be `android:colorBackground`), `colorSurface`, `colorAccent` missing prefixes in `res/values/styles.xml`. Search for `android:cornerRadius` (minSdk 26 < API 31 requirement).

5. **Resource override check** — if AAPT2 shows old `app_name` values, look in `app/src/{debug,release}/res/` for overrides.

---

## 12. Common Pitfalls (avoid these)

| Pitfall | Why | Solution |
|---|---|---|
| `&&` in PowerShell | PowerShell 5.x doesn't support `&&` | Use `;` or run via `.ps1` script |
| Empty PowerShell command output | Some `Get-ChildItem` patterns return silently | Use `Format-Table` or `ForEach-Object { $_.Name }` |
| `ToggleBrightnessAuto` looks like a BUG | It's dead code from MVI migration, NOT a broken feature | Clean it up, don't try to "fix" it |
| `${app_name}` in manifest shows old value | The actual `app_name` may be in `app/src/{buildType}/res/` | Always check buildType-specific res dirs |
| Cross-drive KSP crash | Project E:\ + cache C:\ | Keep `ksp.incremental=false` in gradle.properties |
| Build takes 10 min first time | Gradle 9.4.1 + AGP download | Normal, only happens once |
| `TextDialog(title, text=..., mode=...)` fails to compile | Parameter is `content`, not `text` | Use positional: `TextDialog(title, content, mode)` |
| `dumpsys package <pkg> \| grep label` returns nothing | `dumpsys` doesn't show applicationLabel in many Android versions | Use `aapt2 dump badging` instead |
| AGP installs missing build-tools 36.0.0 | AGP 9.2.1 hard requirement, no `buildToolsVersion` override available | Allow TRAE sandbox for SDK path; ensure dl.google.com is reachable |
| `Restore.kt:331` switches launcher icon | `Restore.kt` uses the `legado·D` / `阅读·D` suffix pattern to identify debug build | If you change the `·D` convention, update `Restore.kt:331` too |
| **Icon background appears as white on the launcher** | The legacy `mipmap-*/ic_launcher.webp` had **alpha channel** with rounded corners transparent. Launchers fill transparent regions with the system background (typically white) — your dark gradient design disappears. | **Generate the background 100% opaque**, filling the entire canvas. The launcher's own shape mask (circle/squircle) will be applied on top, but only after your background pixels are there. |
| **Icon content gets clipped** by launcher | Adaptive icon mask + legacy launcher mask crop the outer ~33-40% of the canvas. Content too close to the edges (book, text, bookmark) gets cut off. | Place all meaningful content in the **inner 60%** of the canvas (the "safe zone"). The outer 40% can be sacrificed to the launcher mask. |
| **WEBP loses alpha channel** | PIL's `save(path, "WEBP", ...)` may not always preserve alpha. | Use `lossless=True` for icons with transparency (adaptive foreground), `lossless=False` + high `quality` for opaque legacy icons. Always set `method=6`. |

## 🔴 RED LINES — Never violate (added 2026-06-11 after user complaint)

1. **NEVER redraw, recreate, or "approximate" an image the user provided.** The model can *see* attached images, but **cannot** extract their binary data to disk. If the user uploads a PNG and expects it to be used as an icon/asset, do NOT fall back to PIL + system fonts. **Stop and ask the user to save the file to a known path.** This rule is non-negotiable and supersedes any "I can recreate it" reasoning.
2. **NEVER claim a regenerated icon "matches the user's design"** when it clearly doesn't. If you cannot use the source file, you cannot fulfill the request — say so.

---

## 13. Project-Specific Scripts (in `e:\New\yueduMD3\`)

| Script | Purpose |
|---|---|
| `build_and_install.ps1` | Sets env vars + runs `assembleDebug` + shows last 80 lines on failure |
| `clean_and_build.ps1` | `gradlew clean --no-daemon` + `assembleDebug --rerun-tasks` + aapt2 inspection |
| `generate_icons.py` | Python+PIL: generates all icon variants from inline design |
| `install_icons.py` | Replaces the existing icons with the `_new` versions, cleans up |
| `build_output.log` | Last build's full log (overwritten each run) |
| `aapt2_dump.txt` | Last aapt2 dump output (overwritten each run) |

These are not part of the project repo (lives at `e:\New\yueduMD3\`, not `legado-with-MD3\.trae\`); they can be deleted, regenerated, or moved.

---

## 14. When This Skill Should Be Invoked

Trigger this skill when the user:

- Asks to build / install / debug the 悦读 app
- Reports a BUG and asks for a fix
- Wants to rename, re-icon, or rebrand the app
- Asks about the project structure, modules, or dependencies
- Wants to add a new feature
- Sees a confusing build error that might relate to environment (KSP, AAPT, build-tools, sandbox)
- Mentions: `legado-with-MD3`, `悦读`, `legato.kazusa`, "the reader app", "my reading app"

**Do not trigger** for unrelated questions or for generic Android development that doesn't involve this specific project.
