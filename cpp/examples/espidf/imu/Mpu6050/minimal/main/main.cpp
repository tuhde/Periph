// Auto-generated ESP-IDF example for Mpu6050 (Minimal).
// Mirrors the Arduino Mpu6050_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Mpu6050.h"

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
    MPU6050Minimal chip(connection);  // Create Mpu6050 driver
    float ax, ay, az, gx, gy, gz, t_c;
    int16_t rx, ry, rz;
    uint8_t wid;
    uint16_t fc;
    uint8_t fifo_buf[256];
    bool ready;
    while (1) {
    chip.accel(ax, ay, az);                           // Read 3-axis acceleration, (x, y, z) → void m/s²
    chip.gyro(gx, gy, gz);                            // Read 3-axis angular rate, (x, y, z) → void rad/s
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
