#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x60);
MPL3115A2Full sensor(connection);                                  // Create MPL3115A2 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    float p = 0, t = 0, alt = 0;
    sensor.init();                                                 // Reset and apply defaults, () → bool
    // --- Weather and altitude station ---
    // Sets the local sea-level pressure, takes three barometer readings, switches to
    // altimeter mode for three altitude readings, collects 16 samples in the circular
    // FIFO and prints their minimum, maximum and mean, then arms a temperature
    // threshold on INT1 and reports the captured extremes.
    sensor.set_sea_level_pressure(101325.0f);                      // Sea-level reference, (pa) → bool
    for (int i = 0; i < 3; i++) {
        sensor.read(p, t);                                         // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
        Serial.print(p, 2); Serial.print(" Pa "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
        delay(5000);
    }
    for (int i = 0; i < 3; i++) {
        sensor.read_altitude(alt);                                 // Read altitude, (altitude_m&) → bool
        Serial.print("altitude "); Serial.print(alt, 2); Serial.print(" m"); Serial.println();
    }
    sensor.set_mode(false);                                        // Back to barometer, (altimeter) → bool
    sensor.set_oversample(16);                                     // Faster conversions, (ratio) → bool
    sensor.fifo_setup(MPL3115A2Full::FIFO_CIRCULAR, 16);           // Circular FIFO, watermark 16, (mode, watermark) → bool
    sensor.start_continuous(0);                                    // One sample per second, (step_exp 0-15) → bool
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    while (!watermark) {
        delay(1000);
        sensor.fifo_status(overflow, watermark, count);
    }
    float fv[32], ft[32];
    uint8_t n = sensor.fifo_read(fv, ft, 32);                      // Drain the FIFO, (values*, temperatures*, max) → uint8_t
    float lo = fv[0], hi = fv[0], sum = 0;
    for (uint8_t i = 0; i < n; i++) { if (fv[i] < lo) lo = fv[i]; if (fv[i] > hi) hi = fv[i]; sum += fv[i]; }
    Serial.print("n="); Serial.print((unsigned long)(n)); Serial.print(" min "); Serial.print(lo, 2); Serial.print(" max "); Serial.print(hi, 2); Serial.print(" mean "); Serial.print(sum / (n ? n : 1), 2); Serial.print(" Pa"); Serial.println();
    sensor.set_target(0.0f, 0.0f, (int8_t)(ft[n - 1] + 1), 0);     // Temperature target, (value, window, temperature_c, temperature_window) → bool
    sensor.enable_interrupt(MPL3115A2Full::SOURCE_TTH, 1);         // Route temperature threshold to INT1, (source, pin) → bool
    Serial.print("INT_SOURCE "); Serial.print("0x"); Serial.print((unsigned)(sensor.poll_interrupt()), HEX); Serial.println();
    sensor.stop_continuous();                                      // Back to STANDBY, () → bool
    sensor.fifo_setup(MPL3115A2Full::FIFO_OFF);                    // FIFO off, (mode) → bool
    float pmin = 0, pmax = 0, tmin = 0, tmax = 0;
    sensor.read_min_max(pmin, pmax, tmin, tmax);                   // Captured extremes, (p_min&, p_max&, t_min&, t_max&) → bool
    Serial.print("min "); Serial.print(pmin, 2); Serial.print(" Pa max "); Serial.print(pmax, 2); Serial.print(" Pa"); Serial.println();
}

void loop() {
}
