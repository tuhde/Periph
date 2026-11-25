#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
HDC1008Full sensor(connection);                                         // Create HDC1008 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float t = 0, rh = 0;

    uint64_t serial = 0;
    sensor.init();                                                      // Write default configuration, () → void
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    Serial.print("serial id 0x"); Serial.print((unsigned)((serial) >> 32), HEX); Serial.print((unsigned)((serial) & 0xFFFFFFFF), HEX); Serial.println();

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        float g = logf(fmaxf(rh, 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * g / (17.62f - g);                         // Magnus dew point, °C
        Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH dew point "); Serial.print(dew, 2); Serial.print(" C"); Serial.println();
        delay(2000);
    }

    // --- Heater test: 10 s ---
    float rh0 = sensor.read_humidity();
    float t0c = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    delay(10000);
    float rh1 = sensor.read_humidity();
    float t1c = sensor.read_temperature();
    Serial.print("RH drop "); Serial.print(rh0 - rh1, 1); Serial.print(" %RH, temperature rise "); Serial.print(t1c - t0c, 2); Serial.print(" K"); Serial.println();
    sensor.set_heater(false);                                           // Heater off

    // --- Resolution comparison ---
    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
    uint32_t ts = millis();
    sensor.read(t, rh);
    uint32_t dt = millis() - ts;
    Serial.print("11/8 bit: "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH in "); Serial.print((unsigned)(dt)); Serial.print(" ms"); Serial.println();
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit
    Serial.print("battery low "); Serial.print((unsigned)(sensor.battery_low())); Serial.println();
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
}

void loop() {
    delay(1000);
}
