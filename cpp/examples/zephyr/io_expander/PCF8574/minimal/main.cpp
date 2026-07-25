#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include "I2CTransportZephyr.h"
#include "PCF8574.h"

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));

    I2CTransportZephyr transport(i2c_dev, 0x20);               // Create I2C transport, (dev, addr=0x20)
    PCF8574Minimal chip(transport);                             // Create PCF8574 driver, (transport, addr=0x20)

    PCF8574Minimal::IOExpanderPin p0 = chip.pin(0);            // Get pin proxy, (n) → IOExpanderPin
    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void

    PCF8574Minimal::IOExpanderPin p4 = chip.pin(4);            // Get pin proxy, (n) → IOExpanderPin
    p4.mode(INPUT);                                             // Set direction, (mode=INPUT) → void

    while (true) {
        uint8_t port = chip.read_port();                        // Read all 8 pins, () → uint8_t bitmask
        if ((port >> 4) & 1) p0.high(); else p0.low();         // Set high, () → void / Set low, () → void
        k_msleep(200);
    }
    return 0;
}
