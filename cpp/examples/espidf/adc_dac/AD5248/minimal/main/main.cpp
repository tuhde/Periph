// Auto-generated ESP-IDF example for AD5248 (Minimal).
// Mirrors the Arduino AD5248_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5248.h"

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
    dev_cfg.device_address = 0x2C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5248Minimal pot(connection, 10000.0f, 160.0f);             // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    pot.set_wiper(0, 64);                                    // Set channel 0 wiper, (channel 0-1, code 0-255) → void
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 W-B resistance, (channel 0-1, ohms) → void

    while (true) {
        uint8_t code = pot.get_wiper(0);                     // Read channel 0 wiper, (channel 0-1) → uint8_t 0-255
        float ohms = pot.get_resistance(0);                  // Read channel 0 W-B resistance, (channel 0-1) → float Ω
        printf("ch0 code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
