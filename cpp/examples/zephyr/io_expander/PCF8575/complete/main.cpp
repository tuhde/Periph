#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include "I2CTransportZephyr.h"
#include "PCF8575.h"

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));

    I2CTransportZephyr transport(i2c_dev, 0x20);                 // Create I2C transport, (dev, addr=0x20)
    PCF8575Full chip(transport);                                 // Create PCF8575 full driver, (transport, addr=0x20)

    PCF8575Minimal::IOExpanderPin p0 = chip.pin(0);             // Get pin proxy, (n=0) → IOExpanderPin
    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void
    p0.high();                                                  // Set high, () → void
    p0.low();                                                   // Drive low, () → void
    p0.toggle();                                                // Invert shadow bit, () → void

    chip.write_port(0, 0b00001111);                             // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0b00001111);                             // Write Port 1, (port=1, mask=uint8_t) → void

    PCF8575Minimal::IOExpanderPin p8 = chip.pin(8);             // Get pin proxy, (n=8) → IOExpanderPin
    p8.mode(INPUT);                                             // Set direction, (mode=INPUT) → void
    uint8_t state = p8.read();                                  // Read actual level, () → uint8_t

    uint16_t changed = chip.clear_interrupt();                  // Read port and return 16-bit changed bitmask, () → uint16_t
    (void)state; (void)changed;

    while (true) k_msleep(1000);
    return 0;
}