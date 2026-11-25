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
    // --- Persistent boot counter ---
    // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has no
    // write cycle, so the increment is committed the moment the write ends.
    uint8_t cbuf[4];
    fram.read(0x0000, cbuf, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = (((uint32_t)cbuf[0] << 24) | ((uint32_t)cbuf[1] << 16) | ((uint32_t)cbuf[2] << 8) | cbuf[3]) + 1;
    cbuf[0] = counter >> 24; cbuf[1] = counter >> 16; cbuf[2] = counter >> 8; cbuf[3] = counter;
    fram.write(0x0000, cbuf, 4);                                            // Sequential write, (address, data, length) → bool
    printf("Boot count: %lu\n", (unsigned long)counter);

    // --- Ring-buffer logger, back-to-back with no delays ---
    // 1,000 records of 16 bytes (index + counter + padding) are written one
    // after the other without any pause — an EEPROM would need ACK polling
    // after every page, FRAM does not.
    const uint16_t LOG_BASE = 0x0100;
    const uint16_t RECORD = 16;
    const uint16_t COUNT = 1000;
    const uint16_t RING = 200;   // records that fit before 0x7FFF
    uint8_t rec[16];
    uint32_t t0 = (uint32_t)esp_timer_get_time();
    for (uint16_t i = 0; i < COUNT; i++) {
        for (uint8_t k = 0; k < 16; k++) rec[k] = 0;
        rec[0] = i >> 24; rec[1] = i >> 16; rec[2] = i >> 8; rec[3] = i;
        rec[4] = counter >> 24; rec[5] = counter >> 16; rec[6] = counter >> 8; rec[7] = counter;
        fram.write(LOG_BASE + (i % RING) * RECORD, rec, RECORD);            // Sequential write, (address, data, length) → bool
    }
    uint32_t dt_us = (uint32_t)esp_timer_get_time() - t0;
    if (dt_us == 0) dt_us = 1;
    printf("Logged %u bytes in %lu us = %lu B/s\n", (unsigned)(COUNT * RECORD), (unsigned long)dt_us,
          (unsigned long)((uint64_t)COUNT * RECORD * 1000000ULL / dt_us));

    // --- Verify the last five records ---
    // Read back the newest records from the ring and check their contents.
    bool ok = true;
    for (uint16_t i = COUNT - 5; i < COUNT; i++) {
        fram.read(LOG_BASE + (i % RING) * RECORD, rec, RECORD);             // Sequential read, (address, buf, length) → bool
        uint32_t idx = ((uint32_t)rec[0] << 24) | ((uint32_t)rec[1] << 16) | ((uint32_t)rec[2] << 8) | rec[3];
        if (idx != i) ok = false;
    }
    printf("Last 5 records verified: %s\n", ok ? "yes" : "no");

    // --- Clear the log area ---
    // fill() zeroes the whole ring in chunked transactions.
    fram.fill(LOG_BASE, RING * RECORD, 0);                                  // Fill a range, (address, length, value=0) → bool
    printf("Log cleared; array size: %u bytes\n", (unsigned)fram.size());    // Get array size, () → uint16_t

    // --- Device ID (needs the reserved address 0x7C) ---
    // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F.
    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    fram.read_device_id(idConnection, &manufacturer, &density, &product, 0x50);// Read Device ID, (id_connection, &mfr, &density, &product, address=0x50) → void
    printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", (unsigned)manufacturer, (unsigned)density, (unsigned)product);
}
