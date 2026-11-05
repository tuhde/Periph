#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP23008.h"

static MCP23008Full* g_chip = nullptr;
static uint8_t g_hold_mask = 0;
static int g_hold_steps = 0;

static void onButton(uint8_t status) {
    uint8_t captured = g_chip->read_capture();
    printf("button INTF=0x%02X INTCAP=0x%02X\n", status, captured);
    g_hold_mask = (status >> 4) & 0x0F;
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

    MCP23008Full chip(connection);                            // Create MCP23008 driver, (connection, addr=0x20)
    g_chip = &chip;

    // --- Configure LEDs and buttons ---
    // GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups on,
    // so a pressed button (to GND) reads 0.
    for (uint8_t n = 0; n < 4; n++) {
        chip.pin(n).mode(OUTPUT);                             // Set LED pin direction, (m INPUT/OUTPUT) → void
    }
    chip.configure_pullup(0xF0);                              // Enable pull-ups on GP4–GP7, (mask=0xF0) → void

    // --- Arm interrupt-on-change on the buttons ---
    // Any button edge raises INT; onButton() prints INTF and the INTCAP snapshot and
    // holds the LED matching the pressed button for one second.
    chip.enable_pin_interrupt(0xF0);                          // Arm GP4–GP7, (mask) → void
    chip.onInterrupt(onButton);                               // Subscribe to INT, (callback, intPin=nullptr, mode) → bool

    // --- Scanner loop ---
    // service() stands in for the INT line when none is wired.
    for (int step = 0; step < 40; step++) {
        uint8_t pos = step % 4;
        uint8_t leds = 1 << pos;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_port(0, leds);                             // Write LED outputs, (port=0, mask) → void
        uint8_t port_val = chip.read_port();                  // Read all 8 pins, (port=0) → uint8_t bitmask
        printf("  pos=%d  port=0x%02X\n", pos, port_val);
        sleep_ms(100);
        chip.service();                                       // Poll for button interrupts, () → void
    }

    // --- Shut down ---
    chip.offInterrupt();                                      // Unsubscribe, () → void
    chip.write_port(0, 0x00);                                 // Clear all outputs, (port=0, mask=0x00) → void
    while (true) sleep_ms(1000);
}
