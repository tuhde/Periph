// ESP-IDF example for MCP4XXX (MCP4XXXMinimal), mirrors the Arduino example.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP4XXX.h"

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
    dev_cfg.device_address = 0x58;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP4XXXMinimal pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω)
    pot.init();                                              // Bus check, () → void

    pot.set_wiper(32, 0);                                    // Set wiper, (code 0-128, channel=0) → void
    pot.set_resistance(2200.0f, 0);                          // Set W-B resistance, (ohms, channel=0) → void

    while (true) {
        uint16_t code = pot.get_wiper(0);                        // Read wiper, (channel=0) → uint16_t 0-128
        float ohms = pot.get_resistance(0);                      // Read W-B resistance, (channel=0) → float Ω
        printf("code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
