#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP3422.h"

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
    dev_cfg.device_address = MCP3422Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP3422Minimal adc(connection);                     // Create MCP3422 driver, (connection)
    for (int i = 0; i < 10; i++) {
        int32_t raw0 = adc.readRaw(0);                  // Read a signed code, (channel 0–1) → int32_t
        float v1 = adc.readVoltage(1);                  // Read the differential voltage, (channel 0–1) → float V
        printf("CH1 raw %d, CH2 %.6f V\n", (int)raw0, (double)v1);
        float both[2];
        bool ok = adc.readAll(both);                    // Read both channels, (float out[2]) → bool
        printf("all %d %.6f %.6f\n", (int)ok, (double)both[0], (double)both[1]);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
