'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { FXOS8700Full, FXOS8700Source } = require('periph/src/chips/accelerometer/fxos8700');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const connection = new I2CConnection(I2C_BUS, 0x1E);          // Open I²C bus, (busNumber, addr=0x1E) → I2CConnection
const compass = new FXOS8700Full(connection);                 // Create FXOS8700 Full driver, (connection) → FXOS8700Full
                                                              // verifies WHO_AM_I, resets and starts hybrid mode
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    await compass.init();                                     // Wait for initialization, () → Promise<void>
                                                              // resolves when the init sequence has finished

    console.log('accel', await compass.readAccel());          // Read acceleration, () → Promise<{x, y, z}> g
                                                              // burst-reads 0x01-0x06, 14-bit left-justified
    console.log('mag', await compass.readMag());              // Read magnetic field, () → Promise<{x, y, z}> uT
                                                              // burst-reads 0x33-0x38, 0.1 uT/LSB
    console.log('hybrid', await compass.readHybrid());        // Read both sensors, () → Promise<{accel, mag}>
                                                              // one 12-byte burst from 0x01 (hyb_autoinc_mode)
    console.log('raw accel', await compass.readRawAccel());   // Read raw accelerometer, () → Promise<{x, y, z}> counts
    console.log('raw mag', await compass.readRawMag());       // Read raw magnetometer, () → Promise<{x, y, z}> counts
    console.log('temperature', await compass.readTemperature());  // Read die temperature, () → Promise<float> °C
                                                              // int8(TEMP) × 0.96, relative only
    console.log('new accel', await compass.newAccelData());   // Check accelerometer data ready, () → Promise<boolean>
    console.log('new mag', await compass.newMagData());       // Check magnetometer data ready, () → Promise<boolean>
    console.log('status', await compass.readStatus());        // Read STATUS, () → Promise<int>
    console.log('mag status', await compass.readMagStatus()); // Read M_DR_STATUS, () → Promise<int>
    console.log('system mode', await compass.readSystemMode());  // Read SYSMOD, () → Promise<int> 0 standby | 1 wake | 2 sleep

    await compass.setMode(FXOS8700Full.MODE_HYBRID);          // Select operating mode, (mode 0|1|2) → Promise<void>
                                                              // 0 accel only, 1 mag only, 2 hybrid
    await compass.setRange(4);                                // Set accelerometer range, (rangeG 2|4|8) → Promise<void>
                                                              // rewrites XYZ_DATA_CFG.fs and the cached sensitivity
    await compass.setDataRate(200);                           // Set system data rate, (rateHz 800..1.5625) → Promise<void>
                                                              // each sensor runs at half this rate in hybrid mode
    await compass.setAccelOversampling(0, 0);                 // Set accelerometer oversampling, (mode 0..3, sleepMode=null) → Promise<void>
                                                              // MODS / SMODS in CTRL_REG2
    await compass.setMagOversampling(7, 7);                   // Set magnetometer oversampling, (ratio 0..7, sleepRatio=null) → Promise<void>
                                                              // m_os / m_aslp_os
    await compass.setLowNoise(false);                         // Set low-noise mode, (enabled boolean) → Promise<void>
                                                              // lnoise limits the range to ±4 g
    await compass.setResolution(14);                          // Set accelerometer resolution, (bits 14|8) → Promise<void>
                                                              // 8 selects fast read (MSB only)
    await compass.setHighPassOutput(false, 0);                // Set high-pass output, (enabled boolean, cutoff=0) → Promise<void>
                                                              // hpf_out and sel
    await compass.standby();                                  // Enter STANDBY, () → Promise<void>
                                                              // polls SYSMOD until 0 (up to 300 ms)
    await compass.activate();                                 // Enter ACTIVE, () → Promise<void>
                                                              // waits the 2/ODR + 2 ms turn-on time

    await compass.setAccelOffset(0, 0, 0);                    // Set accelerometer offsets, (xG, yG, zG) → Promise<void>
                                                              // OFF_X/Y/Z, 2 mg/LSB
    console.log('accel offset', await compass.getAccelOffset());  // Get accelerometer offsets, () → Promise<{x, y, z}> g
    await compass.setMagOffset(0, 0, 0);                      // Set hard-iron offsets, (xUt, yUt, zUt) → Promise<void>
                                                              // 15-bit values subtracted from the output
    console.log('mag offset', await compass.getMagOffset());  // Get hard-iron offsets, () → Promise<{x, y, z}> uT
    await compass.setMagRaw(false);                           // Select raw magnetometer output, (raw boolean) → Promise<void>
                                                              // false applies the hard-iron offsets
    await compass.setMagAutoCalibration(true);                // Hardware hard-iron calibration, (enabled boolean) → Promise<void>
                                                              // MAX/MIN midpoint → M_OFF_* every ODR cycle
    await sleep(1000);
    console.log('min/max', await compass.readMagMinMax());    // Read tracked min / max, () → Promise<{min, max}> uT
    await compass.setMagAutoCalibration(false);
    await compass.resetMagMinMax();                           // Reset min / max tracker, () → Promise<void>
    await compass.setMagReset(0);                             // Set automatic magnetic reset, (mode 0..3) → Promise<void>
                                                              // 0 every ODR cycle, 3 disabled
    await compass.triggerMagReset();                          // Trigger one-shot degauss, () → Promise<void>
    console.log('one-shot', await compass.measureMagOnce());  // Measure once in STANDBY, () → Promise<{x, y, z}> uT
                                                              // m_ost, restores ACTIVE
    console.log('heading', await compass.heading(2.5));       // Compute tilt-compensated heading, (declinationDeg=0) → Promise<float> degrees
                                                              // software compensation from one accel + one mag sample

    await compass.setFifo(FXOS8700Full.FIFO_CIRCULAR, 16);    // Configure FIFO, (mode 0..3, watermark=0) → Promise<void>
    await compass.setFifoTrigger({ motion: true });           // Select FIFO trigger sources, ({transient, orientation, pulse, motion}) → Promise<void>
    await compass.setFifoGate(false);                         // Set FIFO gate, (enabled boolean) → Promise<void>
    await sleep(200);
    console.log('fifo level', await compass.fifoLevel());     // Read FIFO level, () → Promise<int>
    console.log('fifo status', await compass.readFifoStatus());  // Read FIFO status, () → Promise<{overflow, watermark, count}>
    console.log('fifo', await compass.readFifo(4));           // Drain FIFO, (count=null) → Promise<[{x, y, z}]> g
    await compass.setFifo(FXOS8700Full.FIFO_DISABLED);

    await compass.setFreefallMotion(true, { thresholdG: 0.3, debounceMs: 60, latch: true });  // Configure freefall / motion, (motion, {axes=7, thresholdG=0.3, debounceMs=0, latch=false, debounceClear=false}) → Promise<void>
    console.log('motion', await compass.readMotionSource()); // Read A_FFMT_SRC, () → Promise<int>
    await compass.setTransient({ thresholdG: 0.4, debounceMs: 20 });  // Configure transient detector, ({axes=7, thresholdG=0.3, debounceMs=0, bypassHpf=false, latch=false}) → Promise<void>
    console.log('transient', await compass.readTransientSource());  // Read TRANSIENT_SRC, () → Promise<int>
    await compass.setPulse({ single: 0x04 });                 // Configure tap detection, ({single=0, double=0, thresholdG=[0.5,0.5,0.5], timeLimitMs=30, latencyMs=100, windowMs=300, latch=false, doubleAbort=false, lpf=true, hpf=true}) → Promise<void>
    console.log('pulse', await compass.readPulseSource());    // Read PULSE_SRC, () → Promise<int>
    await compass.setOrientation({ enabled: true, debounceMs: 100 });  // Configure orientation detection, ({enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=2}) → Promise<void>
    console.log('orientation', await compass.readOrientation());  // Read orientation, () → Promise<{changed, lockout, portraitLandscape, back}>
    await compass.setAutoSleep(true, { sleepRateHz: 12.5, timeoutS: 10, wakeSources: FXOS8700Source.SOURCE_FF_MT });  // Configure Auto-SLEEP, (enabled, {sleepRateHz=50, timeoutS=10, wakeSources=0}) → Promise<void>

    await compass.setInterruptPins(false, false);             // Configure INT pins, (activeHigh=false, openDrain=false) → Promise<void>
    await compass.enableInterrupt(FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_ORIENTATION, 1);  // Enable and route interrupt, (source mask, pin 1|2) → Promise<void>
    console.log('interrupts', await compass.pollInterrupt()); // Poll and clear interrupt sources, () → Promise<int>
    await compass.onInterrupt((status) => console.log('INT', status), 1);  // Subscribe to INT, (callback, pin=1) → Promise<void>
    await sleep(1000);
    await compass.offInterrupt(1);                            // Unsubscribe from INT, (pin=1) → Promise<void>
    await compass.disableInterrupt(FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_ORIENTATION);  // Disable interrupt sources, (source mask) → Promise<void>

    console.log('A_VECM_CFG', await compass.readRegister(0x5F, 1));  // Read raw registers, (reg, length=1) → Promise<Buffer>
    await compass.writeRegister(0x5F, Buffer.from([0x00]));   // Write raw registers, (reg, data) → Promise<void>
    console.log('self-test', await compass.selfTest());       // Run accelerometer self-test, () → Promise<{ok, dx, dy, dz}> counts
                                                              // electrostatic test, checks X ≥ 192, Y ≥ 270, Z ≥ 1275
    await compass.reset();                                    // Software reset, () → Promise<void>
                                                              // the NACK on the write is tolerated
    await compass.reinit();                                   // Re-initialise, () → Promise<void>
    await connection.close();
})();
