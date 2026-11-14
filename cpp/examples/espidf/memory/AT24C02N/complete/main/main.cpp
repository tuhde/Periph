#include <stdio.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AT24C02N.h"

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
    dev_cfg.device_address = 0x50;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);
    I2CConnectionESPIDF connection(dev);    // Create I2C connection, (dev)

    dev_cfg.device_address = 0x7C;                                // reserved Device ID address
    i2c_master_dev_handle_t id_dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &id_dev);
    I2CConnectionESPIDF idConnection(id_dev);
    AT24C02NFull ee(connection);                                       // Create AT24C02N driver, (connection) → void
    uint8_t data[40], back[40];
    uint8_t page[16];
    for (uint8_t i = 0; i < 40; i++) data[i] = i;
    for (uint8_t i = 0; i < 16; i++) page[i] = i;

    printf("size %lu bytes, page %u bytes\n", (unsigned long)ee.size(), (unsigned)ee.page_size());
    // ee.size() → uint32_t (256), ee.page_size() → uint16_t (8)
    ee.set_max_chunk(16);                                               // Set max bytes per bus transaction, (value 1-32, default 30) → void
    printf("max_chunk %u\n", (unsigned)ee.max_chunk());
    // ee.max_chunk() → uint8_t

    ee.write_byte(0x0010, 0xA5);                                        // Write a single byte, (address 0-255, value 0-255) → bool
                                                                        // ACK-polled until the write cycle ends
    printf("byte 0x%02X\n", (unsigned)ee.read_byte(0x0010));
    // ee.read_byte(address) → uint8_t, random read
    ee.write(0x0040, data, 40);                                         // Write data, (address, data, length) → bool
                                                                        // split at 8-byte page boundaries and max_chunk, ACK-polled per chunk
    ee.read(0x0040, back, 40);                                          // Sequential read, (address, buf, length) → bool
                                                                        // split into max_chunk-byte transactions
    printf("block[39] = %u\n", (unsigned)back[39]);
    ee.write_page(0x00B8, page, 8);                                    // Write within one page, (address, data, length) → bool
                                                                        // false if the data crosses a page boundary
    ee.fill(0x0040, 40, 0xFF);                                          // Fill a range, (address, length, value=0) → bool
                                                                        // page-wise writes of value
    printf("ready %u\n", (unsigned)ee.wait_ready(10));
    // ee.wait_ready(timeout_ms) → bool, ACK-polls the chip
    printf("done\n");
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
