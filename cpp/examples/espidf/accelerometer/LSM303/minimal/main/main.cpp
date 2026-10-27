#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LSM303.h"

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

    // Accelerometer at 0x19 and magnetometer at 0x1E share the bus; each gets its own device handle.
    i2c_device_config_t accel_cfg = {};
    accel_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    accel_cfg.device_address = 0x19;
    accel_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t accelDev;
    i2c_master_bus_add_device(bus, &accel_cfg, &accelDev);

    i2c_device_config_t mag_cfg = {};
    mag_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    mag_cfg.device_address = 0x1E;
    mag_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t magDev;
    i2c_master_bus_add_device(bus, &mag_cfg, &magDev);

    I2CConnectionESPIDF accelConn(accelDev);                // Accelerometer connection at 0x19
    I2CConnectionESPIDF magConn(magDev);                    // Magnetometer connection at 0x1E

    LSM303Minimal chip(accelConn, magConn);                 // Create LSM303 driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    while (1) {
        float ax, ay, az, mx, my, mz;
        chip.readAcceleration(ax, ay, az);                  // Read acceleration, (x, y, z) → g, g, g
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        printf("a=%.3f,%.3f,%.3f g  m=%.1f,%.1f,%.1f uT\n",
               ax, ay, az, mx, my, mz);
        vTaskDelay(pdMS_TO_TICKS(200));
    }
}
