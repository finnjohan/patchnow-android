# PatchNow för Android

PatchNow visar bara de sårbarheter som behöver åtgärdas nu: de som utnyttjas aktivt och är kritiska, på de plattformar du har hand om. Det är Android-versionen av [PatchNow för Ubuntu Touch](https://github.com/finnjohan/patchnow), med samma regler.

*PatchNow shows only the vulnerabilities that need action now: actively exploited and critical, on the platforms you run. Data from CISA KEV, NVD, EPSS and CERT-SE.*

## Funktioner
- Hämtar CISA:s katalog Known Exploited Vulnerabilities (KEV), NVD (CVSS, CPE och CISA:s SSVC), EPSS från FIRST och CERT-SE:s flöde.
- Tre nivåer: Strikt, Normal och Bred.
- Plattformsfilter: Windows Server, Linux, serverprogram och hypervisorer som standard. Nätverksutrustning och datorer/mobiler går att slå på.
- Notis när något nytt blir kritiskt, med tyst tid 22–07.
- Kollar i bakgrunden varje timme, var 3:e eller var 6:e timme med WorkManager, även när appen är stängd.
- Svep åt höger för att markera som åtgärdad, åt vänster för att dölja en leverantör.
- Fungerar utan nät med senast sparade data.
- Engelska och svenska, ljust, mörkt eller automatiskt tema.

## Bygga
GitHub Actions bygger appen vid varje push (`.github/workflows/build.yml`):
1. Kör logiktesterna (`app/src/test`, samma fall som i Ubuntu Touch-versionen).
2. Bygger en testversion (`patchnow-<version>-debug.apk`) och, om signeringsnyckeln finns, en utgåva.
3. Startar en emulator (Android 14), tar skärmdumpar i demo-läget och kontrollerar att en notis visas.

APK:n och skärmdumparna finns under **Actions → senaste körningen → Artifacts**. En tagg som `v1.0.0` lägger APK:n under **Releases**.

Testversionen är signerad med en testnyckel som ligger i repot (`app/debug.keystore`), så att nya testversioner kan installeras ovanpå varandra. Se [docs/SIGNING.md](docs/SIGNING.md) för en riktig nyckel.

Lokalt med Android Studio: öppna mappen och välj **Run**. Utan Android Studio: `gradle :app:assembleDebug` (Gradle 8.9 eller senare, JDK 17).

## Installera
Ladda ned APK:n till telefonen och öppna den (tillåt installation från den appen). Eller med adb:
```bash
adb install -r patchnow-1.0.0-debug.apk
```

## Demo-läge (bara testversionen)
```bash
adb shell am start -n io.github.finnjohan.patchnow/.ui.MainActivity --ez demo true --es theme dark --es page detail --ei index 1
```
Extras: `demo`, `theme` (`light`/`dark`), `page` (`settings`/`detail`), `index`, `simulateNew` (skickar en notis).

## Struktur
| Fil | Innehåll |
|---|---|
| `logic/Logic.kt` | Adresser, tolkning (KEV, NVD, EPSS, Atom/RSS), plattform, bedömning. Ren Kotlin, testas med JUnit |
| `data/Repository.kt` | Hämtning, cache, sedda/åtgärdade, notiser |
| `data/Prefs.kt` | Inställningar (SharedPreferences) |
| `work/` | Bakgrundskoll (WorkManager) och notiser |
| `ui/` | Jetpack Compose: lista, detaljsida, inställningar |

## Licens
Copyright © 2026 Johan Finn. GNU General Public License v3.0 eller senare.

This product uses data from the NVD API but is not endorsed or certified by the NVD. EPSS: FIRST.org. KEV: CISA. PatchNow har ingen koppling till någon av källorna.
