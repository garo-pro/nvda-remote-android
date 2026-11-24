# NVDA Remote for Android

A minimal controller ("leader") for NVDA Remote Access, built against the protocol of NVDA 2026.3.1
(`source/_remoteClient`: TLS, newline-delimited JSON, protocol version 2, default port 6837).
It controls a Windows PC running NVDA; it does not make the phone controllable.

## Use
1. Enter server address, port (default 6837) and connection key, then press Connect. The address may also
   be written as `host:port` or `[IPv6 address]:port`, as in NVDA.
2. On the Windows side NVDA must be connected to the same server and key as a *controlled* computer.
3. Press **Ctrl+Shift+F11** on the USB or Bluetooth keyboard (or the "Start controlling" button) to send
   all keys to the PC. Press it again to return to the phone. Speech from NVDA is spoken by your screen
   reader (see Speech output below).
4. Back, Home, volume and media keys are never forwarded.

## Speech output
The **Speech output** button chooses who speaks NVDA's speech and the app's own messages:

| Choice | Voice, rate and volume | Notes |
| --- | --- | --- |
| Screen reader (default) | Those of the running screen reader: TalkBack, Jieshuo, Prudence or another | Also shown on the screen reader's braille display. If no screen reader is on, the default text-to-speech engine speaks until one is turned on. |
| Default text-to-speech engine | The engine chosen in Android's text-to-speech settings | Uses the app's speech rate slider. |
| A named engine (eSpeak NG, Vocalizer, Samsung, Google...) | That engine, even if it is not the Android default | Uses the app's speech rate slider. |

With the screen reader choice, NVDA's speech is handed to the screen reader as accessibility announcements
and NVDA's "stop speech" interrupts the screen reader. The app's speech rate slider is then disabled,
because the screen reader's own rate applies. TalkBack vibrates for each announcement when its vibration
feedback is on; turn that off in TalkBack's settings if it is distracting.

## Features
Keyboard forwarding (letters, digits, F1-F12, numpad, navigation, modifiers, Insert/CapsLock for the
NVDA key, Windows and Applications keys), speech through the screen reader or a chosen text-to-speech
engine, cancel/pause, NVDA beeps, clipboard in both directions,
Ctrl+Alt+Delete, automatic reconnect, trust-on-first-use pinning of self-signed server certificates
(SHA-256 fingerprint shown for confirmation, like NVDA). Braille is not supported.

The numeric keypad follows the phone's Num Lock state, like a Windows keyboard: with Num Lock off it sends
the keypad navigation keys, so NVDA's desktop-layout review commands (numpad 1-9, plus, minus...) work and
numpad Insert acts as the NVDA key; with Num Lock on it types digits.

## Build
Android Studio, or `./gradlew assembleDebug` with JDK 17 and the Android SDK (platform 35).
The included GitHub Actions workflow builds the APK as a downloadable artifact.
Install with `adb install app/build/outputs/apk/debug/app-debug.apk` or copy the APK to the phone.

## Notes
- The app must stay in the foreground to receive keyboard events; the screen is kept on while connected.
- Some Android systems consume Meta/Windows and Alt combinations before the app sees them.
