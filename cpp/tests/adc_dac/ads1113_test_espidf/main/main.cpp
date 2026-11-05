// ESP-IDF test for ADS1113.
// Mirrors the Zephyr test for ADS1113; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1113.h"

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
    dev_cfg.device_address = ADS1113Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1113Minimal sensor(connection);                      // Create ADS1113 driver, (connection)
    ADS1113Full full(connection);                           // Create ADS1113 Full driver, (connection)
    int16_t raw = sensor.readRaw();                         // Read raw code, () → int16_t
    int16_t raw2 = sensor.readRaw();
    check_true(raw - raw2 < 4000 && raw2 - raw < 4000, "read_raw_stable");
    float v = sensor.readVoltage();                         // Read voltage, () → float V
    check_true(v - raw * 0.0000625f < 0.25f && raw * 0.0000625f - v < 0.25f, "read_voltage_matches_raw");
    full.setDataRate(ADS1113Full::DR_860);                  // Set data rate, (dr 0–7) → void
    check_true(full.getDataRate() == 7 && full.dataRateSps() == 860, "data_rate_roundtrip");
    full.startContinuous();                                 // Start continuous, () → void
    vTaskDelay(pdMS_TO_TICKS(50));
    int16_t cont = full.readContinuousRaw();                // Read continuous raw, () → int16_t
    check_true(cont - raw < 8000 && raw - cont < 8000, "continuous_matches_single_shot");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_config_mode");
    full.stopContinuous();                                  // Stop continuous, () → void
    check_true((full.readConfig() & 0x0100) != 0, "single_shot_config_mode");
    full.reset();                                           // Reset, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
