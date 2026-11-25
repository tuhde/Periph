#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AD5248.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AD5248_ADDR 0x2C

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AD5248_ADDR);
    AD5248Full pot(connection, 10000.0f, 160.0f);                // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    // --- Wiper position ---
    pot.set_wiper(0, 100);                                   // Set wiper, (channel 0-1, code 0-255) → void
                                                             // writes [channel << 7, code]; code clamped to 0-255
    uint8_t code = pot.get_wiper(0);                         // Read wiper, (channel 0-1) → uint8_t 0-255
                                                             // sends the instruction byte alone, then reads one byte
    printf("code=%d\n", (int)(code));
    pot.set_both(10, 20);                                    // Set both wipers, (code1 0-255, code2 0-255) → void
                                                             // two transactions, one per channel
    // --- Resistance ---
    pot.set_resistance(1, 2200.0f);                          // Set W-B resistance, (channel 0-1, ohms) → void
                                                             // nearest code of R = code/256*RAB + 2*RW, clamped
    float ohms = pot.get_resistance(1);                      // Read W-B resistance, (channel 0-1) → float Ω
                                                             // code/256*RAB + 2*RW
    printf("ch1 ohm=%.1f\n", (double)(ohms));
    // --- Ratio and increment ---
    pot.set_ratio(0, 0.5f);                                  // Set wiper ratio, (channel 0-1, ratio 0.0-1.0) → void
                                                             // code = round(ratio*256) clamped to 255; needs terminal A wired
    float ratio = pot.get_ratio(0);                          // Read wiper ratio, (channel 0-1) → float 0.0-1.0
                                                             // code / 256
    printf("ratio=%.1f\n", (double)(ratio));
    uint8_t next = pot.increment(0, 5);                      // Step wiper, (channel 0-1, steps=1) → uint8_t 0-255
                                                             // read-modify-write, clamped to 0-255; negative steps decrement
    printf("next=%d\n", (int)(next));
    // --- Shutdown ---
    pot.shutdown(1);                                         // Shut channel down, (channel 0-1) → void
                                                             // opens terminal A, shorts wiper to B, RDAC kept
    bool down = pot.is_shutdown(1);                          // Query driver-tracked state, (channel 0-1) → bool
                                                             // the chip cannot be read back
    printf("shutdown=%d\n", (int)(down));
    pot.resume(1);                                           // Leave shutdown, (channel 0-1) → void
                                                             // rewrites the RDAC with SD = 0
    // --- Model and reset ---
    pot.set_rab(10000.0f, 160.0f);                           // Set resistance model, (r_ab Ω, r_wiper Ω) → void
                                                             // no I²C traffic; affects conversions only
    pot.reset();                                             // Reset to power-up preset, () → void
                                                             // writes midscale 0x80 to both channels with SD = 0
    return 0;
}
