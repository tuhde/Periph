#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x54);
HS40xxFull sensor(connection);                                      // Create HS40xx driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    float t = 0, rh = 0;
    uint8_t st = 0;
    uint32_t id = 0;
    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    Serial.print("ID 0x"); Serial.print((unsigned long)(id), HEX); Serial.println();
    for (uint8_t n = 0; n < 5; n++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
        delay(2000);
    }
    sensor.set_alert_thresholds(60.0f, 55.0f);                      // Set alert thresholds, (rh_high, rh_high_reset, ...) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH);            // Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
    for (uint8_t n = 0; n < 15; n++) {
        delay(1000);
        sensor.read_periodic(t, rh, st);                            // Fetch the latest periodic result, (t, rh, status) → bool
        Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH "); Serial.print((st & HS40xxFull::SOURCE_RH_HIGH) ? "ALERT" : "ok"); Serial.println();
    }
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
}

void loop() {
    delay(1000);
}
