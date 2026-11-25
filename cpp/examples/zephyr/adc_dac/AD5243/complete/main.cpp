#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AD5243.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AD5243Minimal::I2C_ADDRESS);
    AD5243Full pot(connection);                         // Create AD5243 Full driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    pot.setWiper(0, 100);                               // Set wiper position, (channel 0–1, code 0–255) → bool
    // writes [instruction, code]; the instruction byte selects the channel
    uint8_t w = pot.getWiper(0);                        // Read wiper position, (channel 0–1) → uint8_t 0–255
    // sends the instruction alone, then reads one byte
    printk("wiper %d\n", (int)w);
    pot.setResistance(0, 5000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    // inverts R = D/256·RAB + 2·RW to the nearest code
    float r = pot.getResistance(0);                     // Read W–B resistance, (channel 0–1) → float Ω
    // evaluates D/256·RAB + 2·RW for the current code
    printk("R_WB %.1f ohm\n", (double)r);
    pot.setBoth(32, 224);                               // Write both wipers, (code1 0–255, code2 0–255) → void
    // two transactions, channel 0 then channel 1
    pot.setRatio(0, 0.25f);                             // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    // code = round(ratio × 256), clamped to 255
    float ratio = pot.getRatio(0);                      // Read divider ratio, (channel 0–1) → float
    // code / 256
    printk("ratio %.4f\n", (double)ratio);
    pot.setResistanceWa(1, 3000.0f);                    // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa(1);                 // Read W–A resistance, (channel 0–1) → float Ω
    // (256 − D)/256·RAB + 2·RW
    printk("R_WA %.1f ohm\n", (double)rwa);
    uint8_t n = pot.increment(0, 5);                    // Step the wiper, (channel 0–1, steps=1) → uint8_t 0–255
    // read, add, clamp, write; returns the new code
    printk("incremented to %d\n", (int)n);
    pot.shutdown(1);                                    // Shut channel down, (channel 0–1) → void
    // SD = 1: A opens, wiper shorts to B, RDAC kept
    bool sd = pot.isShutdown(1);                        // Query shutdown state, (channel 0–1) → bool
    // driver-tracked; the chip cannot report it
    printk("shutdown %d\n", (int)sd);
    k_sleep(K_MSEC(1000));
    pot.resume(1);                                      // Leave shutdown, (channel 0–1) → void
    // rewrites the kept code with SD = 0
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Restore power-up preset, () → void
    // writes 0x80 to both channels with SD = 0
    return 0;
}
