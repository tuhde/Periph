#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "LIS3DH.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

I2CConnectionLinux connection(TEST_I2C_BUS, 0x18);
LIS3DHFull accel(connection);                                // Create LIS3DH driver, (connection)
static void lis3dh_report(float pitch, float roll, float mag, int16_t temp, uint8_t level) {
    printf("pitch=%+.1f roll=%+.1f |a|=%.3f g  T=%d C  fifo=%d\n",
           pitch, roll, mag, temp, level);
}

static void lis3dh_event(const char* what) {
    printf("%s\n", what);
}

static uint32_t lis3dh_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void lis3dh_sleep_ms(uint32_t ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000};
    nanosleep(&ts, nullptr);
}

int main(void) {
    // --- Configure ±2 g / 50 Hz high-resolution and verify the part ---
    // The self-test only passes on healthy silicon, so run it before
    // arming anything; 50 Hz keeps the self-test window short (2 samples).
    accel.set_data_rate(50);                                 // Set output data rate, (rate_hz) → Hz
    accel.set_range(2);                                      // Set full-scale range, (range_g) → g
    accel.set_mode(2);                                       // Set operating mode, (mode) → None
    bool st = accel.self_test(1);                            // Run self-test, (mode) → bool
    printf("self-test: %s\n", st ? "PASS" : "FAIL");

    // --- Arm the double-tap and free-fall detectors on INT1 ---
    // Both sources are latched so a transient cannot slip past the 100 ms
    // polling loop; the click window (200 ms) covers a slow double tap.
    accel.set_click(0, 0x04, 0.8f, 20, 40, 200, true);       // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms, latch) → None
                                                            // double tap on Z with an 0.8 g threshold
    accel.set_free_fall(1, 0.35f, 60);                       // Configure free-fall, (generator, threshold_g, duration_ms) → None
                                                            // 0.35 g threshold held for 60 ms
    accel.set_latch(1, true);                                // Latch generator 1, (generator, enabled) → None
    accel.enable_interrupt(LIS3DHFull::SOURCE_CLICK, 1);    // Route source to INT pin, (source, pin) → None
    accel.enable_interrupt(LIS3DHFull::SOURCE_IA1, 1);      // Route source to INT pin, (source, pin) → None

    // --- Run the FIFO in stream mode with a 16-sample watermark ---
    // Stream mode overwrites the oldest samples once full, so a slow
    // reader always sees recent motion instead of going stale.
    accel.set_fifo(LIS3DHFull::FIFO_STREAM, 16, false);      // Configure FIFO, (mode, watermark, trigger_int2) → None
                                                            // stream mode with a 16-sample watermark

    // --- 60-second tilt / tap / free-fall monitor ---
    // The board is tilted, double-tapped and dropped within the 60 s
    // window. Between events the vector magnitude sits at ≈1.00 g.
    uint32_t start = lis3dh_now_ms();
    uint32_t last = start;
    while (lis3dh_now_ms() - start < 60000) {
        uint32_t now = lis3dh_now_ms();

        // Drain the FIFO every 100 ms and characterise the average attitude.
        if (now - last >= 100) {
            uint8_t level = accel.fifo_level();              // Read FIFO level, () → int
            float xb[32], yb[32], zb[32];
            uint8_t n = accel.read_fifo(xb, yb, zb, 32);     // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) → int
            if (n > 0) {
                float ax = 0, ay = 0, az = 0;
                for (uint8_t i = 0; i < n; i++) { ax += xb[i]; ay += yb[i]; az += zb[i]; }
                ax /= n; ay /= n; az /= n;
                float mag = sqrtf(ax * ax + ay * ay + az * az);
                float pitch = atan2f(ax, sqrtf(ay * ay + az * az)) * 57.2957795f;
                float roll = atan2f(ay, az) * 57.2957795f;
                int16_t temp = accel.read_temperature_delta();  // Read relative temperature, () → int °C
                lis3dh_report(pitch, roll, mag, temp, level);
            }

            uint16_t isr = accel.poll_interrupt();           // Read interrupt status, () → int
            if (isr & LIS3DHFull::SOURCE_CLICK) lis3dh_event("DOUBLE TAP");
            if (isr & LIS3DHFull::SOURCE_IA1)   lis3dh_event("FREE FALL");
            last = now;
        }

        lis3dh_sleep_ms(10);
    }
    printf("done\n");
    return 0;
}
