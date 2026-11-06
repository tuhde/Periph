#include <stdio.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MB85RC.h"

static void sleep_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }

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
    dev_cfg.device_address = 0x50;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);
    I2CConnectionESPIDF connection(dev, nullptr, nullptr, 2);    // Create I2C connection, (dev, int=null, en=null, reg_bytes=2)
    MB85RCMinimal fram(connection);                                  // Create MB85RC driver, (connection) → void
    uint8_t counter = 0;
    while (1) {
        fram.write_byte(0x0000, counter);                                   // Write a single byte, (address 0–32767, value 0–255) → bool
        uint8_t value = fram.read_byte(0x0000);                             // Read a single byte, (address 0–32767) → uint8_t
        printf("Stored: %u\n", (unsigned)value);
        counter++;
        sleep_ms(1000);
    }
}
