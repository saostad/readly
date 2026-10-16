<img src="assets/icon.svg" width="64" height="64" alt="">

# readly

Created in [T3 Code](https://t3.codes).

## Build

Prerequisites:

- JDK 17 (`/usr/bin/java` on the dev host)
- Android SDK with platform `android-34` and build-tools `36.1.0`; point Gradle
  at it with a `local.properties` file in the repo root (not committed):
  `sdk.dir=/home/saeid/Android/Sdk`

Build a debug APK (downloads the sherpa-onnx AAR on first run):

```sh
./gradlew assembleDebug
```

Install it on a connected device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For wireless debugging, pair then connect:
`adb pair <ip:port>` then `adb connect <ip:port>`.
