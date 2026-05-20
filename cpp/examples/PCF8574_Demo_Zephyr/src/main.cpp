/**
 * PCF8574 Zephyr demo — button-controlled LED mirror.
 *
 * Hardware:
 *   P0–P3: LEDs (anode → VCC, cathode → pin; active-low)
 *   P4–P7: push buttons (pin → GND when pressed)
 *   INT:   pcf8574_int GPIO defined in board overlay
 */
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/gpio.h>
#include "I2CTransportZephyr.h"
#include "PCF8574.h"

static const struct gpio_dt_spec int_gpio =
    GPIO_DT_SPEC_GET(DT_NODELABEL(pcf8574_int), gpios);

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));

    I2CTransportZephyr transport(i2c_dev, 0x20);               // Create I2C transport, (dev, addr=0x20)
    PCF8574Full chip(transport);                                // Create PCF8574 full driver, (transport, addr=0x20)

    // --- Configure output and input nibbles ---
    // P0–P3: outputs (LEDs, active-low); P4–P7: inputs (buttons, pull-up)
    chip.write_port(0, 0xF0);                                   // Write all 8 pins, (port, mask) → void

    // --- Wire INT line for fast response ---
    // The chip's INT fires within ~10 µs of any input change.
    gpio_pin_configure_dt(&int_gpio, GPIO_INPUT | GPIO_PULL_UP);
    chip.configure_interrupt(int_gpio.pin, [](uint8_t) {        // Attach interrupt, (gpio_pin, callback) → void
        // wake-up signal; actual read happens in main loop
    });

    printk("PCF8574 demo running\n");

    while (true) {
        uint8_t port = chip.read_port();                        // Read all 8 pins, () → uint8_t bitmask

        uint8_t buttons  = (port >> 4) & 0x0F;  // P4–P7: pressed = 0
        uint8_t led_bits = (~buttons) & 0x0F;   // active-low: pressed → LED on
        chip.write_port(0, 0xF0 | led_bits);                    // Write all 8 pins, (port, mask) → void

        printk("port=0x%02X  btn=0x%X  led=0x%X\n", port, buttons, led_bits);
        k_msleep(200);
    }
    return 0;
}
