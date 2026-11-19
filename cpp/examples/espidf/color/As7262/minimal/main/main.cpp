#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "As7262.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x49

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
    dev_cfg.device_address = AHT_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AS7262Minimal sensor(connection);                               // Create AS7262 driver, (connection) → void
    AS7262Channels ch = {};
    if (!sensor.begin()) {                                          // Reset and configure defaults, () → bool
        printf("AS7262 not found\n");
    }

    while (true) {
        bool ok = sensor.read(ch);                                  // Read six calibrated channels, (ch) → bool
        printf("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f (ok=%lu)\n", (double)(ch.violet), (double)(ch.blue), (double)(ch.green), (double)(ch.yellow), (double)(ch.orange), (double)(ch.red), (unsigned long)(ok));
        printf("%.0f C\n", (double)(sensor.temperature()));
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
