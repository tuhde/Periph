// ESP-IDF test for ADS1115.
// Mirrors the Zephyr test for ADS1115; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1115.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

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
    dev_cfg.device_address = ADS1115Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1115Minimal sensor(connection);                      // Create ADS1115 driver, (connection)
    ADS1115Full full(connection);                           // Create ADS1115 Full driver, (connection)
    int16_t raw = sensor.readRaw();                         // Read raw code, () → int16_t
    int16_t raw2 = sensor.readRaw();
    check_true(raw - raw2 < 4000 && raw2 - raw < 4000, "read_raw_stable");
    float v = sensor.readVoltage();                         // Read voltage, () → float V
    check_true(v - raw * 0.0000625f < 0.25f && raw * 0.0000625f - v < 0.25f, "read_voltage_matches_raw");
    full.setDataRate(ADS1115Full::DR_860);                  // Set data rate, (dr 0–7) → void
    check_true(full.getDataRate() == 7 && full.dataRateSps() == 860, "data_rate_roundtrip");
    full.setGain(ADS1115Full::PGA_4_096);                   // Set gain, (pga 0–5) → void
    check_true(full.getGain() == 1 && full.fullScale() == 4.096f, "gain_roundtrip");
    full.setGain(ADS1115Full::PGA_2_048);
    full.startContinuous();                                 // Start continuous, () → void
    vTaskDelay(pdMS_TO_TICKS(50));
    int16_t cont = full.readContinuousRaw();                // Read continuous raw, () → int16_t
    check_true(cont - raw < 8000 && raw - cont < 8000, "continuous_matches_single_shot");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_config_mode");
    full.stopContinuous();                                  // Stop continuous, () → void
    check_true((full.readConfig() & 0x0100) != 0, "single_shot_config_mode");
    for (uint8_t ch = 0; ch < 4; ch++) {
        int16_t r = full.readRaw(ch);
        check_true(r > -2000, "single_ended_channels");
    }
    int16_t dr = full.readDifferentialRaw(ADS1115Full::DIFF_2_3);// Read differential raw, (pair 0–3) → int16_t
    (void)dr;
    check_true(!full.isConverting(), "differential_done");
    full.setLowThresholdRaw(-1000);
    full.setHighThresholdRaw(1000);
    int16_t lo = 0, hi = 0;
    full.getThresholdsRaw(&lo, &hi);                        // Get thresholds, (int16_t* low, int16_t* high) → void
    check_true(lo == -1000 && hi == 1000, "threshold_roundtrip");
    full.configureComparator(ADS1115Full::COMP_WINDOW, ADS1115Full::ACTIVE_LOW, false, ADS1115Full::QUEUE_4);
    check_true((full.readConfig() & 0x0017) == 0x0012, "comparator_bits");
    full.disableComparator();
    check_true((full.readConfig() & 0x0003) == 0x0003, "comparator_disabled");
    full.reset();                                           // Reset, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
