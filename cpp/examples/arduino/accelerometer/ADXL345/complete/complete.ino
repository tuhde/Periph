#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/accelerometer/ADXL345.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x53);
    ADXL345Full accel(connection);                           // Create ADXL345 Full driver, (connection, spi=false)

    accel.set_range(4);                                      // Set measurement range, (range_g) → g
                                                               // selects ±4 g; FULL_RES is preserved so scale stays 3.9 mg/LSB
    accel.set_data_rate(200);                                // Set output data rate, (rate_hz) → Hz
                                                               // picks the nearest supported value (200 Hz)
    accel.set_low_power(false);                              // Set low-power mode, (enabled) → None
                                                               // normal-power mode; LOW_POWER bit in BW_RATE cleared
    accel.calibrate_offset(0.0f, 0.0f, 1.0f, 64);            // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → g, g, g
                                                               // averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
    accel.set_tap_detection(0.5f, 10.0f);                    // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → g, ms
                                                               // 0.5 g threshold, 10 ms duration, all axes, no suppress
    accel.set_double_tap(50.0f, 200.0f);                     // Configure double-tap, (latency_ms, window_ms) → ms, ms
                                                               // 50 ms latency, 200 ms window between taps
    accel.set_fifo_mode(ADXL345Full::FIFO_STREAM, 16);       // Configure FIFO, (mode, samples=16) → None
                                                               // stream mode, watermark 16 entries
    accel.set_interrupt(ADXL345Full::INT_WATERMARK, true, 1);// Configure interrupt, (source, enabled, pin=1) → None
                                                               // enable watermark interrupt on INT1

    float x, y, z;
    accel.read(x, y, z);                                     // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                               // single-shot burst read of all 6 data bytes
    float xs[32], ys[32], zs[32];
    uint8_t n = accel.read_fifo(xs, ys, zs, 32);             // Drain the FIFO, (x_buf, y_buf, z_buf, max_samples) → count
                                                               // up to 32 (x, y, z) samples in *g*
    uint8_t count = accel.fifo_count();                      // FIFO entries available, () → count
                                                               // from FIFO_STATUS register
    uint8_t src = accel.read_interrupt_source();             // Read interrupt source, () → bitmask
                                                               // bitmask of active INT_* sources; clears latches

    accel.self_test(false);                                  // Toggle self-test, (enabled) → None
                                                               // SELF_TEST bit in DATA_FORMAT cleared
    accel.set_sleep(false);                                  // Set sleep mode, (enabled, wakeup_hz=8) → Hz
                                                               // wake up; no further state changes
    accel.set_link_mode(false);                              // Set activity/inactivity link, (enabled) → None
                                                               // Link bit in POWER_CTL cleared
    accel.set_auto_sleep(false);                             // Set auto-sleep, (enabled) → None
                                                               // AUTO_SLEEP bit cleared

    (void)n; (void)count; (void)src;
    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }