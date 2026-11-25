#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "CAT5171.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, CAT5171Minimal::I2C_ADDRESS);
    CAT5171Full pot(connection);                        // Create CAT5171 Full driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    pot.setWiper(100);                                  // Set wiper position, (code 0–255) → void
    // writes [instruction, code] with RS = 0, SD = 0
    uint8_t w = pot.getWiper();                         // Read wiper position, () → uint8_t 0–255
    // bare one-byte read; no instruction byte is sent
    printk("wiper %d\n", (int)w);
    pot.setResistance(50000.0f);                        // Set W–B resistance, (ohms Ω) → void
    // inverts R = D/256·RAB + RW to the nearest code
    float r = pot.getResistance();                      // Read W–B resistance, () → float Ω
    // evaluates D/256·RAB + RW for the current code
    printk("R_WB %.1f ohm\n", (double)r);
    pot.setRatio(0.25f);                                // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 256), clamped to 255
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    // code / 256
    printk("ratio %.4f\n", (double)ratio);
    pot.setResistanceWa(30000.0f);                      // Set W–A resistance, (ohms Ω) → void
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa();                  // Read W–A resistance, () → float Ω
    // (256 − D)/256·RAB + RW
    printk("R_WA %.1f ohm\n", (double)rwa);
    uint8_t n = pot.increment(5);                       // Step the wiper, (steps=1) → uint8_t 0–255
    // read, add, clamp, write; returns the new code
    printk("incremented to %d\n", (int)n);
    pot.midscale();                                     // Move wiper to midscale, () → void
    // RS = 1 instruction; wiper goes to 0x80
    pot.shutdown();                                     // Shut down, () → void
    // SD = 1: A opens, wiper shorts to B, WCR kept
    bool sd = pot.isShutdown();                         // Query shutdown state, () → bool
    // driver-tracked; the chip cannot report it
    printk("shutdown %d\n", (int)sd);
    k_sleep(K_MSEC(1000));
    pot.resume();                                       // Leave shutdown, () → void
    // rewrites the kept code with SD = 0
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Midscale reset, () → void
    // RS = 1, SD = 0; wiper returns to 0x80
    return 0;
}
