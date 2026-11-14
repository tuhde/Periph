#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
HTU21DFFull sensor(connection);                                         // Create HTU21D-F driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;
    uint8_t reg = 0, res = 0;

    sensor.init();                                                      // Soft reset, () → void
    sensor.soft_reset();                                                // Soft reset, () → void
    reg = sensor.read_user_register();                                  // Read the user register, () → uint8_t
    Serial.print("user register "); Serial.print("0x"); Serial.print((unsigned)reg, HEX); Serial.println();
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    Serial.print("resolution setting "); Serial.print((unsigned)res); Serial.println();

    // --- Five readings at 12/14 bit ---
    for (uint8_t i = 0; i < 5; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH (compensated "); Serial.print(sensor.read_humidity_compensated(), 1); Serial.print(") dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();
        // sensor.read_humidity_compensated() → float %RH ; sensor.dew_point() → float °C
        delay(2000);
    }
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
    Serial.print("raw T "); Serial.print((unsigned)raw_t); Serial.print(" raw RH "); Serial.print((unsigned)raw_rh); Serial.println();

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    sensor.set_resolution(1);                                           // Set resolution, (res) → bool
    uint32_t t0 = millis();
    sensor.read(t, rh);
    uint32_t dt = millis() - t0;
    Serial.print("8/12 bit: "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH in "); Serial.print((unsigned long)dt); Serial.print(" ms"); Serial.println();
    sensor.set_resolution(0);                                           // Back to 12/14 bit
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    delay(10000);
    Serial.print("heater on "); Serial.print((unsigned)sensor.heater()); Serial.println();
    Serial.print("RH drop "); Serial.print(rh_before - sensor.read_humidity(), 1); Serial.print(" %RH, temperature rise "); Serial.print(sensor.read_temperature() - t_before, 2); Serial.print(" K"); Serial.println();
    sensor.set_heater(false);                                           // Heater off
    Serial.print("battery low "); Serial.print((unsigned)sensor.battery_low()); Serial.println();
}

void loop() {
    delay(1000);
}
