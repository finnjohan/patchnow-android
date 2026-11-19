# Lägga upp PatchNow i F-Droid

F-Droid bygger appen själv från källkoden på GitHub och signerar den med sin egen nyckel.
Ansökan görs som en merge request till repot `fdroiddata` på **gitlab.com** (inte KI:s GitLab).

## 1. Förbered repot (görs en gång)
- `fastlane/metadata/android/` innehåller titel, beskrivningar, ikon och skärmdumpar. F-Droid läser dem härifrån.
- `gradle/wrapper/gradle-wrapper.properties` talar om vilken Gradle-version F-Droid ska använda.
- Varje version ska ha en tagg: versionName `1.0.0` → taggen `v1.0.0`.

```bash
git tag v1.0.0
git push --tags
```

## 2. Skicka in
1. Skapa ett konto på https://gitlab.com och logga in.
2. Öppna https://gitlab.com/fdroid/fdroiddata och välj **Fork**.
3. I din fork: lägg till filen `metadata/io.github.finnjohan.patchnow.yml` med innehållet i `docs/io.github.finnjohan.patchnow.yml`
   (i webbläsaren: + → New file, eller klona och pusha en ny gren).
4. Skapa en **merge request** till `fdroid/fdroiddata`, välj mallen **App inclusion** och fyll i checklistan.
5. GitLab CI bygger appen automatiskt. Om något blir rött, rätta i forken. Granskarna svarar i merge requesten.

Det brukar ta från några dagar till några veckor.

## 3. Nya versioner
Höj `versionCode` och `versionName`, skriv `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
pusha och tagga (`v1.0.1`). F-Droid hittar taggen själv (`UpdateCheckMode: Tags`) och bygger.

## Att tänka på
- Appen hämtar data från CISA, NVD, FIRST och CERT-SE. Granskarna avgör om någon "anti-feature" ska anges.
- F-Droid-versionen har en annan signatur än testversionen från GitHub. Den som har testversionen måste avinstallera den först.
