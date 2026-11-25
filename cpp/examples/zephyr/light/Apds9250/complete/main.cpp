#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Apds9250.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Apds9250Minimal::I2C_ADDRESS);
    Apds9250Full sensor(connection);                                // Create APDS-9250 driver, (connection)
    uint8_t part = 0, rev = 0;
    sensor.partId(part, rev);                                       // Read PART_ID, (part&, revision&) → void
                                                                    // 0xB, 0x5 for the APDS-9250
    printk("part %02X rev %02X\n", (unsigned)(part), (unsigned)(rev));
    Apds9250Counts c = {0, 0, 0, 0};
    sensor.read(c);                                                 // Read the four channels, (counts&) → bool
    printk("R %lu G %lu B %lu IR %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue), (unsigned long)(c.ir));
    printk("red %lu\n", (unsigned long)(sensor.readRed()));         // Read the red channel, () → uint32_t
    printk("green %lu\n", (unsigned long)(sensor.readGreen()));     // Read the green / ALS channel, () → uint32_t
    printk("blue %lu\n", (unsigned long)(sensor.readBlue()));       // Read the blue channel, () → uint32_t
    printk("ir %lu\n", (unsigned long)(sensor.readIr()));           // Read the IR channel, () → uint32_t
    printk("ready %lu\n", (unsigned long)(sensor.dataReady()));     // Query LS_DATA, () → bool
                                                                    // clears the status flags on the chip
    sensor.readChannels(c);                                         // Block-read without waiting, (counts&) → void
    sensor.setMode(false);                                          // Select ALS/IR mode, (colour) → void
                                                                    // false: only green and IR are active
    sensor.setMode(true);                                           // Back to colour mode, (colour) → void
    sensor.disable();                                               // Standby, () → void
    sensor.enable();                                                // Activate the sensor, () → void
    bool ok = sensor.setResolution(16);                             // Set the ADC resolution, (bits) → bool
                                                                    // 13, 16, 17, 18, 19 or 20; restarts the measurement
    ok = sensor.setMeasurementRate(200);                            // Set the measurement rate, (milliseconds) → bool
                                                                    // 25, 50, 100, 200, 500, 1000 or 2000
    ok = sensor.setGain(6);                                         // Set the gain, (gain) → bool
                                                                    // 1, 3, 6, 9 or 18; restarts the measurement
    printk("resolution %lu bit\n", (unsigned long)(sensor.getResolution()));// Read the resolution, () → uint8_t
    printk("rate %lu ms\n", (unsigned long)(sensor.getMeasurementRate()));// Read the rate, () → uint16_t
    printk("gain %lu\n", (unsigned long)(sensor.getGain()));        // Read the gain, () → uint8_t
    printk("integration %.3f s\n", (double)(sensor.integrationTime()));// Integration time, () → float s
    printk("normalized %.0f\n", (double)(sensor.normalized(sensor.readGreen())));// Counts per gain-second, (counts) → float
    ok = sensor.setThresholds(1000, 100000);                        // Set the threshold window, (low, high) → bool
                                                                    // 20-bit counts
    uint32_t lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    printk("window %lu - %lu\n", (unsigned long)(lo), (unsigned long)(hi));
    ok = sensor.setInterruptChannel(Apds9250Full::CHANNEL_GREEN);   // Choose the monitored channel, (channel) → bool
                                                                    // CHANNEL_IR, _GREEN, _RED or _BLUE
    ok = sensor.setPersistence(2);                                  // Set the persistence, (count) → bool
                                                                    // 1–16 consecutive events
    ok = sensor.setVariance(64);                                    // Set the variation threshold, (counts) → bool
                                                                    // 8 … 1024, power of two
    sensor.enableInterrupt(Apds9250Full::SOURCE_VARIATION);         // Enable the interrupt, (source) → void
                                                                    // SOURCE_THRESHOLD or SOURCE_VARIATION
    k_sleep(K_MSEC(2000));
    printk("poll 0x%02X\n", (unsigned)(sensor.pollInterrupt()));    // Read and clear MAIN_STATUS, () → uint8_t
                                                                    // 0x20 power-on, 0x10 interrupt, 0x08 data
    sensor.disableInterrupt();                                      // Disable the interrupt, () → void
    printk("status 0x%02X\n", (unsigned)(sensor.status()));         // Raw MAIN_STATUS, () → uint8_t
    sensor.softReset();                                             // Software reset, () → void
                                                                    // all registers to defaults, sensor in standby
    printk("ok %lu\n", (unsigned long)(ok));
    return 0;
}
