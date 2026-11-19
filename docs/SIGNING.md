# Signera utgåvor

Testversionen från GitHub signeras med testnyckeln i `app/debug.keystore`. Den är offentlig och ska aldrig användas för en riktig utgåva.

## Skapa en egen nyckel (en gång)
```bash
keytool -genkeypair -v -keystore patchnow-release.jks -alias patchnow \
    -keyalg RSA -keysize 4096 -validity 10000
```
Spara filen och lösenorden på ett säkert ställe. **Tappar du nyckeln kan appen inte uppdateras** – då måste alla avinstallera och installera om.

## Lägg in den i GitHub
Repot → Settings → Secrets and variables → Actions → New repository secret:

| Namn | Värde |
|---|---|
| `PATCHNOW_KEYSTORE_BASE64` | `base64 -w0 patchnow-release.jks` |
| `PATCHNOW_KEYSTORE_PASSWORD` | lösenordet till filen |
| `PATCHNOW_KEY_ALIAS` | `patchnow` |
| `PATCHNOW_KEY_PASSWORD` | lösenordet till nyckeln |

Lägg aldrig `.jks`-filen i repot.

## Ge ut en version
1. Höj `versionCode` och `versionName` i `app/build.gradle.kts` och skriv en rad i `CHANGELOG.md` och `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. `git tag v1.0.1 && git push --tags`
3. GitHub bygger `patchnow-1.0.1.apk` (signerad) och lägger den under Releases.

Byter man från testversionen till den signerade utgåvan måste testversionen avinstalleras först, eftersom nycklarna skiljer sig.
