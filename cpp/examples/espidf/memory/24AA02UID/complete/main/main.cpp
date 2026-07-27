// Auto-generated ESP-IDF example for 24AA02UID (Complete).
// Mirrors the Arduino 24AA02UID_Complete example using the
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
    uint8_t buf[8];
    uint8_t page_data[] = { 0x01, 0x02, 0x03, 0x04 };
    uint8_t cross[] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE };
    chip.read_uid(uid);                               // Read 32-bit unique serial number, (buf[4]) → void
    // reads 4 bytes at 0xFC-0xFF
    chip.read_manufacturer_code();                    // Read manufacturer code, () → byte
    // reads 0xFA; expect 0x29 (Microchip)
    chip.read_device_code();                          // Read device code, () → byte
    // reads 0xFB; expect 0x41
    chip.read_byte(0x00);                             // Read a single byte, (address=0x00-0x7F) → byte
    // random read at user EEPROM address
    chip.write_byte(0x10, 0xA5);                      // Write a single byte, (address, value) → void
    // byte write + delay until complete (max 5 ms)
    chip.read_byte(0x10);                             // Read a single byte, (address=0x00-0x7F) → byte
    chip.read(0x20, buf, 8);                          // Sequential read, (address, buf, length) → void
    // reads 8 bytes starting at address
    chip.write_page(0x40, page_data, 4);              // Page write, (address, data, length) → void
    // writes up to 8 bytes within one page
    chip.write(0x44, cross, 5);                       // Arbitrary-length write, (address, data, length) → void
    // splits at 8-byte page boundaries; waits for each chunk
    vTaskDelay(pdMS_TO_TICKS(1000));
}
