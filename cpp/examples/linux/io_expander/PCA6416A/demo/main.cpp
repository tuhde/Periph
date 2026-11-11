#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCA6416A.h"

static PCA6416AFull* g_chip = nullptr;
static uint8_t g_hold_mask = 0xFF;
static int g_hold_steps = 0;

static void onButton(uint16_t changed) {
    uint16_t all = g_chip->read_all();
    printf("button changed=0x%04X all=0x%04X\n", changed, all);
    g_hold_mask = ~((changed >> 8) & 0xFF) & 0xFF;
    g_hold_steps = 10;
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    PCA6416AFull chip(connection);                            // Create PCA6416A driver, (connection, addr=0x20)
    g_chip = &chip;


    // --- Configure LEDs and buttons ---
    // Port 0 holds the active-low LED outputs (0 lights the LED); port 1 stays input and relies
    // on external pull-ups (the chip has none), so a pressed button (to GND) reads 0. Polarity
    // inversion makes a pressed button read as 1.
    chip.write_all(0xFFFF);                                   // Turn all LEDs off, (value=0xFFFF) → void
    chip.set_direction(0xFF00);                                // Port 0 outputs, port 1 inputs, (mask=0xFF00) → void
    chip.configure_polarity(0xFF00);                          // Invert port 1 inputs, (mask=0xFF00) → void

    // --- Subscribe to input changes ---
    // Any button edge raises INT; onButton() prints the 16-bit changed mask and the pin state and
    // holds the LED matching the pressed button for one second.
    chip.onInterrupt(onButton);                               // Subscribe to INT, (callback, intPin=nullptr) → void

    // --- Scanner loop ---
    // service() stands in for the INT line when none is wired.
    for (int step = 0; step < 80; step++) {
        uint8_t pos = step % 8;
        uint8_t leds = ~(1 << pos) & 0xFF;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_all(0xFF00 | leds);                           // Write LED outputs, (value) → void
        uint16_t all = chip.read_all();                      // Read all 16 pins, () → uint16_t
        printf("  pos=%d  all=0x%04X\n", pos, all);
        usleep(100000);
        chip.service();                                       // Poll for button changes, () → void
    }

    // --- Shut down ---
    chip.offInterrupt();                                      // Unsubscribe, () → void
    chip.write_all(0xFFFF);                                   // Turn all LEDs off, (value=0xFFFF) → void
    return 0;
}
