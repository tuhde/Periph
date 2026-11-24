#include <math.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Isl29125.h"

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
    dev_cfg.device_address = Isl29125Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Isl29125Full sensor(connection);                                // Create ISL29125 driver, (connection)

    // --- Colour sampler ---
    // Prints the device ID, samples R/G/B as a normalised colour, compares the
    // 375 lux and 10 000 lux ranges and the 12/16-bit resolutions, varies the IR
    // compensation, reads green only, arms a threshold interrupt on green with
    // persistence 2 (polled through pollInterrupt), then powers down and resets.
    printf("device id 0x%02X\n", (unsigned)(sensor.deviceId()));    // Read DEVICE_ID, () → uint8_t
    Isl29125Counts c = {0, 0, 0};
    for (int i = 0; i < 20; i++) {
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        uint16_t top = c.red > c.green ? c.red : c.green;
        if (c.blue > top) top = c.blue;
        if (top == 0) top = 1;
        printf("#%02X%02X%02X\n", (unsigned)((unsigned long)c.red * 255 / top), (unsigned)((unsigned long)c.green * 255 / top), (unsigned)((unsigned long)c.blue * 255 / top));
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    const uint16_t ranges[2] = {375, 10000};
    for (uint8_t i = 0; i < 2; i++) {
        sensor.setRange(ranges[i]);                                 // Set the range, (lux) → bool
        vTaskDelay(pdMS_TO_TICKS(400));
        sensor.readRgb(c);                                          // Read without waiting, (counts&) → void
        printf("%lu lux range G %lu R %lu B %lu\n", (unsigned long)(ranges[i]), (unsigned long)(c.green), (unsigned long)(c.red), (unsigned long)(c.blue));
    }
    const uint8_t bits[2] = {12, 16};
    for (uint8_t i = 0; i < 2; i++) {
        sensor.setResolution(bits[i]);                              // Set the resolution, (bits) → bool
        vTaskDelay(pdMS_TO_TICKS(400));
        sensor.readRgb(c);
        printf("%lu bit full scale %lu G %lu\n", (unsigned long)(bits[i]), (unsigned long)((1UL << bits[i]) - 1), (unsigned long)(c.green));
    }
    sensor.setRange(10000);
    const uint8_t adjust[3] = {0, 0x20, 0x3F};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.setIrCompensation(false, adjust[i]);                 // Set the IR compensation, (offset, adjust) → bool
        vTaskDelay(pdMS_TO_TICKS(400));
        sensor.readRgb(c);
        printf("IR compensation %lu G %lu R %lu B %lu\n", (unsigned long)(adjust[i]), (unsigned long)(c.green), (unsigned long)(c.red), (unsigned long)(c.blue));
    }
    sensor.setIrCompensation(true, 0x3F);
    sensor.setMode(Isl29125Full::MODE_GREEN);                       // Green only, (mode) → bool
    vTaskDelay(pdMS_TO_TICKS(300));
    printf("green only %lu\n", (unsigned long)(sensor.readGreen()));// Read green, () → uint16_t
    sensor.setMode(Isl29125Full::MODE_RGB);
    printf("ready %lu\n", (unsigned long)(sensor.dataReady()));     // Query CONVENF, () → bool
    uint16_t base = sensor.readGreen();
    uint16_t lo = base - base / 4;
    uint16_t hi = base + base / 4 > 0xFFFF ? 0xFFFF : base + base / 4;
    sensor.setThresholds(lo, hi);                                   // Set the window, (low, high) → bool
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    printf("window %lu - %lu\n", (unsigned long)(lo), (unsigned long)(hi));
    sensor.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);        // Choose the channel, (channel) → bool
    sensor.setPersistence(2);                                       // Set the persistence, (count) → bool
    sensor.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);         // Enable the interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        vTaskDelay(pdMS_TO_TICKS(500));
        uint8_t st = sensor.pollInterrupt();                        // Read and clear STATUS, () → uint8_t
        if (st & Isl29125Full::STATUS_THRESHOLD) {
            printf("window crossed, green %lu\n", (unsigned long)(sensor.readGreen()));
        }
    }
    sensor.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);        // Disable the interrupt, (source) → void
    printf("status 0x%02X\n", (unsigned)(sensor.status()));
    sensor.setMode(Isl29125Full::MODE_POWER_DOWN);
    sensor.softReset();                                             // Software reset, () → void
}
