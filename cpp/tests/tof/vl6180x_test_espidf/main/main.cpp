// ESP-IDF test for VL6180X.
// Mirrors the Zephyr test for VL6180X; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "VL6180X.h"

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
    dev_cfg.device_address = VL6180XMinimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev, nullptr, nullptr, 2);

    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    check_true(sensor.init(), "init");                      // Initialize, () → bool
    uint8_t mm = 0;
    check_true(sensor.read_range(mm) || sensor.last_error() != VL6180XMinimal::ERROR_TIMEOUT, "read_range_completes"); // Single-shot range, (uint8_t& mm) → bool

    VL6180XFull full(connection);                           // Create VL6180X Full driver, (connection)
    check_true(full.init(), "full_init");
    check_true(full.model_id() == 0xB4, "model_id");        // Read model ID, () → uint8_t
    uint8_t major = 0, minor = 0;
    full.revision(major, minor);                            // Model revision, (uint8_t& major, uint8_t& minor) → void
    check_true(major >= 1, "revision");
    uint8_t err = 0;
    check_true(full.read_range_status(mm, err) && err <= 15, "range_status"); // Range and error code, (uint8_t& mm, uint8_t& err) → bool
    uint16_t raw = 0;
    check_true(full.read_als_raw(raw), "als_raw");          // Single-shot ALS count, (uint16_t& raw) → bool
    check_true(full.read_lux() >= 0.0f, "lux_positive");    // Single-shot ALS in lux, () → float lux
    check_true(full.set_als_gain(0) && full.read_lux() >= 0.0f, "lux_gain_20"); // ALS gain code, (uint8_t code) → bool
    full.set_als_gain(6);
    check_true(full.set_als_integration_time(50) && full.read_lux() >= 0.0f, "lux_50ms"); // ALS integration time, (uint16_t ms) → bool
    full.set_als_integration_time(100);
    full.set_als_scaler(1);                                 // ALS result scaler, (uint8_t scaler) → bool

    full.set_range_offset(3);                               // Part-to-part offset, (int8_t mm) → void
    check_true(full.get_range_offset() == 3, "offset_roundtrip"); // Read the offset, () → int8_t mm
    full.set_range_offset(0);
    full.set_crosstalk_rate(0.0f);                          // Crosstalk rate, (float rate_mcps) → bool
    full.set_max_convergence_time(49);                      // Max convergence time, (uint8_t ms) → bool
    full.set_averaging_period(48);                          // Averaging period, (uint8_t units) → void
    check_true(full.recalibrate_vhv(), "recalibrate_vhv");  // Recalibrate VHV, () → bool
    check_true(full.read_range_status(mm, err), "range_after_recalibration");

    check_true(full.start_continuous_range(50), "start_continuous_range"); // Start continuous ranging, (uint16_t period_ms) → bool
    bool continuousOk = true;
    for (int i = 0; i < 5; i++) {
        bool ok = full.read_range_continuous(mm) || full.last_error() != VL6180XMinimal::ERROR_TIMEOUT; // Wait for next sample, (uint8_t& mm) → bool
        continuousOk = continuousOk && ok;
    }
    check_true(continuousOk, "continuous_samples");
    full.stop_continuous_range();                           // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(200));

    full.set_range_thresholds(30, 80);                      // Range thresholds, (uint8_t low, uint8_t high) → bool
    full.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW); // Enable an interrupt, (uint8_t source, uint8_t mode) → bool
    full.start_continuous_range(50);
    vTaskDelay(pdMS_TO_TICKS(300));
    check_true(full.poll_interrupt() <= 7, "poll_interrupt_mask"); // Read and clear, () → uint8_t
    full.stop_continuous_range();
    vTaskDelay(pdMS_TO_TICKS(200));
    full.disable_interrupt(VL6180XFull::SOURCE_RANGE);      // Disable an interrupt, (uint8_t source) → bool
    full.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_NEW_SAMPLE_READY);
    full.poll_interrupt();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
