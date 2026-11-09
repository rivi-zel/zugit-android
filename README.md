# ZugIt (Android app)

Yiddish voice-to-text for Android: a floating mic that types what you say into any app. It also registers as a system voice-input option.

This repository contains the Android client only. It needs a transcription server, which you provide.

## Setup

1. Open `android/` in Android Studio (JDK 17, SDK 35).
2. Set your server address in `SettingsManager.kt` (`serverUrl`).
3. Replace the placeholders below.
4. Run the `app` configuration, or `./gradlew assembleDebug`.

## Server the app expects

| Request | Body | Response |
| --- | --- | --- |
| `POST /api/transcribe` | multipart form: `file` (audio), `user_email`, `user_name` | JSON: `{"text": "...", "duration": 1.23}` |
| `POST /api/warm` (optional) | empty | ignored; called when the keyboard opens |

## Placeholders to replace

| What | Where |
| --- | --- |
| Google sign-in client ID | `default_web_client_id` in `res/values/strings.xml` |
| Support email | `SUPPORT_EMAIL` in `SupportHelper.kt` |
| Privacy policy link | `OnboardingActivity.kt` |
| App ID, if you publish it | `applicationId` in `app/build.gradle.kts` |

Sign-in fails until you add your own client ID, but it can be skipped during onboarding.

For a signed release build, put your keystore at `android/app/release.keystore` and set the `KEYSTORE_PASSWORD` environment variable.
