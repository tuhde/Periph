#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCA9536.h"

#ifndef PCA9536_I2C_NODE
#define PCA9536_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCA9536_ADDR
#define PCA9536_ADDR 0x41
#endif

static PCA9536Full* g_chip = nullptr;
static uint8_t g_hold_mask = 0x03;
static int g_hold_steps = 0;

static void onButton(uint8_t changed) {
    uint8_t port = g_chip->read_port();
    printk("button changed=0x%X port=0x%X\n", changed, port);
    g_hold_mask = ~((changed >> 2) & 0x03) & 0x03;
    g_hold_steps = 10;
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCA9536_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCA9536_ADDR);

    PCA9536Full chip(connection);                           // Create PCA9536 driver, (connection, addr=0x41)
    g_chip = &chip;

    // --- Configure LEDs and buttons ---
    // P0–P1 are active-low LED outputs (0 lights the LED); P2–P3 stay inputs and rely on the
    // built-in weak pull-up, so a pressed button (to GND) reads 0.
    chip.write_port(0, 0x0F);                                   // Turn all LEDs off, (port=0, mask=0x0F) → void
    chip.set_direction(0x0C);                                   // P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → void

    // --- Watch for input changes ---
    // The chip has no INT pin: service() polls INPUT, and onButton() prints the changed mask
    // and the port state and holds the LED matching the pressed button for one second.
    chip.watchChanges(onButton);                                // Register change callback, (callback) → void

    // --- Blink loop ---
    for (int step = 0; step < 40; step++) {
        uint8_t leds = (step % 2 == 0) ? 0x02 : 0x01;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_port(0, 0x0C | leds);                        // Write LED outputs, (port=0, mask) → void
        k_msleep(100);
        chip.service();                                         // Poll for button changes, () → void
    }

    // --- Shut down ---
    chip.unwatchChanges();                                      // Remove change callback, () → void
    chip.write_port(0, 0x0F);                                   // Turn all LEDs off, (port=0, mask=0x0F) → void
    while (1) { k_sleep(K_SECONDS(1)); }
    return 0;
}
