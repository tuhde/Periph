#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XFull sensor(connection);                         // Create VL6180X Full driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    // --- Identification ---
    uint8_t major = 0, minor = 0;
    sensor.revision(major, minor);                          // Model revision, (uint8_t& major, uint8_t& minor) → void
    logPrintf("model 0x%02X revision %u.%u\n", (unsigned)sensor.model_id(), (unsigned)major, (unsigned)minor); // Model ID, () → uint8_t

    // --- Single-shot ranging at 5 Hz for 10 s ---
    uint8_t mm = 0, err = 0;
    for (int i = 0; i < 50; i++) {
        if (sensor.read_range(mm)) logPrintf("%u mm\n", (unsigned)mm); // Single-shot range, (uint8_t& mm) → bool
        delay(200);
    }

    // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
    sensor.start_continuous_range(50);                      // Start continuous ranging, (uint16_t period_ms) → bool
    for (int i = 0; i < 40; i++) {
        bool ok = sensor.read_range_continuous(mm);         // Wait for next sample, (uint8_t& mm) → bool
        logPrintf("%u mm (%s)\n", (unsigned)mm, ok ? "valid" : "error");
    }
    sensor.stop_continuous_range();                         // Stop continuous ranging, () → void
    delay(200);

    // --- Out-of-window interrupt for 30-80 mm ---
    sensor.set_range_thresholds(30, 80);                    // Range thresholds, (uint8_t low, uint8_t high) → bool
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW); // Enable an interrupt, (uint8_t source, uint8_t mode) → bool
    sensor.start_continuous_range(50);
    for (int i = 0; i < 50; i++) {
        if (sensor.poll_interrupt() & VL6180XFull::SOURCE_RANGE) logPrintf("range out of window\n"); // Read and clear, () → uint8_t
        delay(100);
    }
    sensor.stop_continuous_range();
    delay(200);
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_NEW_SAMPLE_READY);

    // --- Light meter at gain 1 and 20 ---
    static const uint8_t gains[2] = { 6, 0 };
    for (int i = 0; i < 2; i++) {
        sensor.set_als_gain(gains[i]);                      // ALS gain code, (uint8_t code) → bool
        float lux = sensor.read_lux();                      // Single-shot ALS in lux, () → float lux
        logPrintf("gain code %u: %d.%02d lux\n", (unsigned)gains[i], (int)lux, (int)(lux * 100) % 100);
    }
    sensor.set_als_gain(6);

    // --- Interleaved ALS + range for 5 s ---
    sensor.set_interleaved(true);                           // Interleaved ALS + range, (bool on) → void
    sensor.start_continuous_als(100);                       // Start continuous ALS, (uint16_t period_ms) → bool
    delay(5000);
    sensor.stop_continuous_als();                           // Stop continuous ALS, () → void
    sensor.set_interleaved(false);

    // --- Calibration and diagnostics ---
    sensor.set_range_offset(0);                             // Part-to-part offset, (int8_t mm) → void
    sensor.set_crosstalk_rate(0.0f);                        // Crosstalk rate, (float rate_mcps) → bool
    sensor.set_als_integration_time(100);                   // ALS integration time, (uint16_t ms) → bool
    sensor.set_als_scaler(1);                               // ALS result scaler, (uint8_t scaler) → bool
    sensor.recalibrate_vhv();                               // Recalibrate VHV, () → bool
    uint16_t raw = 0;
    sensor.read_als_raw(raw);                               // Single-shot ALS count, (uint16_t& raw) → bool
    sensor.read_range_status(mm, err);                      // Range and error code, (uint8_t& mm, uint8_t& err) → bool
    logPrintf("als raw %u, last range %u mm (error %u), offset %d mm\n", (unsigned)raw, (unsigned)mm, (unsigned)err, (int)sensor.get_range_offset()); // Read the offset, () → int8_t mm
    uint16_t hist[8];
    sensor.read_history(hist);                              // History buffer, (uint16_t out[8]) → void

    // --- Change the device address and back (volatile) ---
    sensor.set_address(0x30);                               // Change address, (uint8_t address) → bool
    I2CConnection moved(Wire, 0x30, nullptr, nullptr, 2);
    VL6180XFull sensorMoved(moved);
    sensorMoved.init();
    logPrintf("on 0x30: model 0x%02X\n", (unsigned)sensorMoved.model_id());// Model ID at the new address, () → uint8_t
    sensorMoved.set_address(0x29);                          // Back to the power-on 0x29
}

void loop() {}
