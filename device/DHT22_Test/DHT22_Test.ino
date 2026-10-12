/*
 * DHT22_Test - checks the DHT22 wiring before it is added to CeranaNode.
 * Wiring: DHT22 +  -> 3V3,  DHT22 -  -> GND,  DHT22 OUT -> GPIO 4 (labelled D4)
 * Library: "DHT sensor library" by Adafruit (Tools -> Manage Libraries; click "Install all").
 * Open Serial Monitor at 115200 baud.
 */
#include <DHT.h>

const int PIN_DHT = 4;
DHT dht(PIN_DHT, DHT22);

void setup() {
  Serial.begin(115200);
  dht.begin();
  Serial.println("DHT22 test started");
}

void loop() {
  delay(2500);                      // the DHT22 gives a new reading at most every 2 seconds
  float t = dht.readTemperature();  // degrees C
  float h = dht.readHumidity();     // percent
  if (isnan(t) || isnan(h)) {
    Serial.println("No reading - check the three wires and that + goes to 3V3");
  } else {
    Serial.printf("Temperature %.1f C   Humidity %.0f %%\n", t, h);
  }
}
