// UC1611s minimal example: EA DOGXL240-7, ESP-IDF (ESP32-S3 / ESP32, I2C_NUM_0)
// Wiring: SDA=GPIO21, SCL=GPIO22; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), I2C: BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/i2c_master.h>
#include "I2CConnectionESPIDF.h"
#include "UC1611s.h"

#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#define PRINT printf

static void run(UC1611sMinimal& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 240; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, 127);                      // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x * 127 / 239));  // Set pixel, (x 0..239, y 0..127, on=true)
    }
    for (int16_t y = 0; y < 128; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(239, y);                      // Set pixel, (x 0..239, y 0..127, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
    PRINT("done\n");
}

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

    // Two device handles on the same bus: command address 0x38 and data address 0x39.
    i2c_device_config_t cmd_cfg = {};
    cmd_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    cmd_cfg.device_address = 0x38;
    cmd_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t cmd_dev;
    i2c_master_bus_add_device(bus, &cmd_cfg, &cmd_dev);
    i2c_device_config_t data_cfg = cmd_cfg;
    data_cfg.device_address = 0x39;
    i2c_master_dev_handle_t data_dev;
    i2c_master_bus_add_device(bus, &data_cfg, &data_dev);

    I2CConnectionESPIDF cmdConn(cmd_dev);                // Create command connection, (dev)
    I2CConnectionESPIDF dataConn(data_dev);              // Create data connection, (dev)
    static UC1611sMinimal lcd(cmdConn, dataConn);  // Create UC1611s driver, (command, data, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed\n");
        return;
    }
    run(lcd);
}
