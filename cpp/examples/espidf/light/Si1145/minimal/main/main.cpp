#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si1145.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define SI1145_ADDR 0x60

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

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
    dev_cfg.device_address = SI1145_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1145Minimal sensor(connection);                              // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    while (true) {
        Si1145Reading r;
        bool ok = sensor.read(r);                                  // Forced PS + ALS, (reading&) → bool
        printf("prox %u UV %u.%02u vis %u ir %u (ok=%u)\n", (unsigned)r.proximity, uv_whole(r.uv_index),
              uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared, (unsigned)ok);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
