#include <Wire.h>
#include <Periph.h>
#include <math.h>

I2CConnection connection(Wire, 0x77);
SPL06Full sensor(connection);                                      // Create SPL06 driver, (connection) → void

static void on_int(uint8_t status) {
    Serial.print("interrupt "); Serial.print("0x"); Serial.print((unsigned)(status), HEX); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    // --- Identity and calibration ---
    // init() checks ID = 0x10; the nine coefficients are factory-trimmed per
    // part and drive every compensated value below.
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
    SPL06Coefficients co;
    sensor.coefficients(co);                                       // Decoded calibration, (coefficients&) → void
    Serial.print("ID 0x10 verified; c0 "); Serial.print((long)(co.c0)); Serial.print(" c1 "); Serial.print((long)(co.c1)); Serial.print(" c00 "); Serial.print((long)(co.c00)); Serial.print(" c10 "); Serial.print((long)(co.c10)); Serial.print(" c01 "); Serial.print((long)(co.c01)); Serial.println();

    // --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
    // The first pressure becomes the reference level, so the altitude column
    // shows how far the sensor moved since start (about 12 Pa per metre).
    float t = 0, t2 = 0, p = 0, h = 0, p0 = 0;
    sensor.read_pressure(p0);                                      // Reference pressure, (pressure_pa&) → bool
    for (int i = 0; i < 20; i++) {
        sensor.read(t, p);                                         // Temperature and pressure, (temperature_c&, pressure_pa&) → bool
        sensor.read_temperature(t2);                               // Temperature only, (temperature_c&) → bool
        sensor.altitude(h, p0);                                    // Altitude vs. reference, (altitude_m&, sea_level_pa) → bool
        Serial.print((long)(i)); Serial.print(" s  "); Serial.print(t, 2); Serial.print(" C ("); Serial.print(t2, 2); Serial.print(" C)  "); Serial.print(p, 2); Serial.print(" Pa  "); Serial.print(h, 2); Serial.print(" m"); Serial.println();
        delay(1000);
    }

    // --- Oversampling: noise and measurement time at 1x, 16x and 128x ---
    // More oversampling means less noise but longer measurements; above 8x
    // the driver sets P_SHIFT automatically.
    static const uint8_t osrs[3] = {1, 16, 128};
    for (int k = 0; k < 3; k++) {
        sensor.set_pressure_config(osrs[k], 1);                    // Pressure oversampling, (oversampling, rate) → bool
        float v[10];
        unsigned long start = millis();
        for (int j = 0; j < 10; j++) {
            sensor.read_pressure(v[j]);                            // Pressure, (pressure_pa&) → bool
        }
        unsigned long per = (millis() - start) / 10;
        float mean = 0, var = 0;
        for (int j = 0; j < 10; j++) mean += v[j] / 10.0f;
        for (int j = 0; j < 10; j++) var += (v[j] - mean) * (v[j] - mean) / 10.0f;
        Serial.print((unsigned long)(osrs[k])); Serial.print("x: mean "); Serial.print(mean, 2); Serial.print(" Pa  sd "); Serial.print(sqrtf(var), 2); Serial.print(" Pa  "); Serial.print((unsigned long)(per)); Serial.print(" ms per reading"); Serial.println();
    }

    // --- Variometer: background mode at 8 Hz ---
    // The chip measures on its own; read_continuous only fetches the latest
    // results, so the host never waits for a conversion it started itself.
    sensor.set_pressure_config(16, 8);                             // 16x, 8 Hz, (oversampling, rate) → bool
    sensor.set_temperature_config(1, 8);                           // 1x, 8 Hz, (oversampling, rate) → bool
    sensor.start_continuous(true, true);                           // Background mode, (pressure, temperature) → bool
    float last = 0;
    for (int i = 0; i < 16; i++) {
        sensor.read_continuous(t, p);                              // Latest results, (temperature_c&, pressure_pa&) → bool
        if (i > 0) {
            Serial.print(p, 2); Serial.print(" Pa  climb "); Serial.print(-(p - last) / 12.0f * 8.0f, 2); Serial.print(" m/s"); Serial.println();
        }
        last = p;
    }
    bool pr = false, tr = false;
    sensor.data_ready(pr, tr);                                     // Ready flags, (pressure_ready&, temperature_ready&) → bool
    Serial.print("ready flags "); Serial.print((unsigned long)(pr)); Serial.print(" "); Serial.print((unsigned long)(tr)); Serial.println();
    sensor.stop();                                                 // Standby, () → bool

    // --- FIFO: let the chip collect 32 results, then read them in one go ---
    // Each entry's LSB marks it as pressure or temperature; the host can sleep
    // while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
    sensor.flush_fifo();                                           // Empty the FIFO, () → bool
    sensor.enable_fifo();                                          // FIFO on, () → bool
    sensor.start_continuous();                                     // Background mode, (pressure = true, temperature = true) → bool
    bool empty = true, full = false;
    for (int i = 0; i < 50 && !full; i++) {
        sensor.fifo_status(empty, full);                           // Wait for full, (empty&, full&) → bool
        delay(100);
    }
    sensor.stop();                                                 // Standby, () → bool
    static SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);                     // Drain FIFO, (entries*, max_entries) → uint8_t
    float psum = 0;
    unsigned np = 0;
    for (uint8_t i = 0; i < n; i++) {
        if (entries[i].kind == SPL06Full::KIND_PRESSURE) { psum += entries[i].value; np++; }
    }
    Serial.print((unsigned long)(n)); Serial.print(" FIFO entries, "); Serial.print((unsigned long)(np)); Serial.print(" pressure, mean "); Serial.print(np ? psum / np : 0.0f, 2); Serial.print(" Pa"); Serial.println();
    sensor.disable_fifo();                                         // FIFO off, () → bool

    // --- Data-ready interrupt ---
    // SDO doubles as the interrupt line; the callback receives the INT_STS bits.
    // Without an INT pin nothing is delivered, so the loop also polls INT_STS.
    sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);           // Pressure-ready interrupt, (source) → bool
    sensor.poll_interrupt();                                       // Clear stale status, () → uint8_t
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
    sensor.start_continuous(true, true);                           // Background mode, (pressure, temperature) → bool
    for (int i = 0; i < 4; i++) {
        delay(500);
        uint8_t status = sensor.poll_interrupt();                  // Read and clear INT_STS, () → uint8_t
        Serial.print("INT_STS "); Serial.print("0x"); Serial.print((unsigned)(status), HEX); Serial.println();
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);          // Disable source, (source) → bool
    sensor.stop();                                                 // Standby, () → bool

    // --- Compensation by hand ---
    // compensate() turns raw register values into SI units with the stored
    // coefficients; useful for logging raw data and converting it later.
    sensor.set_pressure_config(16, 1);                             // Back to 16x, (oversampling, rate) → bool
    sensor.set_temperature_config(1, 1);                           // Back to 1x, (oversampling, rate) → bool
    int32_t praw = 0, traw = 0;
    sensor.read_raw(praw, traw);                                   // Raw results, (praw&, traw&) → bool
    sensor.compensate(praw, traw, 16, 1, t, p);                    // Compensate, (praw, traw, p_oversampling, t_oversampling, temperature_c&, pressure_pa&) → bool
    Serial.print("raw "); Serial.print((long)(praw)); Serial.print(" "); Serial.print((long)(traw)); Serial.print(" -> "); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 2); Serial.print(" Pa"); Serial.println();

    // --- Wrap up ---
    // Standby, empty FIFO and a soft reset leave the chip as after power-up
    // (plus the driver defaults).
    sensor.stop();                                                 // Standby, () → bool
    sensor.flush_fifo();                                           // Empty the FIFO, () → bool
    sensor.reset();                                                // Soft reset and defaults, () → bool
}

void loop() {
}
