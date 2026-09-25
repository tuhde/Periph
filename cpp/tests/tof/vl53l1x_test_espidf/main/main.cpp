// ESP-IDF test for VL53L1X.
// Mirrors the Zephyr test for VL53L1X; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "VL53L1X.h"

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
    dev_cfg.device_address = VL53L1XMinimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    VL53L1XMinimal sensor(connection);                      // Create VL53L1X driver, (connection)
    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
    check_true(d != VL53L1XMinimal::TIMEOUT, "distance_completes");
    sensor.rangeValid();                                    // Check last measurement, () → bool

    VL53L1XFull full(connection);                           // Create VL53L1X Full driver, (connection)
    check_true(full.modelId() == 0xEA, "model_id");         // Read model ID, () → uint8_t
    check_true(full.moduleType() == 0xCC, "module_type");   // Read module type, () → uint8_t
    check_true(full.revisionId() > 0, "revision_id");       // Read revision ID, () → uint8_t
    check_true(full.timingBudget() == 100000, "default_budget");  // Read timing budget, () → uint32_t µs
    check_true(full.distanceMode() == VL53L1XFull::DistanceMode::Long, "default_mode");  // Read distance mode, () → DistanceMode

    full.distance();                                        // Measure distance, () → uint16_t mm
    VL53L1XFull::Measurement m = full.readMeasurement();    // Read result block, () → Measurement
    check_true(m.signalRateMcps >= 0.0f && m.effectiveSpadCount >= 0.0f, "measurement_record");

    full.setTimingBudget(50000);                            // Set timing budget, (budgetUs µs) → bool
    check_true(full.timingBudget() == 50000, "budget_roundtrip");  // Read timing budget, () → uint32_t µs
    full.setDistanceMode(VL53L1XFull::DistanceMode::Short);  // Set distance mode, (mode) → bool
    check_true(full.distanceMode() == VL53L1XFull::DistanceMode::Short &&
               full.timingBudget() == 50000, "mode_short");
    check_true(full.distance() != VL53L1XMinimal::TIMEOUT, "short_distance");  // Measure distance, () → uint16_t mm
    full.setDistanceMode(VL53L1XFull::DistanceMode::Long);  // Set distance mode, (mode) → bool
    full.setTimingBudget(100000);                           // Set timing budget, (budgetUs µs) → bool

    full.setSignalRateLimit(0.5f);                          // Set signal-rate limit, (limitMcps MCPS) → bool
    check_true(full.signalRateLimit() == 0.5f, "signal_rate_roundtrip");  // Read signal-rate limit, () → float MCPS
    full.setSignalRateLimit(1.0f);                          // Set signal-rate limit, (limitMcps MCPS) → bool
    full.setSigmaThreshold(60);                             // Set sigma threshold, (sigmaMm mm) → bool
    check_true(full.sigmaThreshold() == 60, "sigma_roundtrip");  // Read sigma threshold, () → uint16_t mm
    full.setSigmaThreshold(90);                             // Set sigma threshold, (sigmaMm mm) → bool

    uint8_t w = 0, h = 0;
    full.setRoi(8, 8);                                      // Set ROI size, (width SPADs, height SPADs) → bool
    full.roi(w, h);                                         // Read ROI size, (width&, height&) → void
    check_true(w == 8 && h == 8, "roi_roundtrip");
    full.opticalCenter();                                   // Read optical-centre SPAD, () → uint8_t
    full.setRoi(16, 16);                                    // Set ROI size, (width SPADs, height SPADs) → bool
    full.roi(w, h);                                         // Read ROI size, (width&, height&) → void
    check_true(w == 16 && h == 16 && full.roiCenter() == 199, "roi_restored");  // Read ROI centre, () → uint8_t

    float original = full.offset();                         // Read range offset, () → float mm
    full.setOffset(-10.25f);                                // Set range offset, (offsetMm mm) → bool
    check_true(full.offset() == -10.25f, "offset_roundtrip");
    full.setOffset(original);                               // Set range offset, (offsetMm mm) → bool
    full.setCrosstalkCompensation(0.01f);                   // Set crosstalk compensation, (rateMcps MCPS) → bool
    float xt = full.crosstalkCompensation();                // Read crosstalk compensation, () → float MCPS
    check_true(xt > 0.0099f && xt < 0.0101f, "crosstalk_roundtrip");
    full.setCrosstalkCompensation(0.0f);                    // Set crosstalk compensation, (rateMcps MCPS) → bool

    full.setInterruptThresholds(100, 800);                  // Set distance thresholds, (lowMm mm, highMm mm) → bool
    uint16_t lo = 0, hi = 0;
    full.interruptThresholds(lo, hi);                       // Read distance thresholds, (lowMm&, highMm&) → void
    check_true(lo == 100 && hi == 800, "thresholds_roundtrip");

    full.startContinuous(150);                              // Start continuous ranging, (periodMs=0 ms) → bool
    uint32_t period = full.interMeasurement();              // Read inter-measurement period, () → uint32_t ms
    check_true(period >= 148 && period <= 150, "inter_measurement");
    bool contOk = true;
    for (int i = 0; i < 3; i++) contOk = full.readContinuous() != VL53L1XMinimal::TIMEOUT && contOk;  // Read next continuous result, () → uint16_t mm
    check_true(contOk, "continuous_readings");
    vTaskDelay(pdMS_TO_TICKS(200));
    check_true(full.pollInterrupt() == VL53L1XFull::SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample");  // Read and clear interrupt, () → uint8_t
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(200));
    full.pollInterrupt();                                   // Read and clear interrupt, () → uint8_t

    check_true(full.recalibrate(), "recalibrate");          // Run temperature update, () → bool
    check_true(full.distance() != VL53L1XMinimal::TIMEOUT, "recalibrate_then_distance");  // Measure distance, () → uint16_t mm

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
