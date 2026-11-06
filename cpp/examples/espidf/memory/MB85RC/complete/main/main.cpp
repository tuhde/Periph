#include <stdio.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MB85RC.h"

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
    I2CConnectionESPIDF connection(dev, nullptr, nullptr, 2);    // Create I2C connection, (dev, int=null, en=null, reg_bytes=2)

    dev_cfg.device_address = 0x7C;                                // reserved Device ID address
    i2c_master_dev_handle_t id_dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &id_dev);
    I2CConnectionESPIDF idConnection(id_dev);
    MB85RCFull fram(connection);                                  // Create MB85RC driver, (connection) → void
    printf("Size: %u bytes\n", (unsigned)fram.size());                       // Get array size, () → uint16_t
                                                                            // always 32768 for the MB85RC256V
    fram.set_max_chunk(16);                                                 // Set max bytes per bus transaction, (max_chunk 1–32=30) → void
    printf("max_chunk: %u\n", (unsigned)fram.max_chunk());                   // Get max bytes per bus transaction, () → uint8_t

    fram.write_byte(0x0010, 0xA5);                                          // Write a single byte, (address 0–32767, value 0–255) → bool
                                                                            // no write cycle, no polling
    uint8_t b = fram.read_byte(0x0010);                                     // Read a single byte, (address 0–32767) → uint8_t
                                                                            // random read
    printf("Byte: 0x%02X\n", (unsigned)b);

    uint8_t block[40];
    for (uint8_t i = 0; i < 40; i++) block[i] = i;
    fram.write(0x0100, block, 40);                                          // Sequential write, (address, data, length) → bool
                                                                            // split into max_chunk-byte transactions
    uint8_t back[40];
    fram.read(0x0100, back, 40);                                            // Sequential read, (address, buf, length) → bool
                                                                            // split into max_chunk-byte transactions
    printf("Block[39]: %u\n", (unsigned)back[39]);

    fram.fill(0x0100, 40, 0xFF);                                            // Fill a range, (address, length, value=0) → bool
                                                                            // writes value over the range in chunks
    fram.read(0x0100, back, 8);                                             // Sequential read, (address, buf, length) → bool
    printf("After fill: 0x%02X\n", (unsigned)back[0]);

    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    fram.read_device_id(idConnection, &manufacturer, &density, &product, 0x50);// Read Device ID, (id_connection, &mfr, &density, &product, address=0x50) → void
                                                                            // reads 3 bytes via reserved slave address 0x7C
    printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", (unsigned)manufacturer, (unsigned)density, (unsigned)product);
}
