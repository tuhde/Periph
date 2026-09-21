#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MPU9250.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x68,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    i2c_device_config_t mag_dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x0C,  // AK8963, same bus, reached via I²C bypass
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t magDev;
    i2c_master_bus_add_device(bus, &mag_dev_cfg, &magDev);

    I2CConnectionESPIDF connection(dev);
    I2CConnectionESPIDF magConnection(magDev);

    // --- Configure for tilt and heading estimation ---
    // ±4g / ±500dps trade sensitivity for headroom against sharper motion than
    // the ±2g / ±250dps defaults tolerate; 16-bit continuous magnetometer mode
    // keeps a fresh heading available on every poll.
    MPU9250Full imu(connection, magConnection);       // Create MPU9250 driver, (connection, magConnection) → void
    imu.configure_accel(1);                           // Configure accel range, (full_scale=0) → void
    imu.configure_gyro(1);                            // Configure gyro range, (full_scale=0) → void
    imu.enable_mag(16, 6);                            // Initialize magnetometer, (bits=16, mode=6) → void

    printf("roll     pitch    heading  |accel|  |gyro|\n");

    while (1) {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while (!imu.data_ready()) {                   // Check data ready flag, () → bool
        }

        float ax, ay, az, gx, gy, gz, mx, my, mz;
        imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → void µT

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        float heading = atan2f(my, mx) * 180.0f / 3.141592653589793f;

        float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
        float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

        printf("%.1f      %.1f      %.1f      %.3f    %.3f\n", roll, pitch, heading, accel_mag, gyro_mag);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}