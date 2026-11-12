#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DS1881.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = DS1881Full::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
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
        printf("%2d dB -> positions %d %d\n", db, (int)p0, (int)p1);
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    printf("channel 0 muted: %d dB\n", (int)amp.getAttenuation(0)); // Read attenuation, (channel 0–1) → float dB
    vTaskDelay(pdMS_TO_TICKS(1000));
    amp.setAttenuation(0, 0.0f);                        // Set attenuation, (channel 0–1, db dB) → void

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    amp.setOption(2);                                   // Select option, (option 1–2) → void
    static const int table[] = {0, 12, 13, 24, 25, 32, 33};
    for (int i = 0; i < 7; i++) {
        printf("position %d -> %d dB\n", table[i], (int)amp.positionToDb(table[i])); // Table lookup, (position) → float dB
    }
    amp.setOption(1);                                   // Select option, (option 1–2) → void
    amp.setAttenuationBoth(10.0f);                      // Set both attenuations, (db0 dB, db1=db0 dB) → void
    DS1881Config cfg = amp.getConfig();                 // Read configuration, () → DS1881Config {option, zeroCrossing, nonvolatile, raw}
    printf("option %d zc %d nv %d\n", (int)cfg.option, (int)cfg.zeroCrossing, (int)cfg.nonvolatile);

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested (--store on Linux).
    if (false) {
        printf("WARNING: EEPROM endurance is 50 000 writes\n");
        amp.setNonvolatile(true);                       // Select EEPROM storage, (enabled bool) → void
        amp.store();                                    // Store positions, () → void
        printf("store acknowledged: %d\n", (int)amp.waitReady(0.05f)); // Acknowledge-poll, (timeoutS=0.05 s) → bool
        amp.setNonvolatile(false);                      // Select EEPROM storage, (enabled bool) → void
    }
}
