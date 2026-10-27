'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Full } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);                 // Open I2C bus for the accelerometer, (bus, addr=0x19)
const magConnection = new I2CConnection(I2C_BUS, 0x1E);                   // Open I2C bus for the magnetometer, (bus, addr=0x1E)
const lsm = new LSM303Full(accelConnection, magConnection);               // Create LSM303 Full driver, (accelConnection, magConnection)
                                                                          // runs init: ±2 g / 100 Hz / high-resolution accel, ±130 µT / 15 Hz / continuous mag

(async () => {
    await lsm.setRange(8);                                               // Set accelerometer full-scale range, (rangeG) → g
                                                                         // RMW CTRL_REG4_A FS bits; cached sensitivity becomes 4 mg/LSB
    await lsm.setDataRate(50);                                           // Set accelerometer output data rate, (rateHz) → Hz
                                                                         // RMW CTRL_REG1_A ODR bits; also scales duration encodings
    await lsm.setMode(2);                                                // Set operating mode, (mode) → None
                                                                         // 2 = high-resolution (12-bit output, LPen=0 HR=1)
    await lsm.setAxes(true, true, true);                                 // Set enabled axes, (x, y, z) → None
                                                                         // Xen/Yen/Zen in CTRL_REG1_A bits 2:0

    const raw = await lsm.readAccelerationRaw();                          // Read raw acceleration, () → [int, int, int] counts
                                                                         // signed counts at the current resolution (12-bit here)
    const ready = await lsm.newDataAvailable();                           // Check new data, () → bool
                                                                         // STATUS_REG_A.ZYXDA (bit 3)
    const status = await lsm.readStatus();                                // Read accelerometer status, () → int
                                                                         // full STATUS_REG_A byte: overrun + data-ready flags

    await lsm.setHighPass(0, 0, true, true, false, false);               // Configure high-pass filter, (mode=0, cutoff=0, filterOutput=false, onIa1=false, onIa2=false, onClick=false) → None
                                                                         // HPM=00, filtered data to output registers, enabled for generator 1
    const ref = await lsm.resetHighPass();                                // Reset high-pass filter, () → int
                                                                         // reads REFERENCE_A, which resets the filter in HPM=00
    await lsm.setReference(0x7F);                                         // Write high-pass reference, (value 0–255) → None
                                                                         // REFERENCE_A, used as the filter reference in HPM=01

    await lsm.setFifo(2, 16);                                            // Configure FIFO, (mode, watermark=0, triggerInt2=false) → None
                                                                         // stream mode, 16-sample watermark; sets FIFO_EN in CTRL_REG5_A
    const level = await lsm.fifoLevel();                                  // Read FIFO level, () → int samples
                                                                         // FSS<4:0> — number of unread sample sets (0–32)
    const fifoStatus = await lsm.readFifoStatus();                        // Read FIFO status, () → [watermark, overrun, empty, level]
                                                                         // decoded FIFO_SRC_REG_A flags and count
    const samples = await lsm.readFifo();                                 // Drain FIFO, (count=null) → [[number, number, number]] g
                                                                         // one 6-byte burst per sample set, converted to g

    await lsm.setMotionInterrupt(1, 0x2A, 1.0, 20, false);               // Configure interrupt generator, (generator, events, thresholdG, durationMs=0, andMode=false) → None
                                                                         // gen 1: Y/Z high + X low events, 1.0 g threshold, 20 ms, OR mode
    await lsm.setOrientation(2, false, true);                            // Configure 6D/4D orientation, (generator=1, position=false, fourD=false) → None
                                                                         // gen 2: 6D movement recognition with 4D detection enabled
    await lsm.setFreeFall(1, 0.35, 30);                                  // Configure free-fall detection, (generator=1, thresholdG=0.35, durationMs=30) → None
                                                                         // gen 1: all three axes low, AND mode (INT1_CFG_A = 0x95)
    await lsm.setLatch(1, true);                                         // Latch generator interrupt, (generator, enabled) → None
                                                                         // LIR_INT1: INT1 stays asserted until INT1_SOURCE_A is read
    const int1Src = await lsm.readInterruptSource(1);                     // Read interrupt source, (generator) → int
                                                                         // INT1_SOURCE_A byte; reading clears the interrupt
    await lsm.setClick(1, 4, 0.5, 10, 20, 50);                           // Configure click detection, (single=0, double=0, thresholdG=0.5, timeLimitMs=10, latencyMs=20, windowMs=50) → None
                                                                         // single on X, double on Z; threshold and the three time windows
    const clickSrc = await lsm.readClickSource();                         // Read click source, () → int
                                                                         // CLICK_SRC_A byte; reading clears the click interrupt
    await lsm.setInterruptPolarity(false);                               // Set INT polarity, (activeLow) → None
                                                                         // H_LACTIVE=0: INT1/INT2 active-high (default)

    await lsm.setMagRange(190);                                          // Set magnetometer full-scale range, (rangeUt) → µT
                                                                         // CRB_REG_M GN bits; cached gains become 855/760 LSB/gauss
    await lsm.setMagDataRate(30);                                        // Set magnetometer data rate, (rateHz) → Hz
                                                                         // RMW CRA_REG_M DO bits, preserving TEMP_EN
    await lsm.setMagMode(0);                                             // Set magnetometer mode, (mode) → None
                                                                         // 0 = continuous conversion (part resets into sleep)
    const magRaw = await lsm.readMagneticRaw();                           // Read raw magnetic counts, () → [int, int, int]
                                                                         // signed 12-bit counts, X/Z/Y wire order reordered to X/Y/Z
    const magReady = await lsm.magDataReady();                            // Check magnetometer data ready, () → bool
                                                                         // SR_REG_M.DRDY (bit 0)
    const magStatus = await lsm.readMagStatus();                          // Read magnetometer status, () → [drdy, lock]
                                                                         // decoded SR_REG_M: DRDY flag and output-lock flag
    const identity = await lsm.readMagIdentity();                         // Read magnetometer identity, () → Buffer
                                                                         // IRA/IRB/IRC = 0x48 0x34 0x33 (same as HMC5883L)
    await lsm.enableTemperature(true);                                   // Enable temperature sensor, (enabled) → None
                                                                         // TEMP_EN in CRA_REG_M; updates at the magnetometer data rate
    const tempDelta = await lsm.readTemperatureDelta();                   // Read relative temperature, () → float °C
                                                                         // 8 LSB/°C, relative to an unspecified reference
    const headingTilt = await lsm.heading();                              // Read compass heading, (tiltCompensate=true) → float degrees
                                                                         // tilt-compensated atan2(y, x) from both sensors, wrapped 0–360
    const headingFlat = await lsm.heading(false);                         // Read compass heading, (tiltCompensate=true) → float degrees
                                                                         // flat compass from the magnetic X/Y components only

    await lsm.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);               // Route interrupt source, (source, pin=1) → None
                                                                         // click events to INT1 (CTRL_REG3_A I1_CLICK)
    await lsm.enableInterrupt(LSM303Full.SOURCE_IA1, 1);                 // Route interrupt source, (source, pin=1) → None
                                                                         // generator-1 events to INT1 (CTRL_REG3_A I1_AOI1)
    await lsm.enableInterrupt(LSM303Full.SOURCE_WATERMARK, 2);           // Route interrupt source, (source, pin=1) → None
                                                                         // FIFO watermark is INT1-only (I1_WTM); pin argument ignored
    await lsm.disableInterrupt(LSM303Full.SOURCE_WATERMARK, 2);          // Unroute interrupt source, (source, pin=1) → None
                                                                         // removes the source again
    const intStatus = await lsm.pollInterrupt();                          // Poll interrupt sources, () → int
                                                                         // reads the source registers of every enabled source; clears latched flags
    await lsm.onInterrupt(s => { void s; }, 1);                          // Subscribe to INT pin, (callback, pin=1) → None
                                                                         // uses the connection's intPin, or a 5 ms polling interval
    await lsm.offInterrupt(1);                                           // Unsubscribe from INT pin, (pin=1) → None
                                                                         // stops edge delivery / polling when no callback remains

    await lsm.powerDown();                                               // Enter power-down, () → None
                                                                         // ODR=0000; register contents are retained
    await lsm.setDataRate(100);                                          // Set accelerometer output data rate, (rateHz) → Hz
                                                                         // leaves power-down again at 100 Hz

    await lsm.reboot();                                                  // Reboot memory content, () → None
                                                                         // BOOT=1 reloads factory trim; re-run init (fresh driver) afterwards

    console.log(`raw=${JSON.stringify(raw)} ready=${ready} status=0x${status.toString(16)} ref=0x${ref.toString(16)} ` +
        `level=${level} fifoStatus=${JSON.stringify(fifoStatus)} samples=${samples.length} ` +
        `int1Src=0x${int1Src.toString(16)} clickSrc=0x${clickSrc.toString(16)} ` +
        `magRaw=${JSON.stringify(magRaw)} magReady=${magReady} magStatus=${JSON.stringify(magStatus)} ` +
        `identity=${identity.toString('hex').toUpperCase()} tempDelta=${tempDelta.toFixed(2)} C ` +
        `headingTilt=${headingTilt.toFixed(1)} headingFlat=${headingFlat.toFixed(1)} intStatus=0x${intStatus.toString(16)}`);
    await accelConnection.close();
    await magConnection.close();
})().catch(err => { console.error(err); process.exit(1); });
