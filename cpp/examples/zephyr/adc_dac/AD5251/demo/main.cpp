#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AD5251.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AD5251Minimal::I2C_ADDRESS);
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
        printk("ratio %.3f -> code %d (%.4f) -> %.3f V\n", (double)ratios[i], (int)code, (double)actual, (double)(actual * VDD));
        k_sleep(K_MSEC(500));
    }

    // --- Use channel 1 as a 20 kΩ rheostat ---
    // The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
    // the readback differs slightly from the request.
    pot.setResistance(1, 20000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    float r = pot.getResistance(1);                     // Read W–B resistance, (channel 0–1) → float Ω
    printk("rheostat %.1f ohm, error %.1f ohm\n", (double)r, (double)(r - 20000.0f));

    // --- Step and scale the wiper with the quick commands ---
    // increment/decrement move one code, double/halve change the level by 6 dB.
    pot.setWiper(0, 16);                                // Set wiper position, (channel 0–1, code 0–63) → bool
    pot.increment(0);                                   // Quick command, (channel=BOTH 0–1) → bool
    printk("increment %d\n", (int)pot.getWiper(0));
    pot.decrement(0);                                   // Quick command, (channel=BOTH 0–1) → bool
    printk("decrement %d\n", (int)pot.getWiper(0));
    pot.doubleCode(0);                                  // Quick command, (channel=BOTH 0–1) → bool
    printk("double %d\n", (int)pot.getWiper(0));
    pot.halve(0);                                       // Quick command, (channel=BOTH 0–1) → bool
    printk("halve %d\n", (int)pot.getWiper(0));

    // --- Make the settings persistent ---
    // store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
    // restore() brings the stored values back.
    pot.store();                                        // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    pot.setBoth(1, 2);                                  // Write both wipers, (code0 0–63, code1 0–63) → void
    pot.restore();                                      // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    printk("restored ch0 %d\n", (int)pot.getWiper(0));
    printk("restored ch1 %d\n", (int)pot.getWiper(1));

    // --- User EEMEM bytes and the factory tolerance ---
    pot.writeEemem(4, 0x40);                            // Write user EEMEM byte, (index 4–15, value 0–63) → bool
    uint8_t e = pot.readEemem(4);                       // Read user EEMEM byte, (index 4–15) → uint8_t
    printk("eemem4 %d\n", (int)e);
    float tol = pot.readTolerance(0);                   // Read stored RAB tolerance, (channel 0–1) → float %
    printk("tolerance %d\n", (int)(tol));
    float ra = pot.rabActual(0);                        // Actual RAB of a channel, (channel 0–1) → float Ω
    printk("rab_actual %d\n", (int)(ra));

    // --- Restore the stored state ---
    // reset() restores both wipers from EEMEM and waits for the write cycle.
    pot.reset();                                        // Reset from EEMEM, () → bool
    return 0;
}
