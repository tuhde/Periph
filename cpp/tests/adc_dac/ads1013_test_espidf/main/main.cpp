// ESP-IDF test for ADS1013.
// Mirrors the Zephyr test for ADS1013; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1013.h"

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
    dev_cfg.device_address = ADS1013Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    ADS1013Minimal adc(connection);                     // Create ADS1013 driver, (connection)
    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
    check_true(raw >= -2048 && raw <= 2047, "raw_in_range");
    float v = adc.readVoltage();                        // Read voltage, () → V
    check_true(v > -2.1f && v < 2.1f, "voltage_plausible");

    ADS1013Full full(connection);                       // Create ADS1013 Full driver, (connection)
    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");  // Read CONFIG register, () → uint16
    check_true(full.fullScale() == 2.048f && full.lsb() == 0.001f, "full_scale_fixed");

    full.setDataRate(ADS1013Full::DR_250);              // Set data rate, (dr 0–6) → bool
    check_true(full.getDataRate() == ADS1013Full::DR_250 && full.dataRateSps() == 250, "dr_roundtrip");
    full.readRaw();                                     // Read raw code, () → int16
    check_true(((full.readConfig() >> 5) & 0x7) == ADS1013Full::DR_250, "config_dr_bits");
    full.setDataRate(ADS1013Full::DR_1600);             // Set data rate, (dr 0–6) → bool

    full.startContinuous();                             // Start continuous conversion, () → void
    vTaskDelay(pdMS_TO_TICKS(50));
    int16_t cont = full.readContinuousRaw();            // Read latest code, () → int16
    check_true(cont >= -2048 && cont <= 2047, "continuous_raw_in_range");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_mode_bit");
    full.stopContinuous();                              // Stop continuous conversion, () → void
    check_true((full.readConfig() & 0x0100) != 0, "stop_mode_bit");

    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "final_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
