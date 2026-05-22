#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include "I2CTransportZephyr.h"
#include "PCF8575.h"

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));

    I2CTransportZephyr transport(i2c_dev, 0x20);                 // Create I2C transport, (dev, addr=0x20)
    PCF8575Full chip(transport);                                  // Create PCF8575 full driver, (transport, addr=0x20)

    chip.write_port(0, 0xFF);                                    // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0xFF);                                    // Write Port 1, (port=1, mask=uint8_t) → void

    while (true) {
        uint8_t port0 = chip.read_port(0);                       // Read Port 0, (port=0) → uint8_t bitmask
        uint8_t port1 = chip.read_port(1);                        // Read Port 1, (port=1) → uint8_t bitmask

        uint8_t buttons = port1;
        uint8_t led_bits = ~buttons;
        chip.write_port(0, led_bits);                           // Write Port 0, (port=0, mask=uint8_t) → void

        printk("P0=0x%02X  P1=0x%02X\n", port0, port1);
        k_msleep(200);
    }
    return 0;
}