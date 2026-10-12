/*
 * CeranaNode - hive device for CeranaWatch (version 2.3, 11 Oct 2026)
 * Board: ESP32 DevKit V1.  Parts: INMP441 microphone, DHT22 temperature/humidity sensor, buzzer.
 *
 * What it does, again and again:
 *   1. reads the temperature and humidity inside the hive (DHT22)
 *   2. listens to the hive for 10 seconds
 *   3. decides if the sound is BEE sound, OTHER sound (talking, fan, traffic...) or SILENT
 *      with a small neural network trained on 70 Apis cerana hives (see BeeDetect.h).
 *      It says "bees" only when 3 of the last 4 readings passed, and "no bees" again only when
 *      at most 1 of the last 4 passed (a colony hums all the time; house sounds come and go).
 *      The app can TEACH the device its own place: "no bees here" / "bees here" (10 readings each).
 *      LISTENING RANGE: a microphone hears everything around it. Bees inside a hive are a few cm from
 *      the microphone, so their sound is loud; a TV or talking in another place is quieter. The device
 *      ignores sound that is quieter than its listening range ("far"). Auto = learned from teaching.
 *   4. measures the bee-band sound (100-600 Hz) and the speech-band sound (900-4000 Hz)
 *   5. learns this hive's own normal level, only from readings with bee sound
 *   6. raises an alert when the bee sound is much quieter or louder than its normal,
 *      when no bee sound is heard several readings in a row, or when the hive is too hot
 *   7. sounds the buzzer (works with no internet) and writes to Firebase (needs WiFi)
 *
 * Wiring (one jumper wire for each line; a Y-join is two wires soldered onto one):
 *   INMP441 VDD -> 3V3 (Y-join 1)          never 5V or VIN
 *   INMP441 GND -> GND next to VIN (Y-join 2)
 *   INMP441 L/R -> GPIO 32 (D32)           the program keeps this pin at 0 V (left channel)
 *   INMP441 SD  -> GPIO 33 (D33)
 *   INMP441 WS  -> GPIO 25 (D25)
 *   INMP441 SCK -> GPIO 26 (D26)
 *   Buzzer I/O  -> GPIO 27 (D27)
 *   Buzzer VCC  -> GPIO 13 (D13)           the program keeps GPIO 13 at 3.3 V to power the module
 *   Buzzer GND  -> GND next to VIN (Y-join 2)
 *   DHT22 +     -> 3V3 (Y-join 1)          never 5V
 *   DHT22 OUT   -> GPIO 4 (D4)
 *   DHT22 -     -> GND next to 3V3
 *   Power: 5 V USB step-down module -> USB-C port (or see the wiring guide for VIN).
 *
 * Libraries: in Boards Manager install "esp32 by Espressif Systems".
 *            In Library Manager install "DHT sensor library" by Adafruit (click "Install all").
 * Keep BeeDetect.h in the same folder as this file (Arduino IDE opens it as a second tab).
 */

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <driver/i2s.h>
#include <math.h>
#include <time.h>
#include <DHT.h>
#include "BeeDetect.h"

// ===================== CHANGE THESE FOUR LINES =====================
const char* WIFI_NAME     = "YOUR_WIFI_NAME";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* FIREBASE_URL  = "https://YOUR-PROJECT-default-rtdb.REGION.firebasedatabase.app";  // no slash at the end
const char* HIVE_NAME     = "H01";                                               // the same name as in the app (capital letters and numbers)
// ====================================================================

// true  = demo: a reading every 30 seconds, normal learned from 10 readings, alerts may repeat after 2 minutes
// false = real use: a reading every 5 minutes, normal learned from 288 readings (24 hours), one alert per type per 6 hours
const bool DEMO_MODE = true;

const bool BUZZER_ON_IS_HIGH = false;   // change to false if your buzzer sounds all the time
const bool BUZZER_IS_PASSIVE = false;  // change to true if your buzzer only clicks and does not beep

// ---------- pins ----------
const int PIN_I2S_WS  = 25;
const int PIN_I2S_SCK = 26;
const int PIN_I2S_SD  = 33;
const int PIN_BUZZER  = 27;
const int PIN_MIC_LR       = 32;   // held at 0 V: tells the microphone to use the left channel
const int PIN_BUZZER_POWER = 13;   // held at 3.3 V: power for a 3-pin buzzer module
const int PIN_DHT          = 4;    // DHT22 OUT

// ---------- sound settings ----------
const int   SAMPLE_RATE     = 16000;
const int   FFT_SIZE        = 1024;
const int   CAPTURE_SECONDS = 10;
const float BEE_LOW_HZ = 100.0f, BEE_HIGH_HZ = 600.0f;
const float SPEECH_LOW_HZ = 900.0f, SPEECH_HIGH_HZ = 4000.0f;
const int   BEE_WINDOWS     = 3;          // the 10 seconds are judged as 3 windows of about 3 seconds
const float SILENT_DB       = -75.0f;     // below this loudness the reading counts as silent

// ---------- temperature settings ----------
const float HOT_C = 40.0f;                // alert when the hive is this hot two readings in a row

// ---------- timing and alert settings ----------
const unsigned long READ_EVERY_SECONDS = DEMO_MODE ? 30UL : 300UL;
const int           BASELINE_READINGS  = DEMO_MODE ? 10 : 288;
const unsigned long ALERT_GAP_SECONDS  = DEMO_MODE ? 120UL : 21600UL;
const float         Z_LIMIT            = 3.0f;    // alert when further than 3 standard deviations from normal
const float         MIN_SD_DB          = 0.7f;    // floor, so a very steady hive does not alert on tiny changes
const int           NO_BEE_READINGS    = 3;       // "no bee sound" alert after this many readings in a row without bee sound
const int           PRESENCE_READINGS  = 4;       // the last 4 readings decide if bees are here
const int           PRESENT_ON         = 3;       // bees: at least 3 of the last 4 readings passed
const int           PRESENT_OFF        = 1;       // no bees again: at most 1 of the last 4 readings passed
const int           TEACH_READINGS     = 10;      // readings used for one teaching (10 x 3 windows)
const int           TEACH_MAX          = 60;      // taught windows kept
const int           TEACH_PER_LABEL    = 30;      // the newest 30 "no bees" AND the newest 30 "bees" windows are kept

// ---------- listening range (how loud a sound must be to come from inside the hive) ----------
const float RANGE_AUTO_DB  = -62.0f;   // auto, before the device has been taught
const float RANGE_NEAR_DB  = -50.0f;   // "hive only": very close, loud sound
const float RANGE_MAX_DB   = -40.0f;   // the range is never set stricter than this
const int   LOUDNESS_KEEP  = 20;       // loudness of the last 20 taught readings of each kind

Preferences prefs;

// the hive's own normal (kept in flash memory, so it survives a restart)
int   baseCount = 0;
float baseMean  = 0.0f;
float baseM2    = 0.0f;

int           quietInARow   = 0;
unsigned long lastQuietAlert = 0;
unsigned long lastLoudAlert  = 0;
bool          everQuietAlert = false;
bool          everLoudAlert  = false;
int           noBeeInARow    = 0;
unsigned long lastNoBeeAlert = 0;
bool          everNoBeeAlert = false;
int           hotInARow      = 0;
unsigned long lastHotAlert   = 0;
bool          everHotAlert   = false;

bool passHistory[PRESENCE_READINGS];
int  passHistoryCount = 0;
bool beesPresent = false;

// teaching: 0 = not teaching, 1 = "no bees here", 2 = "bees here"
int  teachMode = 0;
int  teachLeft = 0;
static float         taughtH[TEACH_MAX * beedetect::HIDDEN];
static unsigned char taughtY[TEACH_MAX];
int  taughtCount = 0;
static float winFeat[3][beedetect::FEATURES];   // the last reading's windows, for teaching and upload
static float winHid[3][beedetect::HIDDEN];
static float winProb[3];
int  winCount = 0;

int   rangeMode = 0;                          // 0 = auto, 1 = hive only, 2 = everything
float nearDb = RANGE_AUTO_DB;                 // sound quieter than this counts as "far"
float roomDb[LOUDNESS_KEEP];  int roomDbCount = 0;   // loudness of readings taught as "no bees here"
float hiveDb[LOUDNESS_KEEP];  int hiveDbCount = 0;   // loudness of readings taught as "bees here"

DHT dht(PIN_DHT, DHT22);
static beedetect::Window beeWindow;
static float power[beedetect::BINS];

static int32_t rawBlock[FFT_SIZE];
static float   re[FFT_SIZE];
static float   im[FFT_SIZE];
static float   hannWindow[FFT_SIZE];

// ---------- buzzer ----------
// The tone functions have different names in version 2 and version 3 of the ESP32 board package.
// These two small functions make the program work with both.
void toneStart() {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(PIN_BUZZER, 2500, 8);
#else
  ledcSetup(0, 2500, 8);
  ledcAttachPin(PIN_BUZZER, 0);
#endif
}

void toneSet(int frequency) {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWriteTone(PIN_BUZZER, frequency);
#else
  ledcWriteTone(0, frequency);
#endif
}

void buzzer(bool on) {
  if (BUZZER_IS_PASSIVE) {
    toneSet(on ? 2500 : 0);              // a passive buzzer needs a tone signal
  } else {
    digitalWrite(PIN_BUZZER, (on == BUZZER_ON_IS_HIGH) ? HIGH : LOW);
  }
}

void beep(int times, int onMs, int offMs) {
  for (int i = 0; i < times; i++) {
    buzzer(true);
    delay(onMs);
    buzzer(false);
    delay(offMs);
  }
}

// ---------- microphone ----------
void startMicrophone() {
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate = SAMPLE_RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 8;
  cfg.dma_buf_len = 256;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = false;
  cfg.fixed_mclk = 0;

  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = PIN_I2S_SCK;
  pins.ws_io_num = PIN_I2S_WS;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = PIN_I2S_SD;

  i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  i2s_set_pin(I2S_NUM_0, &pins);
  i2s_zero_dma_buffer(I2S_NUM_0);
}

// ---------- FFT (length must be a power of two) ----------
void fft(float* r, float* x, int n) {
  int j = 0;
  for (int i = 1; i < n; i++) {
    int bit = n >> 1;
    while (j & bit) {
      j ^= bit;
      bit >>= 1;
    }
    j ^= bit;
    if (i < j) {
      float t = r[i]; r[i] = r[j]; r[j] = t;
      t = x[i]; x[i] = x[j]; x[j] = t;
    }
  }
  for (int len = 2; len <= n; len <<= 1) {
    float ang = -2.0f * PI / len;
    float wRe = cosf(ang), wIm = sinf(ang);
    for (int i = 0; i < n; i += len) {
      float curRe = 1.0f, curIm = 0.0f;
      for (int k = 0; k < len / 2; k++) {
        int a = i + k, b = i + k + len / 2;
        float xr = r[b] * curRe - x[b] * curIm;
        float xi = r[b] * curIm + x[b] * curRe;
        r[b] = r[a] - xr;  x[b] = x[a] - xi;
        r[a] += xr;        x[a] += xi;
        float nextRe = curRe * wRe - curIm * wIm;
        curIm = curRe * wIm + curIm * wRe;
        curRe = nextRe;
      }
    }
  }
}

// One reading: listens for CAPTURE_SECONDS and fills the four numbers.
// beeProb is the average bee-sound probability of the 3 windows (0..1), or -1 when it could not be judged.
// Returns false if the microphone gave no data.
bool listen(float* lowDb, float* highDb, float* rmsDb, float* beeProb) {
  const int blocks = (CAPTURE_SECONDS * SAMPLE_RATE) / FFT_SIZE;
  const int binLow1  = (int)ceilf(BEE_LOW_HZ * FFT_SIZE / SAMPLE_RATE);
  const int binLow2  = (int)floorf(BEE_HIGH_HZ * FFT_SIZE / SAMPLE_RATE);
  const int binHigh1 = (int)ceilf(SPEECH_LOW_HZ * FFT_SIZE / SAMPLE_RATE);
  const int binHigh2 = (int)floorf(SPEECH_HIGH_HZ * FFT_SIZE / SAMPLE_RATE);

  double lowSum = 0.0, highSum = 0.0, squareSum = 0.0;
  long   used = 0;
  size_t got = 0;
  float  probSum = 0.0f;
  int    windowsDone = 0;
  beeWindow.reset();

  // throw away the first quarter second (the microphone needs a moment to settle)
  for (int i = 0; i < 4; i++) i2s_read(I2S_NUM_0, rawBlock, sizeof(rawBlock), &got, 1000 / portTICK_PERIOD_MS);

  for (int b = 0; b < blocks; b++) {
    got = 0;
    i2s_read(I2S_NUM_0, rawBlock, sizeof(rawBlock), &got, 2000 / portTICK_PERIOD_MS);
    if (got < sizeof(rawBlock)) continue;

    double mean = 0.0;
    for (int i = 0; i < FFT_SIZE; i++) {
      re[i] = (float)(rawBlock[i] >> 8) / 8388608.0f;   // 24-bit sample to -1..1
      mean += re[i];
    }
    mean /= FFT_SIZE;
    for (int i = 0; i < FFT_SIZE; i++) {
      float v = re[i] - (float)mean;                    // remove the DC offset
      squareSum += (double)v * v;
      re[i] = v * hannWindow[i];
      im[i] = 0.0f;
    }
    fft(re, im, FFT_SIZE);
    for (int k = 0; k < beedetect::BINS; k++) power[k] = re[k] * re[k] + im[k] * im[k];
    for (int k = binLow1; k <= binLow2; k++)   lowSum  += power[k];
    for (int k = binHigh1; k <= binHigh2; k++) highSum += power[k];
    used++;

    // bee-sound detector: 3 windows of 46 frames
    if (windowsDone < BEE_WINDOWS) {
      beeWindow.addFrame(power);
      if (beeWindow.full()) {
        beeWindow.features(winFeat[windowsDone]);
        beedetect::hiddenFromFeatures(winFeat[windowsDone], winHid[windowsDone]);
        winProb[windowsDone] = beedetect::headProbability(beedetect::head, winHid[windowsDone]);
        probSum += winProb[windowsDone];
        windowsDone++;
        beeWindow.reset();
      }
    }
  }
  if (used == 0) return false;
  winCount = windowsDone;
  *beeProb = windowsDone > 0 ? probSum / windowsDone : -1.0f;

  *lowDb  = 10.0f * log10f((float)(lowSum / used) + 1e-12f);
  *highDb = 10.0f * log10f((float)(highSum / used) + 1e-12f);
  *rmsDb  = 10.0f * log10f((float)(squareSum / ((double)used * FFT_SIZE)) + 1e-12f);
  return true;
}

// ---------- the hive's own normal ----------
void saveBaseline();
void loadTeaching();
void loadRange();
void saveRange();
void updateRange();
int  taughtWith(unsigned char label);
void trimTaught();

void loadBaseline() {
  prefs.begin("cerana", false);
  baseCount = prefs.getInt("n", 0);
  baseMean  = prefs.getFloat("mean", 0.0f);
  baseM2    = prefs.getFloat("m2", 0.0f);
  // a normal saved by an older program was learned without the 3-of-4 rule (it could be house sound): start again
  if (prefs.getInt("ver", 1) < 3) {
    baseCount = 0; baseMean = 0.0f; baseM2 = 0.0f;
    saveBaseline();
    prefs.putInt("ver", 3);
    Serial.println("Old normal cleared. Learning again from bee sound only.");
  }
  loadTeaching();
  loadRange();
}

// ---------- teaching (the device learns its own microphone and place) ----------
void loadTeaching() {
  // teaching belongs to one detector: after a new BeeDetect.h (for example from the Colab notebook) it starts again
  if (prefs.getString("tModel", "") != String(beedetect::MODEL_NAME)) {
    prefs.putInt("tN", 0);
    prefs.putString("tModel", beedetect::MODEL_NAME);
  }
  taughtCount = prefs.getInt("tN", 0);
  if (taughtCount < 0 || taughtCount > TEACH_MAX) taughtCount = 0;
  if (taughtCount > 0 &&
      prefs.getBytes("tH", taughtH, sizeof(float) * beedetect::HIDDEN * taughtCount) == sizeof(float) * beedetect::HIDDEN * taughtCount &&
      prefs.getBytes("tY", taughtY, taughtCount) == (size_t)taughtCount) {
    trimTaught();
    beedetect::head = beedetect::fineTune(taughtH, taughtY, taughtCount);
    Serial.printf("Teaching loaded: %d windows.\n", taughtCount);
  } else {
    taughtCount = 0;
    beedetect::head = beedetect::baseHead();
  }
}

void saveTeaching() {
  prefs.putInt("tN", taughtCount);
  if (taughtCount > 0) {
    prefs.putBytes("tH", taughtH, sizeof(float) * beedetect::HIDDEN * taughtCount);
    prefs.putBytes("tY", taughtY, taughtCount);
  }
}

void forgetTeaching() {
  taughtCount = 0; teachMode = 0; teachLeft = 0;
  prefs.putInt("tN", 0);
  beedetect::head = beedetect::baseHead();
  roomDbCount = 0; hiveDbCount = 0;
  saveRange();
  updateRange();
}

int taughtWith(unsigned char label) {
  int c = 0;
  for (int i = 0; i < taughtCount; i++) if (taughtY[i] == label) c++;
  return c;
}

// value at a share q (0..1) of a small list
float percentile(const float* v, int n, float q) {
  float s[LOUDNESS_KEEP];
  for (int i = 0; i < n; i++) s[i] = v[i];
  for (int i = 1; i < n; i++) { float x = s[i]; int j = i - 1; while (j >= 0 && s[j] > x) { s[j + 1] = s[j]; j--; } s[j + 1] = x; }
  float pos = q * (n - 1);
  int a = (int)pos;
  int b = a + 1 < n ? a + 1 : a;
  return s[a] + (s[b] - s[a]) * (pos - a);
}

// sets the listening range from the mode and from the loudness heard while teaching
void updateRange() {
  if (rangeMode == 1) { nearDb = RANGE_NEAR_DB; return; }
  if (rangeMode == 2) { nearDb = SILENT_DB; return; }
  float g = RANGE_AUTO_DB;
  bool room = roomDbCount >= 5, hive = hiveDbCount >= 5;
  float roomHigh = room ? percentile(roomDb, roomDbCount, 0.9f) : 0.0f;   // most of the room sound is quieter than this
  float hiveLow  = hive ? percentile(hiveDb, hiveDbCount, 0.1f) : 0.0f;   // most of the hive sound is louder than this
  if (room && hive) {
    if (hiveLow > roomHigh + 3.0f) g = (roomHigh + hiveLow) / 2.0f;      // a clear gap: in the middle
    else g = fminf(RANGE_AUTO_DB, hiveLow - 6.0f);                        // they overlap: loudness cannot help much
  } else if (hive) {
    g = fminf(RANGE_AUTO_DB, hiveLow - 6.0f);
  }
  // room only: the range is NOT raised. On 11 Oct a real hive gave readings as quiet as -57 to -62 dB,
  // as quiet as the room, so a stricter range would have thrown away real bee readings.
  if (g < SILENT_DB) g = SILENT_DB;
  if (g > RANGE_MAX_DB) g = RANGE_MAX_DB;
  nearDb = g;
}

void keepLoudness(float* list, int* count, float value) {
  if (*count < LOUDNESS_KEEP) { list[(*count)++] = value; return; }
  for (int i = 1; i < LOUDNESS_KEEP; i++) list[i - 1] = list[i];
  list[LOUDNESS_KEEP - 1] = value;
}

void saveRange() {
  prefs.putInt("rMode", rangeMode);
  prefs.putInt("rRoomN", roomDbCount);
  prefs.putInt("rHiveN", hiveDbCount);
  if (roomDbCount > 0) prefs.putBytes("rRoom", roomDb, sizeof(float) * roomDbCount);
  if (hiveDbCount > 0) prefs.putBytes("rHive", hiveDb, sizeof(float) * hiveDbCount);
}

void loadRange() {
  rangeMode = prefs.getInt("rMode", 0);
  if (rangeMode < 0 || rangeMode > 2) rangeMode = 0;
  roomDbCount = prefs.getInt("rRoomN", 0);
  hiveDbCount = prefs.getInt("rHiveN", 0);
  if (roomDbCount < 0 || roomDbCount > LOUDNESS_KEEP || (roomDbCount > 0 && prefs.getBytes("rRoom", roomDb, sizeof(float) * roomDbCount) != sizeof(float) * roomDbCount)) roomDbCount = 0;
  if (hiveDbCount < 0 || hiveDbCount > LOUDNESS_KEEP || (hiveDbCount > 0 && prefs.getBytes("rHive", hiveDb, sizeof(float) * hiveDbCount) != sizeof(float) * hiveDbCount)) hiveDbCount = 0;
  updateRange();
}

// removes one taught window (the list stays in order, oldest first)
void removeTaught(int index) {
  for (int i = index; i < taughtCount - 1; i++) {
    memcpy(&taughtH[i * beedetect::HIDDEN], &taughtH[(i + 1) * beedetect::HIDDEN], sizeof(float) * beedetect::HIDDEN);
    taughtY[i] = taughtY[i + 1];
  }
  taughtCount--;
}

void removeOldest(unsigned char label) {
  for (int i = 0; i < taughtCount; i++) {
    if (taughtY[i] == label) { removeTaught(i); return; }
  }
}

// keeps only the newest TEACH_PER_LABEL windows of each kind, so "bees here" never pushes out "no bees here"
void trimTaught() {
  for (unsigned char label = 0; label <= 1; label++) {
    while (taughtWith(label) > TEACH_PER_LABEL) removeOldest(label);
  }
}

// keeps this reading's windows as taught examples
void keepTaughtWindows(unsigned char label) {
  for (int w = 0; w < winCount; w++) {
    if (taughtWith(label) >= TEACH_PER_LABEL) removeOldest(label);
    if (taughtCount >= TEACH_MAX) removeTaught(0);
    memcpy(&taughtH[taughtCount * beedetect::HIDDEN], winHid[w], sizeof(float) * beedetect::HIDDEN);
    taughtY[taughtCount] = label;
    taughtCount++;
  }
}

void saveBaseline() {
  prefs.putInt("n", baseCount);
  prefs.putFloat("mean", baseMean);
  prefs.putFloat("m2", baseM2);
}

void resetBaseline() {
  baseCount = 0; baseMean = 0.0f; baseM2 = 0.0f;
  quietInARow = 0; everQuietAlert = false; everLoudAlert = false;
  noBeeInARow = 0; everNoBeeAlert = false;
  passHistoryCount = 0; beesPresent = false;
  saveBaseline();
}

// the 3-of-4 rule: a colony hums all the time, house sounds come and go
void updatePresence(bool pass) {
  for (int i = PRESENCE_READINGS - 1; i > 0; i--) passHistory[i] = passHistory[i - 1];
  passHistory[0] = pass;
  if (passHistoryCount < PRESENCE_READINGS) passHistoryCount++;
  int passes = 0;
  for (int i = 0; i < passHistoryCount; i++) if (passHistory[i]) passes++;
  if (!beesPresent && passes >= PRESENT_ON) beesPresent = true;
  else if (beesPresent && passes <= PRESENT_OFF) beesPresent = false;
}

bool baselineReady() { return baseCount >= BASELINE_READINGS; }

float baselineSd() {
  if (baseCount < 2) return MIN_SD_DB;
  float sd = sqrtf(baseM2 / (baseCount - 1));
  return sd < MIN_SD_DB ? MIN_SD_DB : sd;
}

void learn(float value) {
  baseCount++;
  float d = value - baseMean;
  baseMean += d / baseCount;
  baseM2 += d * (value - baseMean);
  saveBaseline();
}

// ---------- WiFi, time and Firebase ----------
void connectWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  Serial.print("Connecting to WiFi");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_NAME, WIFI_PASSWORD);
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) {
    delay(500);
    Serial.print(".");
  }
  Serial.println(WiFi.status() == WL_CONNECTED ? " connected" : " NOT connected (the buzzer still works)");
  if (WiFi.status() == WL_CONNECTED) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    for (int i = 0; i < 20 && time(nullptr) < 1700000000; i++) delay(500);
  }
}

// Sends JSON to Firebase. method is "PUT", "POST" or "GET". Returns the HTTP code (200 = good).
int firebase(const char* method, const String& path, const String& body, String* answer) {
  if (WiFi.status() != WL_CONNECTED) return -1;
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  String url = String(FIREBASE_URL) + path;
  if (!http.begin(client, url)) return -2;
  http.setTimeout(15000);
  http.addHeader("Content-Type", "application/json");
  int code = http.sendRequest(method, body);
  if (answer != NULL && code > 0) *answer = http.getString();
  http.end();
  return code;
}

String num(float v) {
  if (isnan(v) || isinf(v)) return "null";
  return String(v, 2);
}

void sendAlert(const char* type, const char* message, float lowDb, float z, float tempC, float beeProb) {
  String body = "{";
  body += "\"hive\":\"" + String(HIVE_NAME) + "\",";
  body += "\"t\":" + String((unsigned long)time(nullptr)) + ",";
  body += "\"type\":\"" + String(type) + "\",";
  body += "\"message\":\"" + String(message) + "\",";
  body += "\"lowDb\":" + num(lowDb) + ",";
  body += "\"z\":" + num(z) + ",";
  body += "\"normalDb\":" + num(baseMean) + ",";
  body += "\"normalSd\":" + num(baselineSd()) + ",";
  body += "\"tempC\":" + num(tempC) + ",";
  body += "\"beeProb\":" + num(beeProb) + ",";
  body += "\"ack\":false}";
  int code = firebase("POST", "/alerts.json", body, NULL);
  Serial.printf("ALERT %s (z = %.1f), Firebase code %d\n", type, z, code);
}

// While teaching, each window's 50 numbers are also sent to Firebase (/trainfeatures), so the
// Colab notebook (Part 8) can train a better detector with this device's own microphone and place.
void uploadTrainingWindows(const char* label) {
  if (WiFi.status() != WL_CONNECTED || winCount == 0) return;
  String body = "{\"t\":" + String((unsigned long)time(nullptr)) + ",\"label\":\"" + String(label) + "\",\"model\":\"" + String(beedetect::MODEL_NAME) + "\",\"p\":[";
  for (int w = 0; w < winCount; w++) body += (w ? "," : "") + String(winProb[w], 4);
  body += "],\"f\":[";
  for (int w = 0; w < winCount; w++) {
    body += (w ? ",[" : "[");
    for (int i = 0; i < beedetect::FEATURES; i++) body += (i ? "," : "") + String(winFeat[w][i], 5);
    body += "]";
  }
  body += "]}";
  int code = firebase("POST", String("/trainfeatures/") + HIVE_NAME + ".json", body, NULL);
  if (code != 200) Serial.printf("Training numbers not sent (Firebase %d)\n", code);
}

// ---------- start ----------
void setup() {
  Serial.begin(115200);
  pinMode(PIN_MIC_LR, OUTPUT);
  digitalWrite(PIN_MIC_LR, LOW);
  pinMode(PIN_BUZZER_POWER, OUTPUT);
  digitalWrite(PIN_BUZZER_POWER, HIGH);
  dht.begin();
  if (BUZZER_IS_PASSIVE) {
    toneStart();
  } else {
    pinMode(PIN_BUZZER, OUTPUT);
  }
  buzzer(false);
  for (int i = 0; i < FFT_SIZE; i++) hannWindow[i] = 0.5f - 0.5f * cosf(2.0f * PI * i / FFT_SIZE);

  loadBaseline();
  startMicrophone();
  connectWifi();
  beep(1, 120, 0);   // one short beep: the device has started
  Serial.printf("CeranaNode v2.3 started for hive %s. Normal so far: %d of %d readings.\n", HIVE_NAME, baseCount, BASELINE_READINGS);
}

// ---------- one cycle ----------
void loop() {
  unsigned long started = millis();
  connectWifi();
  // the clock comes from the internet; wait a little more if it is not set yet (so readings have the right time)
  if (WiFi.status() == WL_CONNECTED && time(nullptr) < 1700000000) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    for (int i = 0; i < 20 && time(nullptr) < 1700000000; i++) delay(500);
  }

  // commands from the app: learn the normal again, or teach the device its place
  String command;
  if (firebase("GET", String("/nodes/") + HIVE_NAME + "/command.json", "", &command) == 200 && command.length() > 2 && command != "null") {
    bool known = true;
    if (command.indexOf("teach_forget") >= 0) {
      forgetTeaching();
      Serial.println("Teaching forgotten. The trained detector is used again.");
    } else if (command.indexOf("teach_room") >= 0) {
      teachMode = 1; teachLeft = TEACH_READINGS;
      Serial.println("Teaching started: NO BEES here.");
    } else if (command.indexOf("teach_hive") >= 0) {
      teachMode = 2; teachLeft = TEACH_READINGS;
      Serial.println("Teaching started: BEES here.");
    } else if (command.indexOf("range_auto") >= 0 || command.indexOf("range_near") >= 0 || command.indexOf("range_all") >= 0) {
      rangeMode = command.indexOf("range_near") >= 0 ? 1 : (command.indexOf("range_all") >= 0 ? 2 : 0);
      updateRange();
      saveRange();
      passHistoryCount = 0; beesPresent = false;
      Serial.printf("Listening range: %s, %.0f dB\n", rangeMode == 1 ? "hive only" : (rangeMode == 2 ? "everything" : "auto"), nearDb);
    } else if (command.indexOf("reset") >= 0) {
      resetBaseline();
      Serial.println("Normal was reset by the app.");
    } else {
      known = false;
    }
    firebase("PUT", String("/nodes/") + HIVE_NAME + "/command.json", "null", NULL);
    if (known) beep(2, 80, 80);
  }

  // temperature and humidity inside the hive (NaN when the sensor did not answer)
  float tempC = dht.readTemperature();
  float humidity = dht.readHumidity();
  bool dhtOk = !isnan(tempC) && !isnan(humidity);

  float lowDb = 0, highDb = 0, rmsDb = 0, beeProb = -1;
  if (!listen(&lowDb, &highDb, &rmsDb, &beeProb)) {
    Serial.println("No sound data. Check the microphone wiring.");
    delay(5000);
    return;
  }

  // teaching: keep this reading's windows, send them for the Colab notebook, and fit again at the end
  bool teaching = teachMode != 0;
  if (teaching) {
    if (rmsDb >= SILENT_DB) {
      if (teachMode == 2) keepLoudness(hiveDb, &hiveDbCount, rmsDb); else keepLoudness(roomDb, &roomDbCount, rmsDb);
    }
    keepTaughtWindows(teachMode == 2 ? 1 : 0);
    uploadTrainingWindows(teachMode == 2 ? "hive" : "room");
    teachLeft--;
    if (teachLeft <= 0) {
      beedetect::head = beedetect::fineTune(taughtH, taughtY, taughtCount);
      saveTeaching();
      updateRange();
      saveRange();
      teachMode = 0;
      passHistoryCount = 0; beesPresent = false;     // judge again with the taught detector
      Serial.printf("Teaching done (%d windows). The detector is fitted to this place. Listening range %.0f dB.\n", taughtCount, nearDb);
      beep(2, 300, 150);
    }
  }

  // this reading: did it pass? then the 3-of-4 rule decides if bees are here
  bool silent = rmsDb < SILENT_DB;
  bool far = !silent && rmsDb < nearDb;            // quieter than the listening range: not from inside a hive
  bool beeNow = !silent && !far && beeProb >= beedetect::THRESHOLD;
  updatePresence(beeNow);
  const char* sound = silent ? "silent" : (far ? "far" : (beesPresent ? "bee" : "other"));
  bool bee = beesPresent && !silent && !far;

  float z = 0.0f;
  bool ready = baselineReady();
  unsigned long now = millis() / 1000UL;

  if (teaching) {
    // no learning and no alerts while teaching
  } else if (bee) {
    noBeeInARow = 0;
    if (beeNow) {                     // the normal and the alerts use only readings that passed themselves
      if (!ready) {
        learn(lowDb);
      } else {
        z = (lowDb - baseMean) / baselineSd();
        if (z < -Z_LIMIT) quietInARow++; else quietInARow = 0;

        if (quietInARow >= 2 && (!everQuietAlert || now - lastQuietAlert >= ALERT_GAP_SECONDS)) {
          everQuietAlert = true;
          lastQuietAlert = now;
          beep(3, 600, 300);
          sendAlert("quiet", "Bee sound much lower than this hive's normal, two readings in a row.", lowDb, z, tempC, beeProb);
        }
        if (z > Z_LIMIT && (!everLoudAlert || now - lastLoudAlert >= ALERT_GAP_SECONDS)) {
          everLoudAlert = true;
          lastLoudAlert = now;
          beep(6, 150, 150);
          sendAlert("loud", "Bee sound much higher than this hive's normal.", lowDb, z, tempC, beeProb);
        }
      }
    }
  } else {
    noBeeInARow++;
    quietInARow = 0;
    // only when bees were heard here before (so a device switched on far from bees stays calm)
    if (baseCount > 0 && noBeeInARow >= NO_BEE_READINGS && (!everNoBeeAlert || now - lastNoBeeAlert >= ALERT_GAP_SECONDS)) {
      everNoBeeAlert = true;
      lastNoBeeAlert = now;
      beep(5, 500, 250);
      sendAlert("nobees", "No bee sound heard several readings in a row.", lowDb, 0.0f, tempC, beeProb);
    }
  }

  if (dhtOk && tempC >= HOT_C) hotInARow++; else hotInARow = 0;
  if (hotInARow >= 2 && (!everHotAlert || now - lastHotAlert >= ALERT_GAP_SECONDS)) {
    everHotAlert = true;
    lastHotAlert = now;
    beep(4, 300, 200);
    sendAlert("hot", "The hive is very hot, two readings in a row.", lowDb, 0.0f, tempC, beeProb);
  }

  // latest reading for the app
  int passes = 0;
  for (int i = 0; i < passHistoryCount; i++) if (passHistory[i]) passes++;
  String teachText = "";
  if (teachMode != 0) teachText = String(teachMode == 2 ? "hive " : "room ") + String(TEACH_READINGS - teachLeft) + "/" + String(TEACH_READINGS);
  String reading = "{";
  reading += "\"t\":" + String((unsigned long)time(nullptr)) + ",";
  reading += "\"sound\":\"" + String(sound) + "\",";
  reading += "\"beeNow\":" + String(beeNow ? "true" : "false") + ",";
  reading += "\"passes\":" + String(passes) + ",";
  reading += "\"beeProb\":" + num(beeProb) + ",";
  reading += "\"lowDb\":" + num(lowDb) + ",";
  reading += "\"highDb\":" + num(highDb) + ",";
  reading += "\"rmsDb\":" + num(rmsDb) + ",";
  reading += "\"z\":" + num(z) + ",";
  reading += "\"ready\":" + String(ready ? "true" : "false") + ",";
  reading += "\"normalCount\":" + String(baseCount) + ",";
  reading += "\"normalNeeded\":" + String(BASELINE_READINGS) + ",";
  reading += "\"normalDb\":" + num(baseMean) + ",";
  reading += "\"normalSd\":" + num(baselineSd()) + ",";
  reading += "\"noBeeRun\":" + String(noBeeInARow) + ",";
  reading += "\"tempC\":" + (dhtOk ? num(tempC) : String("null")) + ",";
  reading += "\"humidity\":" + (dhtOk ? num(humidity) : String("null")) + ",";
  reading += "\"dhtOk\":" + String(dhtOk ? "true" : "false") + ",";
  reading += "\"teach\":\"" + teachText + "\",";
  reading += "\"taughtRoom\":" + String(taughtWith(0)) + ",";
  reading += "\"taughtHive\":" + String(taughtWith(1)) + ",";
  reading += "\"model\":\"" + String(beedetect::MODEL_NAME) + "\",";
  reading += "\"range\":\"" + String(rangeMode == 1 ? "near" : (rangeMode == 2 ? "all" : "auto")) + "\",";
  reading += "\"nearDb\":" + num(nearDb) + ",";
  reading += "\"fw\":\"2.3\",";
  reading += "\"everySeconds\":" + String(READ_EVERY_SECONDS) + "}";

  int c1 = firebase("PUT", String("/nodes/") + HIVE_NAME + "/latest.json", reading, NULL);
  int c2 = firebase("POST", String("/readings/") + HIVE_NAME + ".json", reading, NULL);
  Serial.printf("sound %s (this reading %s, bee %.2f, %d of last %d passed), bee band %.1f dB, loudness %.1f dB (range %.0f dB), z %.1f, normal %d/%d, ",
                sound, beeNow ? "passed" : (far ? "too far/quiet" : "not bees"), beeProb, passes, passHistoryCount, lowDb, rmsDb, nearDb, z, baseCount, BASELINE_READINGS);
  if (dhtOk) Serial.printf("%.1f C, %.0f %% humidity, ", tempC, humidity); else Serial.print("DHT22 no answer, ");
  if (teachMode != 0) Serial.printf("teaching %s, ", teachText.c_str());
  Serial.printf("Firebase %d %d\n", c1, c2);

  // wait until the next reading (while teaching, the next reading starts after 5 seconds)
  unsigned long usedMs = millis() - started;
  unsigned long waitMs = (teachMode != 0 ? 5UL : READ_EVERY_SECONDS) * 1000UL;
  if (usedMs < waitMs) delay(waitMs - usedMs);
}
