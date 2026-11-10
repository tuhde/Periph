#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AXP2101.h"

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
    dev_cfg.device_address = 0x34;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AXP2101Minimal chip(connection);                    // Create AXP2101 driver, (connection)

    while (true) {
        float v = chip.batteryVoltage();                // Read battery voltage, () → float V
        uint8_t pct = chip.batteryPercent();            // Read state of charge, () → uint8_t %
        uint8_t state = chip.chargeState();             // Read charge state, () → uint8_t (0 trickle … 5 none)
        bool vbus = chip.vbusPresent();                 // Check input source, () → bool
        printf("%.3f V  %d %%  state=%d  vbus=%s\n", (double)v, (int)pct, (int)state, (vbus) ? "true" : "false");
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
