#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LSM303.h"

#define PRINTF(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))

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

    i2c_device_config_t accel_cfg = {};
    accel_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    accel_cfg.device_address = LSM303Minimal::I2C_ADDRESS_ACCEL;
    accel_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t accel_dev;
    i2c_master_bus_add_device(bus, &accel_cfg, &accel_dev);

    i2c_device_config_t mag_cfg = {};
    mag_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    mag_cfg.device_address = LSM303Minimal::I2C_ADDRESS_MAG;
    mag_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t mag_dev;
    i2c_master_bus_add_device(bus, &mag_cfg, &mag_dev);

    I2CConnectionESPIDF accel_connection(accel_dev);
    I2CConnectionESPIDF mag_connection(mag_dev);
    LSM303Minimal accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)


    while (true) {
        float ax, ay, az;
        float mx, my, mz;
        accel.readAcceleration(ax, ay, az);      // Read acceleration, (x, y, z) → g, g, g
        accel.readMagnetic(mx, my, mz);            // Read magnetic field, (x, y, z) → uT, uT, uT
        PRINTF("accel x=%.4f y=%.4f z=%.4f g\n", (double)ax, (double)ay, (double)az);
        PRINTF("mag   x=%.2f y=%.2f z=%.2f uT\n", (double)mx, (double)my, (double)mz);
        SLEEP_MS(1000);
    }
}
