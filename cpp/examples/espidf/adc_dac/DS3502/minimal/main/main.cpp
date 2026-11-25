#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DS3502.h"

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
    dev_cfg.device_address = DS3502Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DS3502Minimal pot(connection, 15.0f, 0.0f);         // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    static const int codes[] = {0, 32, 64, 96, 127};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–127) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–127
        float v = pot.getVoltage();                     // Read unloaded RW voltage, () → float V
        printf("code %d read %d -> %.3f V\n", codes[i], (int)w, (double)v);
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
}
