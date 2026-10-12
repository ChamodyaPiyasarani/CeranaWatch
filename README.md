# CeranaWatch

**A low-cost sound-based machine learning and IoT system to help Sri Lankan beekeepers monitor honeybee (*Apis cerana*) colonies.**

Final-year research project, BSc (Hons) Computer Science, National Institute of Business Management (NIBM) with Coventry University.
Author: Chamodya Piyasarani.

CeranaWatch has four parts:

1. **Android app (Sinhala and English)** - checks a colony from in-hive bee sound. The hive check runs fully on the phone, without internet.
2. **Hive device "CeranaNode" (ESP32)** - listens for bee sound, reads temperature and humidity, and alerts the beekeeper when a colony departs from its own normal pattern.
3. **Firebase Realtime Database** - links the device and the app (readings, alerts, commands).
4. **AI assistant** - explains readings and advice in plain words. It never diagnoses.

## What is in this repository

| Folder | Contents |
| --- | --- |
| `app/` | Android app (Kotlin, Jetpack Compose). YAMNet bee-sound gate, strength estimate, own-normal comparison, on-phone learning, assistant |
| `device/CeranaNode/` | ESP32 program, version 2.3 (INMP441 microphone, DHT22 sensor, buzzer) |
| `device/DHT22_Test/` | Small test program for the DHT22 sensor |
| `device/*.png` | Wiring diagrams and the 5 V step-down module |
| `server/` | Small Cloudflare worker that keeps the AI service key out of the app |
| `results_notebook/` | Results notebook (with and without outputs) and the device feature file |
| `thesis_figures/` | Figures used in the thesis |
| `logo/` | App logo |

## Main results (from the thesis)

- Colony-strength accuracy fell from 0.88 on a random split to 0.55 on unseen hives (majority baseline 0.33): most of the apparent accuracy came from recognising the hive.
- The bee-sound gate blocked 97% of non-bee sounds (old rule: 55%) and kept 54% of bee clips.
- At a live hive the device labelled all 58 readings as bee; after on-site teaching it labelled none of 13 indoor readings as bee.

## Build the Android app

1. Open the project folder in Android Studio.
2. Copy `secrets.properties.example` to `secrets.properties` (next to `settings.gradle.kts`) and fill in your own values. This file is private and is never uploaded.
3. Press **Run**. Minimum Android version: 7.0 (API 24).

## Build the hive device

1. Arduino IDE with the ESP32 board package (2.0.9 tested; 3.x also works) and the Adafruit DHT sensor library (1.4.7).
2. Open `device/CeranaNode/CeranaNode.ino` and change the four lines at the top: WiFi name, WiFi password, Firebase address and hive name.
3. Wire the parts as in `device/CeranaNode_wiring.png`, then upload.

## AI server (optional)

See `server/README.md`. The app can also run without the assistant.

## Data

The field recordings of 70 *Apis cerana* colonies are not included, to protect the beekeepers' privacy. They are available from the author on request.

## Notes

- Use Firebase rules that are not open to everyone. Open rules were used only for testing.
- Third-party parts: YAMNet model by Google (Apache License 2.0). The ESC-50 and MS-SNSD sound datasets were used only for testing and are not included.
