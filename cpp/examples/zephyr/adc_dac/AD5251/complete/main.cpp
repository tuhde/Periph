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
    pot.setWiper(0, 40);                               // Set wiper position, (channel 0–1, code 0–63) → bool
    // writes [instruction, code]; the instruction byte selects the channel
    uint8_t w = pot.getWiper(0);                        // Read wiper position, (channel 0–1) → uint8_t 0–63
    // sends the instruction alone, then reads one byte
    printk("wiper %d\n", (int)w);
    pot.setResistance(0, 25000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    // inverts R = D/64·RAB + RW to the nearest code
    float r = pot.getResistance(0);                     // Read W–B resistance, (channel 0–1) → float Ω
    // evaluates D/64·RAB + RW for the current code
    printk("R_WB %.1f ohm\n", (double)r);
    pot.setBoth(8, 56);                               // Write both wipers, (code0 0–63, code1 0–63) → void
    // two transactions, channel 0 then channel 1
    pot.setRatio(0, 0.25f);                             // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    // code = round(ratio × 64), clamped to 63
    float ratio = pot.getRatio(0);                      // Read divider ratio, (channel 0–1) → float
    // code / 64
    printk("ratio %.4f\n", (double)ratio);
    pot.setResistanceWa(1, 15000.0f);                    // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa(1);                 // Read W–A resistance, (channel 0–1) → float Ω
    // (64 − D)/64·RAB + RW
    printk("R_WA %.1f ohm\n", (double)rwa);
    pot.increment(0);                                   // Step up one code, (channel=BOTH 0–1) → bool
    // quick command; BOTH addresses both channels
    pot.decrement(0);                                   // Step down one code, (channel=BOTH 0–1) → bool
    // quick command; saturates at 0
    pot.doubleCode(0);                                  // Raise by 6 dB, (channel=BOTH 0–1) → bool
    // quick command; the code doubles, saturating at 63
    pot.halve(0);                                       // Lower by 6 dB, (channel=BOTH 0–1) → bool
    // quick command; the code is halved
    pot.store();                                        // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    // quick command, then ACK polling (≈ 26 ms)
    pot.setBoth(1, 2);
    pot.restore();                                      // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    // quick command followed by NOP
    printk("restored %d\n", (int)pot.getWiper(0));
    pot.writeEemem(4, 0x5A);                            // Write user EEMEM byte, (index 4–15, value 0–63) → bool
    // followed by ACK polling (≈ 26 ms)
    uint8_t e = pot.readEemem(4);                       // Read user EEMEM byte, (index 4–15) → uint8_t
    // pointer write, then a one-byte read
    printk("eemem4 %d\n", (int)e);
    float tol = pot.readTolerance(0);                   // Read stored RAB tolerance, (channel 0–1) → float %
    // sign-magnitude integer byte + decimal byte / 256
    printk("tolerance %d\n", (int)(tol));
    float ra = pot.rabActual(0);                        // Actual RAB of a channel, (channel 0–1) → float Ω
    // rAb × (1 + tolerance / 100)
    printk("rab_actual %d\n", (int)(ra));
    pot.nop();                                          // Send NOP, () → void
    // quick command 0x80; the chip returns to idle
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Reset from EEMEM, () → bool
    // quick command 0xB8, then ACK polling
    return 0;
}
