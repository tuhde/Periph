#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "CS43L22.h"

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
    dev_cfg.device_address = CS43L22Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    CS43L22Minimal dac(connection);                  // Create CS43L22 driver, (connection, resPin=nullptr)
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.start();                                     // Power up the DAC once MCLK is running, () → void
    dac.setVolume(-20.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.mute(false);                                 // Unmute the headphone outputs, (on=true) → void
    for (int i = 0; i < 5; i++) {
        float v = dac.getVolume();                   // Read back master volume, () → float dB
        printf("volume %d dB\n", (int)v);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    dac.stop();                                      // Power down the DAC, () → void
}
