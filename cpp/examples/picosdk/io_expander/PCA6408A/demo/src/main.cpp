#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA6408A.h"

static PCA6408AFull* g_chip = nullptr;
static uint8_t g_hold_mask = 0x0F;
static int g_hold_steps = 0;

static void onButton(uint8_t changed) {
    uint8_t port = g_chip->read_port();
    printf("button changed=0x%02X port=0x%02X\n", changed, port);
    g_hold_mask = ~((changed >> 4) & 0x0F) & 0x0F;
    g_hold_steps = 10;
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x20);
    stdio_init_all();

    PCA6408AFull chip(connection);                            // Create PCA6408A driver, (connection, addr=0x20)
    g_chip = &chip;


    // --- Configure LEDs and buttons ---
    // P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs and rely on
    // external pull-ups (the chip has none), so a pressed button (to GND) reads 0.
    chip.write_port(0, 0xFF);                                 // Turn all LEDs off, (port=0, mask=0xFF) → void
    chip.set_direction(0xF0);                                 // P0–P3 outputs, P4–P7 inputs, (mask=0xF0) → void

    // --- Subscribe to input changes ---
    // Any button edge raises INT; onButton() prints the changed mask and the port state and
    // holds the LED matching the pressed button for one second.
    chip.onInterrupt(onButton);                               // Subscribe to INT, (callback, intPin=nullptr) → void

    // --- Scanner loop ---
    // service() stands in for the INT line when none is wired.
    for (int step = 0; step < 40; step++) {
        uint8_t pos = step % 4;
        uint8_t leds = ~(1 << pos) & 0x0F;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_port(0, 0xF0 | leds);                      // Write LED outputs, (port=0, mask) → void
        uint8_t port_val = chip.read_port();                  // Read all 8 pins, (port=0) → uint8_t bitmask
        printf("  pos=%d  port=0x%02X\n", pos, port_val);
        sleep_ms(100);
        chip.service();                                       // Poll for button changes, () → void
    }

    // --- Shut down ---
    chip.offInterrupt();                                      // Unsubscribe, () → void
    chip.write_port(0, 0xFF);                                 // Turn all LEDs off, (port=0, mask=0xFF) → void
    while (true) sleep_ms(1000);
}
