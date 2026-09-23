// ESP-IDF test for VL53L0X.
// Mirrors the Zephyr test for VL53L0X; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "VL53L0X.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

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
        .device_address  = VL53L0XMinimal::I2C_ADDRESS,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    VL53L0XMinimal sensor(connection);                      // Create VL53L0X driver, (connection)
    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
    check_true(d <= 8191, "distance_in_range");
    sensor.rangeValid();                                    // Check last measurement, () → bool

    VL53L0XFull full(connection);                           // Create VL53L0X Full driver, (connection)
    check_true(full.modelId() == 0xEE, "model_id");         // Read model ID, () → uint8_t
    check_true(full.revisionId() > 0, "revision_id");       // Read revision ID, () → uint8_t
    uint32_t budget = full.timingBudget();                  // Read timing budget, () → uint32_t µs
    check_true(budget >= 20000 && budget <= 40000, "default_budget");

    full.distance();                                        // Measure distance, () → uint16_t mm
    VL53L0XFull::Measurement m = full.readMeasurement();    // Read result block, () → Measurement
    check_true(m.rangeStatus <= 15 && m.signalRateMcps >= 0.0f, "measurement_record");

    full.setTimingBudget(50000);                            // Set timing budget, (budgetUs µs) → bool
    budget = full.timingBudget();                           // Read timing budget, () → uint32_t µs
    check_true(budget > 49700 && budget < 50300, "budget_roundtrip");
    full.setSignalRateLimit(0.1f);                          // Set signal-rate limit, (limitMcps MCPS) → bool
    float limit = full.signalRateLimit();                   // Read signal-rate limit, () → float MCPS
    check_true(limit > 0.09f && limit < 0.11f, "signal_rate_roundtrip");
    full.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange, 18);    // Set VCSEL period, (type, pclks) → bool
    full.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange, 14);  // Set VCSEL period, (type, pclks) → bool
    check_true(full.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange) == 18 &&
               full.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange) == 14, "vcsel_roundtrip");
    full.setProfile(VL53L0XFull::Profile::Default);         // Apply ranging profile, (profile) → bool
    budget = full.timingBudget();                           // Read timing budget, () → uint32_t µs
    check_true(full.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange) == 14 &&
               budget > 32700 && budget < 33300, "profile_default");

    float original = full.offset();                         // Read range offset, () → float mm
    full.setOffset(-10.25f);                                // Set range offset, (offsetMm mm) → bool
    check_true(full.offset() == -10.25f, "offset_roundtrip");
    full.setOffset(original);                               // Set range offset, (offsetMm mm) → bool

    full.setInterruptThresholds(100, 800);                  // Set distance thresholds, (lowMm mm, highMm mm) → bool
    uint16_t lo = 0, hi = 0;
    full.interruptThresholds(lo, hi);                       // Read distance thresholds, (lowMm&, highMm&) → void
    check_true(lo == 100 && hi == 800, "thresholds_roundtrip");

    // Back-to-back continuous ranging, then timed mode.
    full.startContinuous();                                 // Start continuous ranging, (periodMs=0 ms) → void
    bool contOk = true;
    for (int i = 0; i < 3; i++) contOk = full.readContinuous() <= 8191 && contOk;  // Read next continuous result, () → uint16_t mm
    check_true(contOk, "continuous_readings");
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(50));
    full.pollInterrupt();                                   // Read and clear status, () → uint8_t
    full.startContinuous(100);                              // Start continuous ranging, (periodMs=0 ms) → void
    bool timedOk = true;
    for (int i = 0; i < 2; i++) timedOk = full.readContinuous() <= 8191 && timedOk;  // Read next continuous result, () → uint16_t mm
    check_true(timedOk, "timed_readings");
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(150));
    full.pollInterrupt();                                   // Read and clear status, () → uint8_t

    full.startContinuous();                                 // Start continuous ranging, (periodMs=0 ms) → void
    vTaskDelay(pdMS_TO_TICKS(100));
    check_true(full.pollInterrupt() == VL53L0XFull::SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample");
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(50));
    full.pollInterrupt();                                   // Read and clear status, () → uint8_t

    check_true(full.recalibrate(), "recalibrate");          // Rerun reference calibration, () → bool
    check_true(full.distance() <= 8191, "recalibrate_then_distance");  // Measure distance, () → uint16_t mm

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
