# eShareX

## Build the Android app from GitHub Actions

The workflow creates a signed release APK. Android requires each update to keep the same application ID and signing key, so create one release key and keep using it for every build.

Create the key once on your computer and store it somewhere safe outside the repository:

```powershell
keytool -genkeypair -v -keystore esharex-release.jks -alias esharex -keyalg RSA -keysize 2048 -validity 10000
```

In PowerShell, convert the keystore file to Base64:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\esharex-release.jks")) | Set-Clipboard
```

In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** and add these secrets:

- `ANDROID_KEYSTORE_BASE64`: the clipboard contents
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password chosen above
- `ANDROID_KEY_ALIAS`: `esharex`
- `ANDROID_KEY_PASSWORD`: the key password chosen above

Keep the `.jks` file and passwords private. The workflow builds a higher `versionCode` on every run and uploads `app-release.apk` as the `eShareX-Release-APK` artifact.

The first release-signed APK cannot update an older APK signed with a different debug key. Uninstall that old app once before installing the release-signed APK; this removes the old app's local data. Updates from later workflow runs will use the saved signing key and can install over the previous release.
