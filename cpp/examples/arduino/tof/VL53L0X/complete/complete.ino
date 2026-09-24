#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

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
    I2CConnection connection(Wire, VL53L0XMinimal::I2C_ADDRESS);
    VL53L0XFull sensor(connection);                         // Create VL53L0X Full driver, (connection)
                                                            // runs init: ID check, tuning, SPADs, VHV + phase calibration

    logPrintf("model 0x%02X\n", sensor.modelId());             // Read model ID, () → uint8_t
                                                            // IDENTIFICATION_MODEL_ID, always 0xEE
    logPrintf("revision 0x%02X\n", sensor.revisionId());       // Read revision ID, () → uint8_t
                                                            // IDENTIFICATION_REVISION_ID, 0x10 on current silicon

    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
                                                            // single shot; blocks for about one timing budget
    logPrintf("distance %u mm, valid %d\n", (unsigned)d, sensor.rangeValid());  // Check last measurement, () → bool
                                                            // device range status == 11 (range complete)
    logPrintf("range status %u\n", sensor.rangeStatus());      // Read last range status, () → uint8_t 0–15
                                                            // 11 = valid, 4 = no target
    VL53L0XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
                                                            // distance, status, signal/ambient MCPS, SPAD count
    logPrintf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs\n",
           (double)m.signalRateMcps, (double)m.ambientRateMcps, (double)m.effectiveSpadCount);

    sensor.startContinuous();                               // Start continuous ranging, (periodMs=0 ms) → void
                                                            // 0 = back-to-back measurements
    for (int i = 0; i < 5; i++) {
        logPrintf("continuous %u mm\n", (unsigned)sensor.readContinuous());  // Read next continuous result, () → uint16_t mm
                                                            // waits for a fresh data-ready, then clears it
    }
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // does not wait for a running measurement

    sensor.startContinuous(100);                            // Start continuous ranging, (periodMs=0 ms) → void
                                                            // timed mode: one measurement every 100 ms
    while (!sensor.dataReady()) {                           // Check for a result, () → bool
        delay(10);                                       // RESULT_INTERRUPT_STATUS bits 2:0 non-zero
    }
    logPrintf("timed %u mm\n", (unsigned)sensor.readMeasurement().distanceMm);  // Read result block, () → Measurement
                                                            // non-blocking; clears the interrupt
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // back to software standby

    logPrintf("budget %lu us\n", (unsigned long)sensor.timingBudget());  // Read timing budget, () → uint32_t µs
                                                            // computed from the sequence-step timeouts
    sensor.setTimingBudget(50000);                          // Set timing budget, (budgetUs µs) → bool
                                                            // longer budget = lower noise, ≥ 20000 µs
    logPrintf("signal limit %.3f MCPS\n", (double)sensor.signalRateLimit());  // Read signal-rate limit, () → float MCPS
                                                            // 9.7 fixed point
    sensor.setSignalRateLimit(0.1f);                        // Set signal-rate limit, (limitMcps MCPS) → bool
                                                            // lower = longer range, more noise
    sensor.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange, 18);    // Set VCSEL period, (type, pclks) → bool
                                                            // pre-range 12/14/16/18; redoes phase calibration
    sensor.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange, 14);  // Set VCSEL period, (type, pclks) → bool
                                                            // final-range 8/10/12/14
    logPrintf("vcsel %u/%u PCLKs\n",
           sensor.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange),     // Read VCSEL period, (type) → uint8_t PCLKs
           sensor.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange));  // (reg + 1) × 2
    sensor.setProfile(VL53L0XFull::Profile::Default);       // Apply ranging profile, (profile) → bool
                                                            // 0.25 MCPS, 14/10 PCLKs, 33 ms

    float original = sensor.offset();                       // Read range offset, () → float mm
                                                            // NVM factory value, 0.25 mm steps
    sensor.setOffset(original - 5.0f);                      // Set range offset, (offsetMm mm) → bool
                                                            // volatile override, −512.0 to 511.75 mm
    logPrintf("offset %.2f mm\n", (double)sensor.offset());    // Read range offset, () → float mm
                                                            // 12-bit two's complement × 0.25
    sensor.setOffset(original);                             // Set range offset, (offsetMm mm) → bool
                                                            // restore the factory value
    sensor.setCrosstalkCompensation(0.0f);                  // Set crosstalk compensation, (rateMcps MCPS) → bool
                                                            // 0 = compensation off

    sensor.recalibrate();                                   // Rerun reference calibration, () → bool
                                                            // VHV + phase; needed after a > 8 °C change

    sensor.setInterruptThresholds(100, 800);                // Set distance thresholds, (lowMm mm, highMm mm) → bool
                                                            // 2 mm resolution
    uint16_t lo = 0, hi = 0;
    sensor.interruptThresholds(lo, hi);                     // Read distance thresholds, (lowMm&, highMm&) → void
                                                            // (low, high) in mm
    logPrintf("thresholds %u..%u mm\n", (unsigned)lo, (unsigned)hi);
    sensor.enableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);   // Select interrupt source, (source) → bool
                                                            // replaces the active source (mutually exclusive)
    sensor.disableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);  // Disable interrupt source, (source) → void
                                                            // only if it is the active one
    sensor.enableInterrupt(VL53L0XFull::SOURCE_NEW_SAMPLE_READY);  // Select interrupt source, (source) → bool
                                                            // back to the default data-ready source

    sensor.onInterrupt(onSample);                           // Subscribe to GPIO1, (callback, intPin=nullptr) → void
                                                            // status is read and cleared before the callback
    sensor.startContinuous(200);                            // Start continuous ranging, (periodMs=0 ms) → void
                                                            // timed mode feeds the subscription
    delay(1000);
    if (pending) logPrintf("interrupt, source %u\n", pendingStatus);
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // no more samples
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler
    logPrintf("pending %u\n", sensor.pollInterrupt());         // Read and clear status, () → uint8_t
                                                            // SOURCE_* value that fired, 0 = nothing pending

    sensor.setAddress(0x30);                                // Change I²C address, (address) → bool
                                                            // volatile; this driver instance is now unusable
    I2CConnection movedConnection(Wire, 0x30);
    VL53L0XFull moved(movedConnection);                     // Create VL53L0X Full driver, (connection)
                                                            // re-init at the new address is safe
    logPrintf("at 0x30 %u mm\n", (unsigned)moved.distance());  // Measure distance, () → uint16_t mm
                                                            // same sensor, new address
    moved.setAddress(VL53L0XMinimal::I2C_ADDRESS);          // Change I²C address, (address) → bool
                                                            // back to the power-on 0x29
}

void loop() {}
