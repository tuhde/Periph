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
                                                                        // 15 ms power-up wait, soft reset, 15 ms
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // no hold master, CRC checked, one retry on a mismatch
    Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.print(" %RH"); Serial.println();
    // sensor.read_humidity() → float %RH (NAN on failure), not clamped
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println();
    // sensor.read_temperature() → float °C (NAN on failure)
    Serial.print("compensated humidity "); Serial.print(sensor.read_humidity_compensated(), 1); Serial.print(" %RH"); Serial.println();
    // sensor.read_humidity_compensated() → float %RH, -0.15 %RH/°C relative to 25 °C (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit codes, status bits cleared
    Serial.print("raw T "); Serial.print((unsigned)raw_t); Serial.print(" raw RH "); Serial.print((unsigned)raw_rh); Serial.println();

    sensor.set_resolution(3);                                           // Set resolution, (res) → bool
                                                                        // 0 = 12/14 bit RH/T, 1 = 8/12, 2 = 10/13, 3 = 11/11
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    Serial.print("resolution setting "); Serial.print((unsigned)res); Serial.println();
    sensor.set_resolution(0);                                           // Back to 12/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5.5 mW; readings drift while on
    Serial.print("heater on "); Serial.print((unsigned)sensor.heater()); Serial.println();
    // sensor.heater() → bool
    sensor.set_heater(false);                                           // Heater off
    Serial.print("battery low "); Serial.print((unsigned)sensor.battery_low()); Serial.println();
    // sensor.battery_low() → bool, true when VDD is below about 2.25 V
    reg = sensor.read_user_register();                          // Read the user register, () → uint8_t
                                                                        // 0x02 after reset
    Serial.print("user register "); Serial.print("0x"); Serial.print((unsigned)reg, HEX); Serial.println();
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void
                                                                        // needs bus-layer clock stretching; off polls the NACKed read header
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();
    // sensor.dew_point() → float °C, one measurement (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0xFE, waits 15 ms; the heater bit survives
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
