#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "CAT5171.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, CAT5171Minimal::I2C_ADDRESS);
    CAT5171Full pot(connection);                        // Create CAT5171 Full driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    pot.setWiper(100);                                  // Set wiper position, (code 0–255) → void
    // writes [instruction, code] with RS = 0, SD = 0
    uint8_t w = pot.getWiper();                         // Read wiper position, () → uint8_t 0–255
    // bare one-byte read; no instruction byte is sent
    printf("wiper %d\n", (int)w);
    pot.setResistance(50000.0f);                        // Set W–B resistance, (ohms Ω) → void
    // inverts R = D/256·RAB + RW to the nearest code
    float r = pot.getResistance();                      // Read W–B resistance, () → float Ω
    // evaluates D/256·RAB + RW for the current code
    printf("R_WB %.1f ohm\n", (double)r);
    pot.setRatio(0.25f);                                // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 256), clamped to 255
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    // code / 256
    printf("ratio %.4f\n", (double)ratio);
    pot.setResistanceWa(30000.0f);                      // Set W–A resistance, (ohms Ω) → void
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa();                  // Read W–A resistance, () → float Ω
    // (256 − D)/256·RAB + RW
    printf("R_WA %.1f ohm\n", (double)rwa);
    uint8_t n = pot.increment(5);                       // Step the wiper, (steps=1) → uint8_t 0–255
    // read, add, clamp, write; returns the new code
    printf("incremented to %d\n", (int)n);
    pot.midscale();                                     // Move wiper to midscale, () → void
    // RS = 1 instruction; wiper goes to 0x80
    pot.shutdown();                                     // Shut down, () → void
    // SD = 1: A opens, wiper shorts to B, WCR kept
    bool sd = pot.isShutdown();                         // Query shutdown state, () → bool
    // driver-tracked; the chip cannot report it
    printf("shutdown %d\n", (int)sd);
    sleep_ms(1000);
    pot.resume();                                       // Leave shutdown, () → void
    // rewrites the kept code with SD = 0
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Midscale reset, () → void
    // RS = 1, SD = 0; wiper returns to 0x80
    return 0;
}
