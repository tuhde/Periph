#include <math.h>
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

    // --- Colour sampler ---
    // Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
    // compares ALS/IR mode, steps gain and resolution with normalised counts,
    // arms a variation and a threshold interrupt on the green channel (polled
    // through pollInterrupt), then disables the sensor and soft-resets it.
    uint8_t part = 0, rev = 0;
    sensor.partId(part, rev);                                       // Read PART_ID, (part&, revision&) → void
    printk("part %02X rev %02X resolution %lu bit rate %lu ms gain %lu\n", (unsigned)(part), (unsigned)(rev), (unsigned long)(sensor.getResolution()), (unsigned long)(sensor.getMeasurementRate()), (unsigned long)(sensor.getGain()));
    Apds9250Counts c = {0, 0, 0, 0};
    for (int i = 0; i < 20; i++) {
        sensor.read(c);                                             // Read the four channels, (counts&) → bool
        uint32_t top = c.red > c.green ? c.red : c.green;
        if (c.blue > top) top = c.blue;
        if (top == 0) top = 1;
        printk("#%02X%02X%02X IR %lu\n", (unsigned)((unsigned long)c.red * 255 / top), (unsigned)((unsigned long)c.green * 255 / top), (unsigned)((unsigned long)c.blue * 255 / top), (unsigned long)(c.ir));
        k_sleep(K_MSEC(500));
    }
    sensor.setMode(false);                                          // Select ALS/IR mode, (colour) → void
    printk("ALS/IR green %lu ir %lu\n", (unsigned long)(sensor.readGreen()), (unsigned long)(sensor.readIr()));
    sensor.setMode(true);                                           // Back to colour mode, (colour) → void
    const uint8_t gains[5] = {1, 3, 6, 9, 18};
    for (uint8_t i = 0; i < 5; i++) {
        sensor.setGain(gains[i]);                                   // Set the gain, (gain) → bool
        k_sleep(K_MSEC(300));
        uint32_t g = sensor.readGreen();                            // Read green, () → uint32_t
        printk("gain %lu green %lu normalized %.0f\n", (unsigned long)(gains[i]), (unsigned long)(g), (double)(sensor.normalized(g)));
    }
    sensor.setGain(3);
    const uint8_t bits[6] = {13, 16, 17, 18, 19, 20};
    for (uint8_t i = 0; i < 6; i++) {
        sensor.setResolution(bits[i]);                              // Set the resolution, (bits) → bool
        k_sleep(K_MSEC(500));
        uint32_t g = sensor.readGreen();
        printk("%lu bit full scale %lu green %lu normalized %.0f\n", (unsigned long)(bits[i]), (unsigned long)((1UL << bits[i]) - 1), (unsigned long)(g), (double)(sensor.normalized(g)));
    }
    sensor.setResolution(18);
    sensor.setMeasurementRate(100);                                 // Set the rate, (milliseconds) → bool
    sensor.setInterruptChannel(Apds9250Full::CHANNEL_GREEN);        // Choose the channel, (channel) → bool
    sensor.setPersistence(2);                                       // Set the persistence, (count) → bool
    sensor.setVariance(64);                                         // Set the variation threshold, (counts) → bool
    sensor.enableInterrupt(Apds9250Full::SOURCE_VARIATION);         // Enable the variation interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        k_sleep(K_MSEC(500));
        uint8_t st = sensor.pollInterrupt();                        // Read and clear MAIN_STATUS, () → uint8_t
        if (st & Apds9250Full::STATUS_INTERRUPT) {
            printk("variation interrupt, green %lu\n", (unsigned long)(sensor.readGreen()));
        }
    }
    uint32_t base = sensor.readGreen();
    sensor.setThresholds(base - base / 4, base + base / 4);         // Set the window, (low, high) → bool
    sensor.enableInterrupt(Apds9250Full::SOURCE_THRESHOLD);         // Enable the threshold interrupt, (source) → void
    k_sleep(K_MSEC(5000));
    printk("status 0x%02X\n", (unsigned)(sensor.pollInterrupt()));
    sensor.disableInterrupt();                                      // Disable the interrupt, () → void
    sensor.disable();                                               // Standby, () → void
    sensor.softReset();                                             // Software reset, () → void
    return 0;
}
