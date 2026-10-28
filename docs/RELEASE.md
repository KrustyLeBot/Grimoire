# Release, signing key, auto-update

## The signing key (one time)

Android only installs an update signed with the same key as the installed app: **never change it**,
never commit it.

```bash
keytool -genkeypair -v -keystore grimoire.jks -alias grimoire \
  -keyalg RSA -keysize 4096 -validity 36500 -storetype JKS
base64 -w0 grimoire.jks > grimoire.jks.b64        # macOS: base64 -i grimoire.jks -o grimoire.jks.b64
```

GitHub → Settings → Environments → `release` (deployment branches: `main` only), secrets:

| Secret | Value |
|---|---|
| `GRIMOIRE_KEYSTORE_BASE64` | content of `grimoire.jks.b64` |
| `GRIMOIRE_KEYSTORE_PASSWORD` | keystore password |
| `GRIMOIRE_KEY_PASSWORD` | key password (the same if keytool's default was kept) |

Keep `grimoire.jks` and both passwords somewhere safe (password manager): losing them means
uninstalling the app to install a build signed with a new key.

## CI

`.github/workflows/build-apk.yml`: unit tests, `assembleRelease` signed with the key above, the
Windows exe (pytest, then PyInstaller with `windows/grimoire.spec`: one file, only the parts of Qt
the app uses, stamped with the same build number), then the
rolling `latest` release with both files (deleted and recreated, title `Grimoire build N`,
N = run number = versionCode). On `main` a missing key fails the build; pull requests never see the
key and never publish.

## Auto-update

Windows: `windows/grimoire_win/services/updater.py` reads the same release, takes its `.exe`
(size and SHA-256 checked), swaps it for the running one and restarts.

`suite/SuiteUpdater` reads the `latest` release, compares its build number with the installed
versionCode, downloads the APK (size and SHA-256 checked, package name verified) and opens Android's
installer from the update banner.
