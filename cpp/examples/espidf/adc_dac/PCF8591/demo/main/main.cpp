// Auto-generated ESP-IDF example for PCF8591 (Demo).
// Mirrors the Arduino PCF8591_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8591.h"

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
        .device_address  = 0x48,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCF8591Full chip(connection);  // Create PCF8591 driver
    uint8_t adc[4];
    float vadc[4];
    // --- Read all 4 ADC channels, convert to voltage ---
    // V_ADC = V_AGND + raw * (V_REF - V_AGND) / 256.

    chip.read_all(adc);                               // Read all 4 channels, (out[4]) → void
    chip.read_all_voltage(vadc, 3.3f, 0.0f);          // Read all voltages, (out[4], vref, vagnd) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
