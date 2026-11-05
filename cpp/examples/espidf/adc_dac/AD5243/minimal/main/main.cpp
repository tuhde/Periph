#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5243.h"

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
    dev_cfg.device_address = AD5243Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5243Minimal pot(connection);                      // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(0, codes[i]);                      // Set wiper position, (channel 0–1, code 0–255) → bool
        uint8_t w = pot.getWiper(0);                    // Read wiper position, (channel 0–1) → uint8_t 0–255
        float r = pot.getResistance(0);                 // Read W–B resistance, (channel 0–1) → float Ω
        printf("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    pot.setResistance(1, 2200.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
}
