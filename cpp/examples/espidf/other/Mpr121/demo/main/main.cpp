// ESP-IDF demo example for the MPR121 — 12-button musical keyboard.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Mpr121.h"

static const char* NOTES[12] = {
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"
};

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
    dev_cfg.device_address = 0x5A;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);                                       // Create I2C connection, (dev) → I2CConnectionESPIDF
    MPR121Full mpr(connection);                                                 // Create MPR121 Full, (connection) → MPR121Full
    uint16_t previous = 0;

    while (1) {
        uint16_t mask = mpr.touched();                                          // Read 12-bit touch bitmask, () → uint16_t bitmask
        uint16_t newly_pressed = mask & ~previous;
        uint16_t newly_released = (~mask) & previous;
        for (uint8_t n = 0; n < 12; n++) {
            if (newly_pressed & (1u << n)) {
                printf("NOTE ON:  %s\n", NOTES[n]);
            }
            if (newly_released & (1u << n)) {
                printf("NOTE OFF: %s\n", NOTES[n]);
            }
        }
        previous = mask;
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}
