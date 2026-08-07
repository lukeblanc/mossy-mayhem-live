# Mossy Mayhem Live

**Point. Watch. Laugh.**

An Android camera app that looks at the live camera feed, recognises the scene on-device with Google ML Kit, and speaks playful commentary. It can also record and share video clips.

## Version 0.1 features

- One-button welcome screen
- Camera starts immediately after permission is granted
- On-device image labelling with 400+ labels
- Spoken Australian-English commentary using Android Text-to-Speech
- Front/back camera switching
- Video recording with microphone audio
- Android share sheet for Facebook, Messenger, WhatsApp and other installed apps
- No camera frames uploaded to a server

## Build

GitHub Actions builds an installable debug APK on every push and pull request. Open the latest **Build Android APK** workflow run and download the `Mossy-Mayhem-Live-debug` artifact.

Local build with Gradle 8.13 and JDK 17:

```bash
gradle assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```
