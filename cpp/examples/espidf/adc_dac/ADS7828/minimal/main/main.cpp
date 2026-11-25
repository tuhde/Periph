// Auto-generated ESP-IDF example for ADS7828 (Minimal).
// Mirrors the Arduino ADS7828_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS7828.h"

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
    dev_cfg.device_address = 0x48;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS7828Minimal adc(connection);                         // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (1) {
        uint16_t raw = adc.readRaw(0);                              // Read CH0 raw code, (channel=0–7) → uint16_t
        float v0 = adc.readVoltage(0);                              // Read CH0 voltage, (channel=0–7) → float V
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
        printf("raw=%u v0=%.3f V ch7=%.3f V\n", raw, (double)v0, (double)all[7]);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
