#include <stdarg.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "VL53L1X.h"

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onSample(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XFull sensor(connection);                         // Create VL53L1X Full driver, (connection)
                                                            // runs init: boot poll, ID check, ULD default config

    logPrintf("model 0x%02X\n", sensor.modelId());             // Read model ID, () → uint8_t
                                                            // IDENTIFICATION__MODEL_ID, always 0xEA
    logPrintf("module 0x%02X\n", sensor.moduleType());         // Read module type, () → uint8_t
                                                            // IDENTIFICATION__MODULE_TYPE, always 0xCC
    logPrintf("revision 0x%02X\n", sensor.revisionId());       // Read revision ID, () → uint8_t
                                                            // mask revision, 0x10

    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
                                                            // single shot; blocks for about one timing budget
    logPrintf("distance %u mm, valid %d\n", (unsigned)d, sensor.rangeValid());  // Check last measurement, () → bool
                                                            // mapped range status == 0
    logPrintf("range status %u\n", sensor.rangeStatus());      // Read last range status, () → uint8_t
                                                            // 0 = valid, 2 = signal fail, 4 = out of bounds
    VL53L1XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
                                                            // distance, status, signal/ambient MCPS, SPAD count
    logPrintf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs\n",
           (double)m.signalRateMcps, (double)m.ambientRateMcps, (double)m.effectiveSpadCount);

    bool isLong = sensor.distanceMode() == VL53L1XFull::DistanceMode::Long;  // Read distance mode, () → DistanceMode
                                                            // from PHASECAL_CONFIG__TIMEOUT_MACROP
    logPrintf("mode %s\n", isLong ? "long" : "short");
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Short);  // Set distance mode, (mode) → bool
                                                            // ~1.3 m, robust in sunlight; keeps the budget
    logPrintf("budget %lu us\n", (unsigned long)sensor.timingBudget());  // Read timing budget, () → uint32_t µs
                                                            // decoded from the range timeout A register
    sensor.setTimingBudget(33000);                          // Set timing budget, (budgetUs µs) → bool
                                                            // ULD table values 15000 (short only) … 500000
    logPrintf("short mode %u mm\n", (unsigned)sensor.distance());  // Measure distance, () → uint16_t mm
                                                            // 33 ms single shot
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Long);  // Set distance mode, (mode) → bool
                                                            // back to up to 4 m in the dark
    sensor.setTimingBudget(100000);                         // Set timing budget, (budgetUs µs) → bool
                                                            // default 100 ms

    sensor.setInterMeasurement(200);                        // Set inter-measurement period, (periodMs ms) → bool
                                                            // must be ≥ the timing budget
    logPrintf("period %lu ms\n", (unsigned long)sensor.interMeasurement());  // Read inter-measurement period, () → uint32_t ms
                                                            // oscillator ticks scaled by the PLL calibration
    sensor.startContinuous(200);                            // Start continuous ranging, (periodMs=0 ms) → bool
                                                            // timed mode; 0 = as fast as the budget allows
    for (int i = 0; i < 5; i++) {
        logPrintf("continuous %u mm\n", (unsigned)sensor.readContinuous());  // Read next continuous result, () → uint16_t mm
                                                            // waits for data ready, then clears it
    }
    while (!sensor.dataReady()) {                           // Check for a result, () → bool
        delay(10);                                       // GPIO1 line asserted
    }
    logPrintf("record %u mm\n", (unsigned)sensor.readMeasurement().distanceMm);  // Read result block, () → Measurement
                                                            // non-blocking; clears the interrupt
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // does not wait for a running measurement
    delay(250);

    logPrintf("signal limit %.3f MCPS\n", (double)sensor.signalRateLimit());  // Read signal-rate limit, () → float MCPS
                                                            // 9.7 fixed point, default 1.0
    sensor.setSignalRateLimit(0.5f);                        // Set signal-rate limit, (limitMcps MCPS) → bool
                                                            // lower = longer range, more noise
    sensor.setSignalRateLimit(1.0f);                        // Set signal-rate limit, (limitMcps MCPS) → bool
                                                            // restore the default
    logPrintf("sigma %u mm\n", (unsigned)sensor.sigmaThreshold());  // Read sigma threshold, () → uint16_t mm
                                                            // 14.2 fixed point, default 90
    sensor.setSigmaThreshold(60);                           // Set sigma threshold, (sigmaMm mm) → bool
                                                            // stricter repeatability filter
    sensor.setSigmaThreshold(90);                           // Set sigma threshold, (sigmaMm mm) → bool
                                                            // restore the default

    uint8_t centre = sensor.opticalCenter();                // Read optical-centre SPAD, () → uint8_t
                                                            // factory NVM value for this part's lens
    logPrintf("optical centre %u\n", (unsigned)centre);
    sensor.setRoi(8, 8);                                    // Set ROI size, (width SPADs, height SPADs) → bool
                                                            // 4–16 each; narrows the field of view
    sensor.setRoiCenter(centre);                            // Set ROI centre, (spad) → void
                                                            // align the narrow ROI with the lens
    uint8_t w = 0, h = 0;
    sensor.roi(w, h);                                       // Read ROI size, (width&, height&) → void
                                                            // in SPADs
    logPrintf("roi %ux%u centre %u\n", (unsigned)w, (unsigned)h, (unsigned)sensor.roiCenter());  // Read ROI centre, () → uint8_t
                                                            // SPAD number
    sensor.setRoi(16, 16);                                  // Set ROI size, (width SPADs, height SPADs) → bool
                                                            // full array; re-centres on SPAD 199

    float original = sensor.offset();                       // Read range offset, () → float mm
                                                            // NVM factory value, 0.25 mm steps
    sensor.setOffset(original - 5.0f);                      // Set range offset, (offsetMm mm) → bool
                                                            // volatile override, −1024.0 to 1023.75 mm
    logPrintf("offset %.2f mm\n", (double)sensor.offset());    // Read range offset, () → float mm
                                                            // 13-bit two's complement × 0.25
    sensor.setCrosstalkCompensation(0.01f);                 // Set crosstalk compensation, (rateMcps MCPS) → bool
                                                            // per-SPAD rate, 7.9 kcps register
    logPrintf("crosstalk %.4f MCPS\n", (double)sensor.crosstalkCompensation());  // Read crosstalk compensation, () → float MCPS
                                                            // 0 = off
    float calOffset = 0.0f, calXtalk = 0.0f;
    sensor.calibrateOffset(140, calOffset);                 // Calibrate offset, (targetMm mm, offsetMm&) → bool
                                                            // 50 samples against a target at 140 mm; applies it
    sensor.calibrateCrosstalk(600, calXtalk);               // Calibrate crosstalk, (targetMm mm, rateMcps&) → bool
                                                            // 50 samples against a target at 600 mm; applies it
    logPrintf("calibrated offset %.2f mm, crosstalk %.4f MCPS\n", (double)calOffset, (double)calXtalk);
    sensor.setOffset(original);                             // Set range offset, (offsetMm mm) → bool
                                                            // restore the factory value
    sensor.setCrosstalkCompensation(0.0f);                  // Set crosstalk compensation, (rateMcps MCPS) → bool
                                                            // compensation off

    sensor.recalibrate();                                   // Run temperature update, () → bool
                                                            // full VHV; after a > 8 °C change, not while ranging

    sensor.setInterruptThresholds(100, 800);                // Set distance thresholds, (lowMm mm, highMm mm) → bool
                                                            // 1 mm resolution
    uint16_t lo = 0, hi = 0;
    sensor.interruptThresholds(lo, hi);                     // Read distance thresholds, (lowMm&, highMm&) → void
                                                            // (low, high) in mm
    logPrintf("thresholds %u..%u mm\n", (unsigned)lo, (unsigned)hi);
    sensor.enableInterrupt(VL53L1XFull::SOURCE_IN_WINDOW);  // Select interrupt source, (source) → bool
                                                            // fires while 100 mm ≤ range ≤ 800 mm
    sensor.disableInterrupt(VL53L1XFull::SOURCE_IN_WINDOW);  // Disable interrupt source, (source) → void
                                                            // reverts to new-sample-ready (no disabled state)
    sensor.enableInterrupt(VL53L1XFull::SOURCE_NEW_SAMPLE_READY);  // Select interrupt source, (source) → bool
                                                            // the default data-ready source

    sensor.onInterrupt(onSample);                           // Subscribe to GPIO1, (callback, intPin=nullptr) → void
                                                            // interrupt is cleared before the callback
    sensor.startContinuous(200);                            // Start continuous ranging, (periodMs=0 ms) → bool
                                                            // timed mode feeds the subscription
    delay(1000);
    if (pending) logPrintf("interrupt, source %u\n", pendingStatus);
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // no more samples
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler
    logPrintf("pending %u\n", sensor.pollInterrupt());         // Read and clear interrupt, () → uint8_t
                                                            // active SOURCE_* value, 0 = nothing pending

    sensor.setAddress(0x30);                                // Change I²C address, (address) → bool
                                                            // volatile; this driver instance is now unusable
    I2CConnection movedConnection(Wire, 0x30);
    VL53L1XFull moved(movedConnection);                     // Create VL53L1X Full driver, (connection)
                                                            // re-init at the new address is safe
    logPrintf("at 0x30 %u mm\n", (unsigned)moved.distance());  // Measure distance, () → uint16_t mm
                                                            // same sensor, new address
    moved.setAddress(VL53L1XMinimal::I2C_ADDRESS);          // Change I²C address, (address) → bool
                                                            // back to the power-on 0x29
}

void loop() {}
