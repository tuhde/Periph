#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AS5600.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x36);
    AS5600Full as5600(connection);

    stdio_init_all();
    sleep_ms(2000);

    // --- Status and magnet checks ---
    printf("%d\n", as5600.is_magnet_detected());       // Check magnet present, () → bool
    printf("%d\n", as5600.is_magnet_too_strong());     // Check magnet too strong, () → bool
    printf("%d\n", as5600.is_magnet_too_weak());       // Check magnet too weak, () → bool
    printf("0x%X\n", (unsigned)as5600.status_byte());         // Read raw status, () → int

    // --- Angle readings ---
    printf("%d\n", as5600.angle());                    // Read absolute angle, () → float degrees
    printf("%d\n", as5600.angle_raw());                // Read scaled angle count, () → int 0-4095
    printf("%d\n", as5600.raw_angle());                // Read raw unscaled angle, () → int 0-4095
    printf("%d\n", as5600.raw_angle_degrees());        // Read raw angle in degrees, () → float degrees

    // --- Diagnostics ---
    printf("%d\n", as5600.agc());                      // Read AGC value, () → int
    printf("%d\n", as5600.magnitude());                // Read CORDIC magnitude, () → int

    // --- Position configuration (volatile) ---
    printf("%d\n", as5600.zero_position());            // Read ZPOS, () → int 0-4095
    printf("%d\n", as5600.max_position());             // Read MPOS, () → int 0-4095
    printf("%d\n", as5600.max_angle());                // Read MANG, () → int 0-4095

    as5600.set_zero_position(0);                       // Set zero position, (pos 0-4095) → None
    as5600.set_max_position(4095);                     // Set max position, (pos 0-4095) → None
    as5600.set_max_angle(2048);                        // Set max angle span, (span 0-4095) → None

    // --- Configure power mode and output ---
    as5600.configure(AS5600Full::PM_NOM, 0, AS5600Full::OUTS_ANALOG, 0, 0, 0, false);  // Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → None

    // --- Burn count ---
    printf("%d\n", as5600.burn_count());               // Read burn count, () → int 0-3
    while (true) {

    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
