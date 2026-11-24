#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x23);
BH1750Full sensor(connection);                                  // Create BH1750 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Power on, () → void

    // --- Light meter ---
    // Reads the illuminance once in each one-time mode, prints continuous H-mode
    // values ten times per second for 5 s (cover and uncover the sensor), shows the
    // effect of the lowest and highest sensitivity on the raw count and the
    // measurement time, and finishes with reset and a power cycle.
    float lux = 0;
    uint16_t raw = 0;
    sensor.measure_once(BH1750Full::MODE_H, lux);                   // One-time measurement, (mode, lux&) → bool
    Serial.print("H  "); Serial.print(lux, 2); Serial.print(" lx"); Serial.println();
    sensor.measure_once(BH1750Full::MODE_H2, lux);
    Serial.print("H2 "); Serial.print(lux, 2); Serial.print(" lx"); Serial.println();
    sensor.measure_once(BH1750Full::MODE_L, lux);
    Serial.print("L  "); Serial.print(lux, 2); Serial.print(" lx"); Serial.println();
    sensor.start_continuous(BH1750Full::MODE_H);                    // Start continuous mode, (mode) → bool
    for (int i = 0; i < 50; i++) {
        delay(100);
        sensor.read_continuous(lux);                                // Read the latest result, (lux&) → bool
        Serial.print(""); Serial.print(lux, 1); Serial.print(" lx"); Serial.println();
    }
    sensor.set_sensitivity(0.45f);                                  // Set sensitivity, (factor) → bool
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    Serial.print("x0.45: mtreg "); Serial.print((unsigned long)(sensor.get_measurement_time_register())); Serial.print(" raw "); Serial.print((unsigned long)(raw)); Serial.print(" max "); Serial.print(sensor.measurement_time() * 1000.0f, 0); Serial.print(" ms"); Serial.println();
    sensor.set_sensitivity(3.68f);
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    Serial.print("x3.68: mtreg "); Serial.print((unsigned long)(sensor.get_measurement_time_register())); Serial.print(" raw "); Serial.print((unsigned long)(raw)); Serial.print(" max "); Serial.print(sensor.measurement_time() * 1000.0f, 0); Serial.print(" ms"); Serial.println();
    sensor.set_sensitivity(1.0f);
    sensor.reset();                                                 // Clear the data register, () → void
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
}

void loop() {
}
