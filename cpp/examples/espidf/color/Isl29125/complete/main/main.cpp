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
    printf("device id 0x%02X\n", (unsigned)(sensor.deviceId()));    // Read DEVICE_ID, () → uint8_t
                                                                    // 0x7D for the ISL29125
    Isl29125Counts c = {0, 0, 0};
    sensor.read(c);                                                 // Read the three channels, (counts&) → bool
    printf("R %lu G %lu B %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue));
    printf("red %lu\n", (unsigned long)(sensor.readRed()));         // Read the red channel, () → uint16_t
    printf("green %lu\n", (unsigned long)(sensor.readGreen()));     // Read the green channel, () → uint16_t
    printf("blue %lu\n", (unsigned long)(sensor.readBlue()));       // Read the blue channel, () → uint16_t
    sensor.readRgb(c);                                              // Block-read without waiting, (counts&) → void
    printf("ready %lu\n", (unsigned long)(sensor.dataReady()));     // Query CONVENF, () → bool
                                                                    // clears the status flags on the chip
    bool ok = sensor.setMode(Isl29125Full::MODE_GREEN);             // Select the channels, (mode) → bool
                                                                    // MODE_POWER_DOWN, _GREEN, _RED, _BLUE, _STANDBY, _RGB, _GREEN_RED, _GREEN_BLUE
    ok = sensor.setMode(Isl29125Full::MODE_RGB);                    // Back to all three channels, (mode) → bool
    ok = sensor.setRange(375);                                      // Set the full-scale range, (lux) → bool
                                                                    // 375 or 10000
    ok = sensor.setRange(10000);
    ok = sensor.setResolution(12);                                  // Set the ADC resolution, (bits) → bool
                                                                    // 12 or 16
    ok = sensor.setResolution(16);
    sensor.setSync(false);                                          // Select the conversion start, (external) → void
                                                                    // true: rising edge of INT starts the conversion
    ok = sensor.setIrCompensation(true, 0x3F);                      // Set the IR compensation, (offset, adjust) → bool
                                                                    // adjust 0-63; offset selects codes 106-169
    ok = sensor.setThresholds(1000, 50000);                         // Set the threshold window, (low, high) → bool
    uint16_t lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    printf("window %lu - %lu\n", (unsigned long)(lo), (unsigned long)(hi));
    ok = sensor.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);   // Choose the monitored channel, (channel) → bool
                                                                    // CHANNEL_NONE, _GREEN, _RED or _BLUE
    ok = sensor.setPersistence(2);                                  // Set the persistence, (count) → bool
                                                                    // 1, 2, 4 or 8 integration cycles
    sensor.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);         // Enable the interrupt, (source) → void
                                                                    // SOURCE_THRESHOLD or SOURCE_CONVERSION
    vTaskDelay(pdMS_TO_TICKS(2000));
    printf("poll 0x%02X\n", (unsigned)(sensor.pollInterrupt()));    // Read and clear STATUS, () → uint8_t
                                                                    // 0x04 brown-out, 0x02 conversion, 0x01 threshold
    sensor.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);        // Disable the interrupt, (source) → void
    printf("status 0x%02X\n", (unsigned)(sensor.status()));         // Raw STATUS, () → uint8_t
    sensor.setMode(Isl29125Full::MODE_POWER_DOWN);
    sensor.softReset();                                             // Software reset, () → void
                                                                    // all registers to defaults
    printf("ok %lu\n", (unsigned long)(ok));
}
