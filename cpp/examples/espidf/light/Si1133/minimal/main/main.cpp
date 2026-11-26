#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si1133.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define SI1133_ADDR 0x55

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = SI1133_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1133Minimal sensor(connection);                              // Create Si1133 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
    while (true) {
        int32_t uv = 0, white = 0;
        bool ok = sensor.read(uv, white);                          // Forced measurement, (uv&, white&) → bool
        printf("UV %ld white %ld (ok=%u)\n", (long)(uv), (long)(white), (unsigned)(ok));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
