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

    // --- Fade the volume down and back up ---
    // Zero-crossing detection (on by default) moves the wiper only at a signal
    // zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
    // than the 50 ms maximum zero-crossing window.
    for (int step = 0; step < 42; step++) {
        int db = step <= 20 ? step * 2 : (41 - step) * 2;
        amp.setAttenuationBoth((float)db);              // Set both attenuations, (db0 dB, db1=db0 dB) → void
        uint8_t p0, p1, cfgByte;
        amp.readAll(p0, p1, cfgByte);                   // Read everything, (&position0, &position1, &config) → void
        printk("%2d dB -> positions %d %d\n", db, (int)p0, (int)p1);
        k_sleep(K_MSEC(100));
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    printk("channel 0 muted: %d dB\n", (int)amp.getAttenuation(0)); // Read attenuation, (channel 0–1) → float dB
    k_sleep(K_MSEC(1000));
    amp.setAttenuation(0, 0.0f);                        // Set attenuation, (channel 0–1, db dB) → void

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    amp.setOption(2);                                   // Select option, (option 1–2) → void
    static const int table[] = {0, 12, 13, 24, 25, 32, 33};
    for (int i = 0; i < 7; i++) {
        printk("position %d -> %d dB\n", table[i], (int)amp.positionToDb(table[i])); // Table lookup, (position) → float dB
    }
    amp.setOption(1);                                   // Select option, (option 1–2) → void
    amp.setAttenuationBoth(10.0f);                      // Set both attenuations, (db0 dB, db1=db0 dB) → void
    DS1881Config cfg = amp.getConfig();                 // Read configuration, () → DS1881Config {option, zeroCrossing, nonvolatile, raw}
    printk("option %d zc %d nv %d\n", (int)cfg.option, (int)cfg.zeroCrossing, (int)cfg.nonvolatile);

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested (--store on Linux).
    if (false) {
        printk("WARNING: EEPROM endurance is 50 000 writes\n");
        amp.setNonvolatile(true);                       // Select EEPROM storage, (enabled bool) → void
        amp.store();                                    // Store positions, () → void
        printk("store acknowledged: %d\n", (int)amp.waitReady(0.05f)); // Acknowledge-poll, (timeoutS=0.05 s) → bool
        amp.setNonvolatile(false);                      // Select EEPROM storage, (enabled bool) → void
    }
    return 0;
}
