#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/gpio.h>
#include "I2CConnectionZephyr.h"
#include "InputPinZephyr.h"
#include "PCF8574.h"

static const struct gpio_dt_spec int_gpio =
    GPIO_DT_SPEC_GET(DT_NODELABEL(pcf8574_int), gpios);

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));

    InputPinZephyr intPin(int_gpio);                             // Create INT pin, (gpio_dt_spec)
    I2CConnectionZephyr connection(i2c_dev, 0x20, &intPin);      // Create I2C connection, (dev, addr=0x20, intPin)
    PCF8574Full chip(connection);                                // Create PCF8574 full driver, (connection, addr=0x20)
                                                               // initialises all pins as inputs; shadow = 0xFF

    PCF8574Full::IOExpanderPin p0 = chip.pin(0);               // Get full pin proxy, (n) → IOExpanderPin
                                                               // holds reference to chip and pin index
    p0.mode(OUTPUT);                                            // Set direction output, (mode=OUTPUT) → void
                                                               // drives P0 low; sets shadow bit 0 = 0
    p0.high();                                                  // Set high, () → void
                                                               // shadow |= 1; quasi-input mode
    p0.low();                                                   // Drive low, () → void
                                                               // shadow &= ~1; strong sink
    p0.toggle();                                                // Invert shadow bit, () → void
                                                               // reads bus state then flips shadow
    uint8_t v = p0.read();                                     // Read actual level, () → uint8_t
                                                               // reads full port byte, extracts bit 0
    printk("P0=%u\n", v);

    uint8_t mask = chip.read_port();                           // Read all 8 pins, (port=0) → uint8_t bitmask
                                                               // bit 0 = P0, bit 7 = P7
    chip.write_port(0, 0b00001111);                            // Write all 8 pins, (port, mask) → void
                                                               // P0–P3 low (output), P4–P7 high (input)

    PCF8574Full::IOExpanderPin p4 = chip.pin(4);               // Get full pin proxy, (n) → IOExpanderPin
    p4.mode(INPUT);                                             // Set direction input, (mode=INPUT) → void
                                                               // releases P4 to input mode (shadow bit 4 = 1)

    // Subscribe to INT line
    chip.onInterrupt([](uint8_t changed) {                      // Subscribe to INT line, (callback) → void
        printk("INT changed=0x%02X\n", changed);               // called on any input change; clears INT line
    });

    uint8_t changed = chip.pollInterrupt();                     // Read and return changed bitmask, () → uint8_t
                                                               // compares current byte to previous read
    printk("changed=0x%02X\n", changed);

    (void)mask;
    while (true) k_msleep(1000);
    return 0;
}
