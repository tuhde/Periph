// Auto-generated ESP-IDF example for Rda5807m (Complete).
// Mirrors the Arduino Rda5807m_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "Rda5807m.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x10,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    RDA5807MFull chip(transport);  // Create Rda5807m driver
    float freq, found;
    bool found_ok;
    uint16_t blk_a, blk_b, blk_c, blk_d;
    bool rds, st_stereo, st_station, st_ready;
    uint8_t rssi;
    chip.set_frequency(100.0f);                       // Tune to frequency, (frequency_mhz) → void
    chip.frequency();                                 // Read current frequency, () → float MHz
    chip.set_volume(10);                              // Set volume, (level 0-15) → void
    chip.mute(true);                                  // Mute output, (enable) → void
    chip.mute(false);                                 // Unmute output, (enable) → void
    chip.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K, 50, 8, 1, 0, 0, 0);  // Configure, (band 0-3, space 0-3, de_emphasis 50/75, seek_threshold 0-15, seek_mode, clk_mode 0-7, afc_disable, east_europe_50m) → void
    chip.set_bass_boost(true);                        // Enable bass boost, (enable) → void
    chip.set_mono(false);                             // Allow stereo, (enable) → void
    chip.set_softmute(true);                          // Enable soft mute, (enable) → void
    chip.enable_rds(true);                            // Enable RDS, (enable) → void
    chip.rds_ready();                                 // Check RDS ready, () → bool
    chip.read_rds_group(blk_a, blk_b, blk_c, blk_d);  // Read RDS group, (block_a, block_b, block_c, block_d) → bool
    chip.is_stereo();                                 // Check stereo, () → bool
    chip.is_station();                                // Check station, () → bool
    chip.is_ready();                                  // Check tuner ready, () → bool
    chip.signal_strength();                           // Read RSSI, () → uint8_t
    chip.standby(false);                              // Power up, (enable) → void
    chip.soft_reset();                                // Soft reset, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
