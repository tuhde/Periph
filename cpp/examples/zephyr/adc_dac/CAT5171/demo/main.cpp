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
    const float VDD = 3.3f;

    // --- Sweep the wiper across the full range ---
    // Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
    // LSB below the RAB end.
    for (int code = 0; code < 256; code += 16) {
        pot.setWiper(code);                             // Set wiper position, (code 0–255) → void
        float r = pot.getResistance();                  // Read W–B resistance, () → float Ω
        printk("code %d -> %.1f ohm\n", code, (double)r);
        k_sleep(K_MSEC(200));
    }

    // --- Set a 22 kΩ rheostat ---
    // The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
    // so the readback differs slightly from the request.
    pot.setResistance(22000.0f);                        // Set W–B resistance, (ohms Ω) → void
    float r = pot.getResistance();                      // Read W–B resistance, () → float Ω
    printk("rheostat %.1f ohm, error %.1f ohm\n", (double)r, (double)(r - 22000.0f));

    // --- Shutdown, resume, midscale and reset ---
    // Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
    // so resume restores the setting; midscale and reset both return to 0x80.
    uint8_t kept = pot.getWiper();                      // Read wiper position, () → uint8_t 0–255
    pot.shutdown();                                     // Shut down, () → void
    bool sd = pot.isShutdown();                         // Query shutdown state, () → bool
    printk("shutdown %d kept %d\n", (int)sd, (int)kept);
    k_sleep(K_MSEC(1000));
    pot.resume();                                       // Leave shutdown, () → void
    pot.midscale();                                     // Move wiper to midscale, () → void
    pot.reset();                                        // Midscale reset, () → void

    // --- Use the wiper as a voltage divider ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    static const float ratios[] = {0.0f, 0.25f, 0.5f, 0.75f, 0.999f};
    for (int i = 0; i < 5; i++) {
        pot.setRatio(ratios[i]);                        // Set divider ratio, (ratio 0.0–1.0) → void
        float actual = pot.getRatio();                  // Read divider ratio, () → float
        printk("ratio %.3f -> %.4f -> %.3f V\n", (double)ratios[i], (double)actual, (double)(actual * VDD));
        k_sleep(K_MSEC(500));
    }
    return 0;
}
