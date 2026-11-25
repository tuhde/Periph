#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AD5251.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, AD5251Minimal::I2C_ADDRESS);
    AD5251Full pot(connection);                         // Create AD5251 Full driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω)
    const float VDD = 3.3f;

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 64), so 0.999 lands on the top code 63.
    static const float ratios[] = {0.0f, 0.25f, 0.5f, 0.75f, 0.999f};
    for (int i = 0; i < 5; i++) {
        pot.setRatio(0, ratios[i]);                     // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
        uint8_t code = pot.getWiper(0);                 // Read wiper position, (channel 0–1) → uint8_t 0–63
        float actual = pot.getRatio(0);                 // Read divider ratio, (channel 0–1) → float
        printf("ratio %.3f -> code %d (%.4f) -> %.3f V\n", (double)ratios[i], (int)code, (double)actual, (double)(actual * VDD));
        sleep_ms(500);
    }

    // --- Use channel 1 as a 20 kΩ rheostat ---
    // The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
    // the readback differs slightly from the request.
    pot.setResistance(1, 20000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    float r = pot.getResistance(1);                     // Read W–B resistance, (channel 0–1) → float Ω
    printf("rheostat %.1f ohm, error %.1f ohm\n", (double)r, (double)(r - 20000.0f));

    // --- Step and scale the wiper with the quick commands ---
    // increment/decrement move one code, double/halve change the level by 6 dB.
    pot.setWiper(0, 16);                                // Set wiper position, (channel 0–1, code 0–63) → bool
    pot.increment(0);                                   // Quick command, (channel=BOTH 0–1) → bool
    printf("increment %d\n", (int)pot.getWiper(0));
    pot.decrement(0);                                   // Quick command, (channel=BOTH 0–1) → bool
    printf("decrement %d\n", (int)pot.getWiper(0));
    pot.doubleCode(0);                                  // Quick command, (channel=BOTH 0–1) → bool
    printf("double %d\n", (int)pot.getWiper(0));
    pot.halve(0);                                       // Quick command, (channel=BOTH 0–1) → bool
    printf("halve %d\n", (int)pot.getWiper(0));

    // --- Make the settings persistent ---
    // store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
    // restore() brings the stored values back.
    pot.store();                                        // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    pot.setBoth(1, 2);                                  // Write both wipers, (code0 0–63, code1 0–63) → void
    pot.restore();                                      // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    printf("restored ch0 %d\n", (int)pot.getWiper(0));
    printf("restored ch1 %d\n", (int)pot.getWiper(1));

    // --- User EEMEM bytes and the factory tolerance ---
    pot.writeEemem(4, 0x40);                            // Write user EEMEM byte, (index 4–15, value 0–63) → bool
    uint8_t e = pot.readEemem(4);                       // Read user EEMEM byte, (index 4–15) → uint8_t
    printf("eemem4 %d\n", (int)e);
    float tol = pot.readTolerance(0);                   // Read stored RAB tolerance, (channel 0–1) → float %
    printf("tolerance %.4f\n", (double)(tol));
    float ra = pot.rabActual(0);                        // Actual RAB of a channel, (channel 0–1) → float Ω
    printf("rab_actual %.4f\n", (double)(ra));

    // --- Restore the stored state ---
    // reset() restores both wipers from EEMEM and waits for the write cycle.
    pot.reset();                                        // Reset from EEMEM, () → bool
    return 0;
}
