#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x23);
BH1750Full sensor(connection);                                  // Create BH1750 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Power on, () → void
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float lux = 0;
    uint16_t raw = 0;
    bool ok = sensor.read(lux);                                     // Measure illuminance, (lux&) → bool
                                                                    // one-time H-resolution (1 lx), waits 180 ms
    Serial.print("illuminance "); Serial.print(lux, 1); Serial.print(" lx"); Serial.println();
    ok = sensor.read_raw(raw);                                      // Read the raw count, (raw&) → bool
                                                                    // 16-bit, one one-time measurement in the current mode
    Serial.print("raw "); Serial.print((unsigned long)(raw)); Serial.println();
    ok = sensor.measure_once(BH1750Full::MODE_H2, lux);             // One-time measurement, (mode, lux&) → bool
                                                                    // MODE_H 1 lx, MODE_H2 0.5 lx, MODE_L 4 lx and 24 ms
    Serial.print("H2 "); Serial.print(lux, 2); Serial.print(" lx"); Serial.println();
    Serial.print("measurement time "); Serial.print(sensor.measurement_time() * 1000.0f, 0); Serial.print(" ms"); Serial.println(); // Maximum measurement time, (mode=MODE_H) → float s
    Serial.print("lux per count "); Serial.print(sensor.lux_per_count(), 3); Serial.println(); // Lux per raw count, (mode=MODE_H) → float
    ok = sensor.start_continuous(BH1750Full::MODE_H);               // Start continuous mode, (mode=MODE_H) → bool
                                                                    // first result after measurement_time()
    delay(200);
    ok = sensor.read_continuous(lux);                               // Read the latest result, (lux&) → bool
                                                                    // no wait
    Serial.print("continuous "); Serial.print(lux, 1); Serial.print(" lx"); Serial.println();
    ok = sensor.set_measurement_time(138);                          // Set MTreg, (mtreg 31-254) → bool
                                                                    // default 69; larger = more sensitive and slower
    Serial.print("mtreg "); Serial.print((unsigned long)(sensor.get_measurement_time_register())); Serial.println(); // Get MTreg, () → uint8_t
                                                                    // cached, the chip has no read-back
    ok = sensor.set_sensitivity(1.0f);                              // Set sensitivity, (factor 0.45-3.68) → bool
                                                                    // MTreg = round(69 x factor)
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
    sensor.reset();                                                 // Clear the data register, () → void
                                                                    // powers on first; MTreg and mode are kept
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
