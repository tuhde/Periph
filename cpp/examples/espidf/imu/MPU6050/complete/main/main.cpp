// Auto-generated ESP-IDF example for MPU6050 (Complete).
// Mirrors the Arduino MPU6050_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MPU6050.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x68,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MPU6050Full chip(connection);  // Create MPU6050 driver
    float ax, ay, az, gx, gy, gz, t_c;
    int16_t rx, ry, rz;
    uint8_t wid;
    uint16_t fc;
    uint8_t fifo_buf[256];
    bool ready;
    chip.who_am_i();                                  // Read WHO_AM_I, () → uint8_t
    // expect 0x68
    chip.configure_gyro(1);                           // Configure gyro, (full_scale 0-3) → void
    chip.configure_accel(1);                          // Configure accel, (full_scale 0-3) → void
    chip.configure_dlpf(3);                           // Configure DLPF, (dlpf 0-6) → void
    chip.configure_sample_rate(9);                    // Configure sample rate, (divider 0-255) → void
    // output rate = 1 kHz / (1 + divider) when DLPF active
    chip.accel_raw(rx, ry, rz);                       // Read raw accel, (x, y, z) → int16_t
    chip.gyro_raw(rx, ry, rz);                        // Read raw gyro, (x, y, z) → int16_t
    chip.accel(ax, ay, az);                           // Read 3-axis acceleration, (x, y, z) → void m/s²
    chip.gyro(gx, gy, gz);                            // Read 3-axis angular rate, (x, y, z) → void rad/s
    chip.temperature();                               // Read die temperature, () → float °C
    chip.data_ready();                                // Check data ready, () → bool
    chip.set_sleep(false);                            // Exit sleep mode, (sleep) → void
    chip.set_standby(false, false, false, false, false, false);  // Set per-axis standby, (xa, ya, za, xg, yg, zg) → void
    chip.enable_fifo(true, true, false);              // Enable FIFO, (gyro, accel, temp) → void
    chip.fifo_count();                                // Read FIFO count, () → uint16_t
    chip.read_fifo(fifo_buf, 256);                    // Read FIFO data, (buf, max) → uint16_t
    chip.reset_fifo();                                // Reset FIFO, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
