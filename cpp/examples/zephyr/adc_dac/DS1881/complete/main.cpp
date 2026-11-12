#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS1881.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS1881Full::I2C_ADDRESS);
    DS1881Full amp(connection);                         // Create DS1881 Full driver, (connection)
    amp.init();                                         // Apply defaults, () → void
                                                        // waits 1 ms, rewrites the config only if it differs

    amp.setAttenuation(0, 12.0f);                       // Set attenuation, (channel 0–1, db dB) → void
                                                        // nearest position, one command byte
    float a = amp.getAttenuation(0);                    // Read attenuation, (channel 0–1) → float dB
                                                        // three-byte round-robin read, position in dB
    printk("attenuation %d dB\n", (int)a);
    amp.setPosition(1, 30);                             // Set raw wiper position, (channel 0–1, position 0–63) → void
                                                        // position 63 is mute in Option 1
    printk("position %d\n", (int)amp.getPosition(1));   // Read wiper position, (channel 0–1) → uint8_t
                                                        // low six bits of the read-back byte
    amp.mute(1);                                        // Mute channel, (channel 0–1) → void
                                                        // writes the mute position (63)

    amp.setBoth(5, 25);                                 // Set both wipers, (position0 0–63, position1 0–63) → void
                                                        // one transaction, both wipers update together
    amp.setAttenuationBoth(20.0f);                      // Set both attenuations, (db0 dB, db1=db0 dB) → void
                                                        // db1 defaults to db0 (stereo volume)
    uint8_t p0, p1, cfgByte;
    amp.readAll(p0, p1, cfgByte);                       // Read everything, (&position0, &position1, &config) → void
                                                        // one three-byte round-robin read
    printk("positions %d %d config 0x%02X\n", (int)p0, (int)p1, (int)cfgByte);

    amp.configure(2, true, false);                      // Configure, (option=1 1–2, zeroCrossing=true, nonvolatile=false) → void
                                                        // writes the config byte only if it changed
    DS1881Config cfg = amp.getConfig();                 // Read configuration, () → DS1881Config {option, zeroCrossing, nonvolatile, raw}
                                                        // fresh read; refreshes the driver's table
    printk("option %d zc %d nv %d\n", (int)cfg.option, (int)cfg.zeroCrossing, (int)cfg.nonvolatile);
    printk("position 13 = %d dB, max position %d\n", (int)amp.positionToDb(13), (int)amp.maxPosition()); // Table lookup, (position) → float dB; Last non-mute position, () → uint8_t
                                                        // Option 2: 13 → 14 dB, max position 32
    printk("39 dB = position %d\n", (int)amp.dbToPosition(39.0f)); // Nearest position, (db dB) → uint8_t
                                                        // 70 dB and above map to mute
    amp.setOption(1);                                   // Select option, (option 1–2) → void
                                                        // positions are not converted
    amp.setZeroCrossing(false);                         // Set zero-crossing, (enabled bool) → void
                                                        // ZC bit of the configuration
    amp.setZeroCrossing(true);                          // Set zero-crossing, (enabled bool) → void
                                                        // clickless updates again
    bool ready = amp.waitReady(0.05f);                  // Acknowledge-poll, (timeoutS=0.05 s) → bool
                                                        // true once the chip ACKs its address
    printk("ready %d\n", (int)ready);

    amp.setNonvolatile(true);                           // Select EEPROM storage, (enabled bool) → void
                                                        // V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
    amp.store();                                        // Store positions, () → void
                                                        // rewrites both wipers and polls for completion
    amp.setNonvolatile(false);                          // Select EEPROM storage, (enabled bool) → void
                                                        // back to volatile wipers
    k_sleep(K_MSEC(1000));
    amp.reset();                                        // Reset, () → void
                                                        // volatile, zero-crossing, Option 1, both wipers muted
    return 0;
}
