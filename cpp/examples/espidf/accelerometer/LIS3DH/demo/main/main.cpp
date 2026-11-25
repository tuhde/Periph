#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LIS3DH.h"

static void lis3dh_report(float pitch, float roll, float mag, int16_t temp, uint8_t level) {
    printf("pitch=%+.1f roll=%+.1f |a|=%.3f g  T=%d C  fifo=%d\n",
           pitch, roll, mag, temp, level);
}

static void lis3dh_event(const char* what) {
    printf("%s\n", what);
}

static uint32_t lis3dh_now_ms() { return (uint32_t)(esp_timer_get_time() / 1000); }

static void lis3dh_sleep_ms(uint32_t ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x18;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LIS3DHFull accel(connection);                    // Create LIS3DH driver, (connection)
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
}
