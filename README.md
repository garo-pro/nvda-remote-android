# NVDA Remote for Android

A minimal controller ("leader") for NVDA Remote Access, built against the protocol of NVDA 2026.3.1
(`source/_remoteClient`: TLS, newline-delimited JSON, protocol version 2, default port 6837).
It controls a Windows PC running NVDA; it does not make the phone controllable.

## Use
1. Enter server address, port (default 6837) and connection key, then press Connect.
2. On the Windows side NVDA must be connected to the same server and key as a *controlled* computer.
3. Press **Ctrl+Shift+F11** on the USB or Bluetooth keyboard (or the "Start controlling" button) to send
   all keys to the PC. Press it again to return to the phone. Speech from NVDA is spoken by the phone's
   text-to-speech engine, using the accessibility volume stream.
4. Back, Home, volume and media keys are never forwarded.

## Features
Keyboard forwarding (letters, digits, F1-F12, numpad, navigation, modifiers, Insert/CapsLock for the
NVDA key, Windows and Applications keys), speech, cancel/pause, NVDA beeps, clipboard in both directions,
Ctrl+Alt+Delete, automatic reconnect, trust-on-first-use pinning of self-signed server certificates
(SHA-256 fingerprint shown for confirmation, like NVDA). Braille is not supported.

## Build
Android Studio, or `./gradlew assembleDebug` with JDK 17 and the Android SDK (platform 35).
The included GitHub Actions workflow builds the APK as a downloadable artifact.
Install with `adb install app/build/outputs/apk/debug/app-debug.apk` or copy the APK to the phone.

## Notes
- The app must stay in the foreground to receive keyboard events; the screen is kept on while connected.
- Some Android systems consume Meta/Windows and Alt combinations before the app sees them.
