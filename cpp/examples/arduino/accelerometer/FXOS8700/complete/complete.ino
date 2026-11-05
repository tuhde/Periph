#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

static void sleepMs(uint32_t ms) { delay(ms); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    Serial.print(label);
    Serial.print(" x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.print(z, 3);
    Serial.print(" "); Serial.println(unit);
}
static void printNum(const char* label, float v, const char* unit) {
    Serial.print(label); Serial.print(" "); Serial.print(v, 2); Serial.print(" "); Serial.println(unit);
}
static void printInt(const char* label, long v) { Serial.print(label); Serial.print(" "); Serial.println(v); }
static void fxos_on_interrupt(uint8_t status) { printInt("INT", status); }

void setup() {
    Serial.begin(115200);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x1E);
    FXOS8700Full compass(connection);                       // Create FXOS8700 Full driver, (connection)
                                                            // verifies WHO_AM_I, resets and starts hybrid mode

    float ax, ay, az, mx, my, mz;
    compass.readAccel(ax, ay, az);                          // Read acceleration, (x, y, z) → g, g, g
                                                            // burst-reads 0x01-0x06, 14-bit left-justified
    compass.readMag(mx, my, mz);                            // Read magnetic field, (x, y, z) → µT, µT, µT
                                                            // burst-reads 0x33-0x38, 0.1 µT/LSB
    printVec("accel", ax, ay, az, "g");
    printVec("mag", mx, my, mz, "uT");
    compass.readHybrid(ax, ay, az, mx, my, mz);             // Read both sensors, (ax..az, mx..mz) → bool
                                                            // one 12-byte burst from 0x01 (hyb_autoinc_mode)
    int16_t rx, ry, rz;
    compass.readRawAccel(rx, ry, rz);                       // Read raw accelerometer, (x, y, z) → counts
                                                            // signed 14-bit counts
    printInt("raw accel x", rx);
    compass.readRawMag(rx, ry, rz);                         // Read raw magnetometer, (x, y, z) → counts
                                                            // signed 16-bit counts, 0.1 µT/LSB
    printInt("raw mag x", rx);
    float value = 0.0f;
    compass.readTemperature(value);                         // Read die temperature, (celsius) → bool
                                                            // int8(TEMP) × 0.96, relative only
    printNum("temperature", value, "C");
    compass.newAccelData();                                 // Check accelerometer data ready, () → bool
                                                            // STATUS.zyxdr
    compass.newMagData();                                   // Check magnetometer data ready, () → bool
                                                            // M_DR_STATUS.zyxdr
    compass.readStatus();                                   // Read STATUS, () → uint8_t
                                                            // raw STATUS byte
    compass.readMagStatus();                                // Read M_DR_STATUS, () → uint8_t
                                                            // raw M_DR_STATUS byte
    printInt("system mode", compass.readSystemMode());      // Read SYSMOD, () → uint8_t 0 standby | 1 wake | 2 sleep
                                                            // SYSMOD bits 1:0

    compass.setMode(FXOS8700Full::MODE_HYBRID);             // Select operating mode, (mode 0|1|2) → bool
                                                            // 0 accel only, 1 mag only, 2 hybrid
    compass.setRange(4);                                    // Set accelerometer range, (rangeG 2|4|8) → bool
                                                            // rewrites XYZ_DATA_CFG.fs and the cached sensitivity
    compass.setDataRate(200.0f);                            // Set system data rate, (rateHz 800..1.5625) → bool
                                                            // each sensor runs at half this rate in hybrid mode
    compass.setAccelOversampling(0, 0);                     // Set accelerometer oversampling, (mode 0..3, sleepMode=-1) → bool
                                                            // MODS / SMODS in CTRL_REG2
    compass.setMagOversampling(7, 7);                       // Set magnetometer oversampling, (ratio 0..7, sleepRatio=-1) → bool
                                                            // m_os / m_aslp_os
    compass.setLowNoise(false);                             // Set low-noise mode, (enabled) → bool
                                                            // lnoise limits the range to ±4 g
    compass.setResolution(14);                              // Set accelerometer resolution, (bits 14|8) → bool
                                                            // 8 selects fast read (MSB only)
    compass.setHighPassOutput(false, 0);                    // Set high-pass output, (enabled, cutoff=0) → bool
                                                            // hpf_out and sel
    compass.standby();                                      // Enter STANDBY, () → bool
                                                            // polls SYSMOD until 0 (up to 300 ms)
    compass.activate();                                     // Enter ACTIVE, () → void
                                                            // waits the 2/ODR + 2 ms turn-on time

    compass.setAccelOffset(0.0f, 0.0f, 0.0f);               // Set accelerometer offsets, (xG, yG, zG) → void
                                                            // OFF_X/Y/Z, 2 mg/LSB
    compass.getAccelOffset(ax, ay, az);                     // Get accelerometer offsets, (xG, yG, zG) → g, g, g
                                                            // signed 8-bit registers
    compass.setMagOffset(0.0f, 0.0f, 0.0f);                 // Set hard-iron offsets, (xUt, yUt, zUt) → void
                                                            // 15-bit values subtracted from the output
    compass.getMagOffset(mx, my, mz);                       // Get hard-iron offsets, (xUt, yUt, zUt) → µT, µT, µT
                                                            // M_OFF_X/Y/Z
    compass.setMagRaw(false);                               // Select raw magnetometer output, (raw) → void
                                                            // false applies the hard-iron offsets
    compass.setMagAutoCalibration(true);                    // Hardware hard-iron calibration, (enabled) → void
                                                            // MAX/MIN midpoint → M_OFF_* every ODR cycle
    sleepMs(1000);
    float mn[3], mxv[3];
    compass.readMagMinMax(mn, mxv);                         // Read tracked min / max, (minUt[3], maxUt[3]) → µT
                                                            // MIN_* / MAX_* registers
    compass.setMagAutoCalibration(false);
    compass.resetMagMinMax();                               // Reset min / max tracker, () → void
                                                            // m_maxmin_rst
    compass.setMagReset(0);                                 // Set automatic magnetic reset, (mode 0..3) → bool
                                                            // 0 every ODR cycle, 3 disabled
    compass.triggerMagReset();                              // Trigger one-shot degauss, () → void
                                                            // m_rst
    compass.measureMagOnce(mx, my, mz);                     // Measure once in STANDBY, (x, y, z) → bool
                                                            // m_ost, restores ACTIVE
    printNum("heading", compass.heading(2.5f), "deg");      // Compute tilt-compensated heading, (declinationDeg=0) → float degrees
                                                            // software compensation from one accel + one mag sample

    compass.setFifo(FXOS8700Full::FIFO_CIRCULAR, 16);       // Configure FIFO, (mode 0..3, watermark=0) → bool
                                                            // circular mode with a 16-sample watermark
    compass.setFifoTrigger(false, false, false, true);      // Select FIFO trigger sources, (transient, orientation, pulse, motion) → void
                                                            // TRIG_CFG
    compass.setFifoGate(false);                             // Set FIFO gate, (enabled) → void
                                                            // fifo_gate
    sleepMs(200);
    printInt("fifo level", compass.fifoLevel());            // Read FIFO level, () → uint8_t
                                                            // f_cnt, 0-32
    bool overflow, watermark;
    uint8_t count;
    compass.readFifoStatus(overflow, watermark, count);     // Read FIFO status, (overflow, watermark, count) → void
                                                            // decodes F_STATUS
    float samples[12];
    compass.readFifo(samples, 4);                           // Drain FIFO, (samples, maxSamples) → size_t
                                                            // one 6-byte transaction per sample
    compass.setFifo(FXOS8700Full::FIFO_DISABLED);

    compass.setFreefallMotion(true, 7, 0.3f, 60.0f, true, false); // Configure freefall / motion, (motion, axes=7, thresholdG=0.3, debounceMs=0, latch=false, debounceClear=false) → void
                                                                  // A_FFMT_CFG / THS / COUNT
    printInt("motion", compass.readMotionSource());         // Read A_FFMT_SRC, () → uint8_t
                                                            // clears the flags when latched
    compass.setTransient(7, 0.4f, 20.0f, false, false);     // Configure transient detector, (axes=7, thresholdG=0.3, debounceMs=0, bypassHpf=false, latch=false) → void
                                                            // TRANSIENT_CFG / THS / COUNT
    printInt("transient", compass.readTransientSource());   // Read TRANSIENT_SRC, () → uint8_t
                                                            // clears the interrupt
    compass.setPulse(0x04, 0x00);                           // Configure tap detection, (single, doubleTap, thresholdG=nullptr, timeLimitMs=30, latencyMs=100, windowMs=300, latch=false, doubleAbort=false, lpf=true, hpf=true) → void
                                                            // single tap on Z with default thresholds
    printInt("pulse", compass.readPulseSource());           // Read PULSE_SRC, () → uint8_t
                                                            // clears the interrupt
    compass.setOrientation(true, 100.0f);                   // Configure orientation detection, (enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=2) → bool
                                                            // PL_CFG / COUNT / THS / BF_ZCOMP
    bool changed, lockout, back;
    uint8_t pl;
    compass.readOrientation(changed, lockout, pl, back);    // Read orientation, (changed, lockout, portraitLandscape, back) → void
                                                            // clears newlp
    compass.setAutoSleep(true, 12.5f, 10.0f, FXOS8700Full::SOURCE_FF_MT); // Configure Auto-SLEEP, (enabled, sleepRateHz=50, timeoutS=10, wakeSources=0) → bool
                                                                          // sleeps after 10 s of inactivity, motion wakes

    compass.setInterruptPins(false, false);                 // Configure INT pins, (activeHigh=false, openDrain=false) → void
                                                            // ipol / pp_od
    compass.enableInterrupt(FXOS8700Full::SOURCE_FF_MT | FXOS8700Full::SOURCE_ORIENTATION, 1); // Enable and route interrupt, (source mask, pin 1|2) → bool
                                                                                               // CTRL_REG4 enable, CTRL_REG5 routing
    printInt("interrupts", compass.pollInterrupt());        // Poll and clear interrupt sources, () → uint8_t
                                                            // reads INT_SOURCE and the matching source registers
    compass.onInterrupt(fxos_on_interrupt, 1);              // Subscribe to INT, (callback, pin=1, intPin=nullptr) → void
                                                            // needs an InputPin on the connection
    compass.offInterrupt(1);                                // Unsubscribe from INT, (pin=1) → void
                                                            // removes the edge handler
    compass.disableInterrupt(FXOS8700Full::SOURCE_FF_MT | FXOS8700Full::SOURCE_ORIENTATION); // Disable interrupt sources, (source mask) → void
                                                                                             // clears int_en_*

    uint8_t reg[1] = { 0 };
    compass.readRegister(0x5F, reg, 1);                     // Read raw registers, (reg, buf, len) → void
                                                            // A_VECM_CFG, not wrapped by the API
    compass.writeRegister(0x5F, reg, 1);                    // Write raw registers, (reg, data, len) → void
                                                            // no STANDBY handling
    int16_t dx, dy, dz;
    bool ok = compass.selfTest(dx, dy, dz);                 // Run accelerometer self-test, (dx, dy, dz) → bool
                                                            // electrostatic test, checks X ≥ 192, Y ≥ 270, Z ≥ 1275
    printInt("self-test ok", ok);
    compass.reset();                                        // Software reset, () → void
                                                            // the NACK on the write is tolerated; call init() afterwards
    compass.init();                                         // Re-initialise, () → bool
                                                            // verifies WHO_AM_I and restarts hybrid mode
}

void loop() { delay(1000); }
