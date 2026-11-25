// ESP-IDF example for ES8311 (Minimal) using I2CConnectionESPIDF.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ES8311.h"

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
    dev_cfg.device_address = 0x18;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ES8311Minimal codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
    codec.set_mic_gain(24.0f);                                  // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → void
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void
    codec.set_volume(-20.0f);                                   // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → void
    vTaskDelay(pdMS_TO_TICKS(5000));
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void
}
