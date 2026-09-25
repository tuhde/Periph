#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MPU9255.h"

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
    dev_cfg.device_address = 0x68;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    i2c_device_config_t mag_dev_cfg = {};
    mag_dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    mag_dev_cfg.device_address = 0x0C;  // AK8963, same bus, reached via I²C bypass
    mag_dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t magDev;
    i2c_master_bus_add_device(bus, &mag_dev_cfg, &magDev);

    I2CConnectionESPIDF connection(dev);
    I2CConnectionESPIDF magConnection(magDev);

    // --- Configure for motion-triggered wake logger ---
    // 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
    // wake-ups from vibration; once motion fires, the full 6-axis sensor suite
    // (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
    MPU9255Full imu(connection, magConnection);       // Create MPU9255 driver, (connection, magConnection) → void
    imu.configure_wake_on_motion(64, 31.25f);         // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void

    TickType_t last_heartbeat = xTaskGetTickCount();

    while (1) {
        // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
        // configure_wake_on_motion already disabled the gyro and put the chip
        // in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
        // state without forcing any further register writes.
        while (!imu.motion_detected()) {               // Check motion detected, () → bool
            TickType_t now = xTaskGetTickCount();
            if ((now - last_heartbeat) * portTICK_PERIOD_MS >= 1000) {
                printf("sleeping...\n");
                last_heartbeat = now;
            }
            vTaskDelay(pdMS_TO_TICKS(200));
        }

        // --- Wake phase: re-arm the full 6-axis + mag stack ---
        // PWR_MGMT_1=0x01 clears CYCLE; PWR_MGMT_2=0x00 re-enables all three gyro axes.
        imu.set_sleep(false);                          // Wake from sleep, (sleep=true) → void
        imu.configure_gyro(1);                         // Configure gyro range, (full_scale=0) → void
        imu.configure_accel(1);                        // Configure accel range, (full_scale=0) → void
        imu.enable_mag(16, 6);                         // Initialize magnetometer, (bits=16, mode=6) → void

        // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
        // Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
        printf("--- motion detected ---\n");
        TickType_t end = xTaskGetTickCount() + pdMS_TO_TICKS(5000);
        while (xTaskGetTickCount() < end) {
            while (!imu.data_ready()) {}               // Check data ready flag, () → bool

            float ax, ay, az, gx, gy, gz, mx, my, mz;
            imu.accel(ax, ay, az);                     // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
            imu.gyro(gx, gy, gz);                      // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
            imu.mag(mx, my, mz);                       // Read 3-axis magnetic field, (float&, float&, float&) → void µT

            float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
            float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;
            float heading = atan2f(my, mx) * 180.0f / 3.141592653589793f;

            printf("%.1f      %.1f      %.1f      |g|=%.2f\n",
                   roll, pitch, heading, sqrtf(gx * gx + gy * gy + gz * gz));
            vTaskDelay(pdMS_TO_TICKS(100));
        }

        // --- Return to low-power wake-on-motion mode ---
        imu.configure_wake_on_motion(64, 31.25f);      // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void
        last_heartbeat = xTaskGetTickCount();
    }
}