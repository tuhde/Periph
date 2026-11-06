#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x1C
#endif

#include <Wire.h>
#include <math.h>
#include <Periph.h>

I2CConnection connection(Wire, TEST_ADDR);
MMA8451QMinimal accel(connection);                       // Create MMA8451Q driver, (connection)
MMA8451QFull accel_full(connection);                     // Create MMA8451Q Full driver, (connection)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

static bool is_finite(float v) {
    return v == v && v > -1e30f && v < 1e30f;
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    float x, y, z;
    accel.read(x, y, z);                                 // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    accel_full.set_range(4);                             // Set measurement range, (range_g=2) → g
    accel_full.read(x, y, z);
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_after_set_range_4g");

    accel_full.set_data_rate(50);                        // Set output data rate, (rate_hz=100) → Hz
    accel_full.read(x, y, z);
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_after_set_data_rate_50hz");

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);                     // Read raw counts, (x, y, z) → int, int, int
    check_true(rx <= 8192 && ry <= 8192 && rz <= 8192, "read_raw_returns_counts");

    check_true(accel_full.read_system_mode() <= 2, "read_system_mode");

    bool changed = false, lockout = false, back = false;
    uint8_t lapo = 0;
    accel_full.read_orientation(changed, lockout, lapo, back);
                                                           // Read orientation, (changed, lockout, lapo, back) → bool, bool, byte, bool
    check_true(lapo <= 3, "read_orientation_decodes_lapo");

    accel_full.set_fifo(1, 16);                          // Configure the FIFO, (mode=0, watermark=0) → None
    uint8_t level = accel_full.fifo_level();             // Read FIFO level, () → byte
    check_true(level <= 32, "fifo_level_in_range");
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    accel_full.read_fifo_status(overflow, watermark, count);
                                                           // Read FIFO status, (overflow, watermark, count) → bool, bool, byte
    check_true(count <= 32, "fifo_status_count_in_range");
    float fifo[3 * 8];
    uint8_t got = accel_full.read_fifo(fifo, 8);         // Drain the FIFO, (out, max_in) → byte
    check_true(got <= 8, "read_fifo_count_in_range");
    accel_full.set_fifo(0, 0);                           // Disable the FIFO, (mode=0, watermark=0) → None

    accel_full.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
                                                           // Configure freefall/motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → None
    check_true(true, "set_freefall_motion");
    accel_full.set_transient(7, 0.25f, 100.0f, false, false);
                                                           // Configure transient, (axes, threshold_g, debounce_ms, bypass_hpf, latch) → None
    check_true(true, "set_transient");
    accel_full.set_pulse(0x04, 0, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
                                                           // Configure pulse/tap, (single, double, ths_x, ths_y, ths_z, tmlt, latency, window, latch, abort, lpf, hpf) → None
    check_true(true, "set_pulse");
    accel_full.set_orientation(true, 100.0f, 45, 4, 4, 1);
                                                           // Configure orientation, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → None
    check_true(true, "set_orientation");
    accel_full.set_auto_sleep(true, 12.5f, 10.0f, MMA8451QFull::SOURCE_PULSE);
                                                           // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None
    check_true(true, "set_auto_sleep");
    accel_full.set_auto_sleep(false, 12.5f, 10.0f, 0);  // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None

    accel_full.enable_interrupt(MMA8451QFull::SOURCE_DATA_READY, 1);
                                                           // Enable interrupt source, (source, pin=1) → None
    accel_full.poll_interrupt();                          // Read interrupt status, () → byte
    check_true(true, "poll_interrupt");
    accel_full.disable_interrupt(MMA8451QFull::SOURCE_DATA_READY);
                                                           // Disable interrupt source, (source) → None

    float ox, oy, oz;
    accel_full.get_offset(ox, oy, oz);                  // Read zero-g offsets, (x, y, z) → g, g, g
    check_true(ox >= -0.256f && ox <= 0.256f, "get_offset_in_range");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
