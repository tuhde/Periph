#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Hts221.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define HTS221_ADDR 0x5F

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
    dev_cfg.device_address = HTS221_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HTS221Minimal sensor(connection);                               // Create HTS221 driver, (connection) → void
    bool ok = sensor.init();                                        // Read calibration and start one-shot mode, () → bool
    float t = 0, rh = 0;

    while (true) {
        ok = sensor.read(t, rh);                                    // Read temperature and humidity, (temperature_c, humidity_pct) → bool
        printf("temperature %.2f C humidity %.1f %%rH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
