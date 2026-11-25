#include <stdio.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "CAT24C512.h"

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
    CAT24C512Full ee(connection);                                       // Create CAT24C512 driver, (connection) → void
    uint8_t cb[4];
    uint8_t pattern[384], back[384];
    for (uint16_t i = 0; i < 384; i++) pattern[i] = (uint8_t)((i * 7) & 0xFF);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    ee.read(0x0000, cb, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = ((uint32_t)cb[0] << 24) | ((uint32_t)cb[1] << 16) | ((uint32_t)cb[2] << 8) | cb[3];
    counter++;
    cb[0] = counter >> 24; cb[1] = counter >> 16; cb[2] = counter >> 8; cb[3] = counter;
    ee.write(0x0000, cb, 4);                                            // Write data, (address, data, length) → bool
    printf("Boot counter: %lu\n", (unsigned long)counter);

    // --- Page-write benchmark: 3 pages, 128-byte aligned ---
    uint32_t t0 = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
    ee.write(0x0080, pattern, 384);                                     // Write data, (address, data, length) → bool
    uint32_t elapsed = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS) - t0;
    printf("Wrote 384 bytes in %lu ms (incl. ACK-polled write cycles)\n", (unsigned long)elapsed);
    ee.read(0x0080, back, 384);                                         // Sequential read, (address, buf, length) → bool
    bool same = true;
    for (uint16_t i = 0; i < 384; i++) same = same && back[i] == pattern[i];
    printf("Read-back matches: %u\n", (unsigned)same);

    // --- A write that crosses a page boundary is refused ---
    printf("write_page across boundary accepted: %u\n", (unsigned)ee.write_page(0x00FC, pattern, 8));
    // ee.write_page(address, data, length) → bool, false across a page boundary
    ee.fill(0x0080, 384);                                               // Fill a range, (address, length, value=0) → bool
    printf("size %lu, page %u, ready %u\n", (unsigned long)ee.size(), (unsigned)ee.page_size(), (unsigned)ee.wait_ready(10));
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
