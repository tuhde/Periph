#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "FXOS8700.h"

static void sleepMs(uint32_t ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    printf("%s x=%.3f y=%.3f z=%.3f %s\n", label, x, y, z, unit);
}

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
    dev_cfg.device_address = 0x1E;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.readAccel(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
        compass.readMag(mx, my, mz);                        // Read magnetic field, (x, y, z) → µT, µT, µT
        printVec("accel", ax, ay, az, "g");
        printVec("mag", mx, my, mz, "uT");
        sleepMs(500);
    }
}
