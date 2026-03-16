#   SwitchSynth

SwitchSynth is an Android Text-to-Speech (TTS) engine that allows you to use different voices for different scripts (Latin vs. Others).

## Features
- **Language Tab:**
  - View all supported languages from installed TTS engines.
  - Select/Deselect all.
  - Choose representative languages for Latin and non-Latin scripts.
- **Voices Tab:**
  - Choose specific voices for the selected representative languages.

## How to Build and Run
1. Ensure you have the Android SDK and a recent version of Gradle (or Android Studio) installed.
2. Open this folder in Android Studio OR run the following command in your terminal:
   ```bash
   gradle assembleDebug
   ```
3. Install the resulting APK:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## How to Activate
1. Open your Android device **Settings**.
2. Search for **Text-to-speech output**.
3. Select **Preferred engine** and choose **SwitchSynth**.
4. (Optional) Open the SwitchSynth app to configure your preferred languages and voices.

## Troubleshooting
- If no voices appear in the Voices tab, ensure you have at least one TTS engine (like Google Speech Services) installed and that it has voices for your selected languages.
- Script detection currently supports Basic Latin blocks vs. 