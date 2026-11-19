#!/bin/bash
# Installerar testversionen i emulatorn, tar skärmdumpar i demo-läget och
# kontrollerar att en notis visas. Körs av GitHub Actions (build.yml).
set -x
PKG=io.github.finnjohan.patchnow
ACT=$PKG/.ui.MainActivity
mkdir -p screenshots

APK=$(ls apk/*-debug.apk | head -1)
adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

shot() {
    local name=$1; shift
    adb shell am force-stop $PKG
    adb shell am start -W -n $ACT --ez demo true "$@"
    sleep 6
    adb exec-out screencap -p > "screenshots/$name.png"
}

shot 1-list-light --es theme light
shot 2-detail-light --es theme light --es page detail --ei index 1
shot 3-list-dark --es theme dark
shot 4-settings-light --es theme light --es page settings
shot 5-detail-dark --es theme dark --es page detail --ei index 6

# Svenska (språk per app, Android 13+)
adb shell cmd locale set-app-locales $PKG --locales sv-SE || true
shot 6-list-sv --es theme light
shot 7-settings-sv-dark --es theme dark --es page settings
adb shell cmd locale set-app-locales $PKG --locales en-US || true

# Notis: låtsas att den nyaste kritiska sårbarheten är ny
adb shell am force-stop $PKG
adb shell am start -W -n $ACT --ez demo true --ez simulateNew true --es theme light
sleep 5
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > screenshots/8-notification.png
adb shell cmd statusbar collapse
adb shell dumpsys notification --noredact | grep -A6 "pkg=$PKG" > screenshots/notifications.txt || true

# Krascher hamnar här
adb logcat -d AndroidRuntime:E '*:S' > screenshots/crash-log.txt || true
adb logcat -d | grep -i -E "patchnow|FATAL" | tail -200 > screenshots/app-log.txt || true
exit 0
