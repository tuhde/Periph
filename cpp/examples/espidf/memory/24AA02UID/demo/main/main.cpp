// Auto-generated ESP-IDF example for 24AA02UID (Demo).
// Mirrors the Arduino 24AA02UID_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "24AA02UID.h"

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
        .device_address  = 0x50,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    EEPROM24AA02UIDFull chip(connection);  // Create 24AA02UID driver
    uint8_t uid[4];
    uint8_t existing[4];
    uint32_t counter;
    uint8_t updated[4];
    // --- Identify the device by its factory UID and maintain a 4-byte boot counter in user EEPROM ---
    // The user EEPROM at 0x00-0x7F is rewritable; the UID above 0x80 is permanent.

    chip.read_uid(uid);                               // Read 32-bit unique serial number, (buf[4]) → void
    // reads 4 bytes at 0xFC-0xFF
    chip.read(0x00, existing, 4);                     // Sequential read, (address, buf, length) → void
    // reads 4 bytes from user EEPROM
    chip.write(0x00, updated, 4);                     // Arbitrary-length write, (address, data, length) → void
    // writes 4 bytes
    chip.read_uid(uid);                               // Read 32-bit unique serial number, (buf[4]) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
