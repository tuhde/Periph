// ESP-IDF example for the APDS-9930 (Minimal).

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Apds9930.h"

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
        .device_address  = 0x39,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);                                       // Create I2C connection, (dev) → I2CConnectionESPIDF
    APDS9930Minimal apds(connection);                                          // Create APDS-9930 Minimal, (connection) → APDS9930Minimal
                                                                               // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20

    vTaskDelay(pdMS_TO_TICKS(110));
    while (1) {
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
                                                                               // IR-compensated lux via Ch0/Ch1 difference
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
                                                                               // 16-bit ADC value; higher = closer object
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}