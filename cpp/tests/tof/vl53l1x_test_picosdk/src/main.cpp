#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "VL53L1X.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    I2CConnectionPicoSDK connection(i2c0, VL53L1XMinimal::I2C_ADDRESS);

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
    sleep_ms(200);
    check_true(full.pollInterrupt() == VL53L1XFull::SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample");  // Read and clear interrupt, () → uint8_t
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    sleep_ms(200);
    full.pollInterrupt();                                   // Read and clear interrupt, () → uint8_t

    check_true(full.recalibrate(), "recalibrate");          // Run temperature update, () → bool
    check_true(full.distance() != VL53L1XMinimal::TIMEOUT, "recalibrate_then_distance");  // Measure distance, () → uint16_t mm

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
