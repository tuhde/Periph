'use strict';

// Exercises every method in the VL53L1X Full API, and finally moves the
// sensor to another I²C address and back to 0x29.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL53L1XFull } = require('periph/src/chips/tof/vl53l1x');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL53L1XFull.I2C_ADDRESS);
    const sensor = new VL53L1XFull(connection);                                    // Create VL53L1X Full driver, (connection)
    await sensor.init();                                                           // Wait for init sequence, () → None
                                                                                   // boot poll, ID check, ULD default config

    console.log('model', (await sensor.modelId()).toString(16));                   // Read model ID, () → number
                                                                                   // IDENTIFICATION__MODEL_ID, always 0xEA
    console.log('module', (await sensor.moduleType()).toString(16));               // Read module type, () → number
                                                                                   // IDENTIFICATION__MODULE_TYPE, always 0xCC
    console.log('revision', (await sensor.revisionId()).toString(16));             // Read revision ID, () → number
                                                                                   // mask revision, 0x10

    const d = await sensor.distance();                                             // Measure distance, () → number mm
                                                                                   // single shot; blocks for about one timing budget
    console.log('distance', d, 'mm, valid', await sensor.rangeValid());            // Check last measurement, () → boolean
                                                                                   // mapped range status === 0
    console.log('range status', await sensor.rangeStatus());                       // Read last range status, () → number
                                                                                   // 0 = valid, 2 = signal fail, 4 = out of bounds
    const m = await sensor.readMeasurement();                                      // Read result block, () → {distanceMm, …}
                                                                                   // distance, status, signal/ambient MCPS, SPAD count
    console.log(`signal ${m.signalRateMcps.toFixed(2)} MCPS, ambient ${m.ambientRateMcps.toFixed(2)} MCPS, ` +
        `${m.effectiveSpadCount.toFixed(1)} SPADs`);

    console.log('mode', await sensor.distanceMode());                              // Read distance mode, () → string
                                                                                   // from PHASECAL_CONFIG__TIMEOUT_MACROP
    await sensor.setDistanceMode('short');                                         // Set distance mode, (mode) → None
                                                                                   // ~1.3 m, robust in sunlight; keeps the budget
    console.log('budget', await sensor.timingBudget(), 'us');                      // Read timing budget, () → number µs
                                                                                   // decoded from the range timeout A register
    await sensor.setTimingBudget(33000);                                           // Set timing budget, (budgetUs µs) → None
                                                                                   // ULD table values 15000 (short only) … 500000
    console.log('short mode', await sensor.distance(), 'mm');                      // Measure distance, () → number mm
                                                                                   // 33 ms single shot
    await sensor.setDistanceMode('long');                                          // Set distance mode, (mode) → None
                                                                                   // back to up to 4 m in the dark
    await sensor.setTimingBudget(100000);                                          // Set timing budget, (budgetUs µs) → None
                                                                                   // default 100 ms

    await sensor.setInterMeasurement(200);                                         // Set inter-measurement period, (periodMs ms) → None
                                                                                   // must be ≥ the timing budget
    console.log('period', await sensor.interMeasurement(), 'ms');                  // Read inter-measurement period, () → number ms
                                                                                   // oscillator ticks scaled by the PLL calibration
    await sensor.startContinuous(200);                                             // Start continuous ranging, (periodMs=0 ms) → None
                                                                                   // timed mode; 0 = as fast as the budget allows
    for (let i = 0; i < 5; i++) {
        console.log('continuous', await sensor.readContinuous(), 'mm');            // Read next continuous result, () → number mm
                                                                                   // waits for data ready, then clears it
    }
    while (!(await sensor.dataReady())) await sleep(10);                           // Check for a result, () → boolean
                                                                                   // GPIO1 line asserted
    console.log('record', (await sensor.readMeasurement()).distanceMm, 'mm');      // Read result block, () → {distanceMm, …}
                                                                                   // non-blocking; clears the interrupt
    await sensor.stopContinuous();                                                 // Stop continuous ranging, () → None
                                                                                   // does not wait for a running measurement
    await sleep(250);

    console.log('signal limit', await sensor.signalRateLimit());                   // Read signal-rate limit, () → number MCPS
                                                                                   // 9.7 fixed point, default 1.0
    await sensor.setSignalRateLimit(0.5);                                          // Set signal-rate limit, (limitMcps MCPS) → None
                                                                                   // lower = longer range, more noise
    await sensor.setSignalRateLimit(1.0);                                          // Set signal-rate limit, (limitMcps MCPS) → None
                                                                                   // restore the default
    console.log('sigma', await sensor.sigmaThreshold(), 'mm');                     // Read sigma threshold, () → number mm
                                                                                   // 14.2 fixed point, default 90
    await sensor.setSigmaThreshold(60);                                            // Set sigma threshold, (sigmaMm mm) → None
                                                                                   // stricter repeatability filter
    await sensor.setSigmaThreshold(90);                                            // Set sigma threshold, (sigmaMm mm) → None
                                                                                   // restore the default

    const centre = await sensor.opticalCenter();                                   // Read optical-centre SPAD, () → number
                                                                                   // factory NVM value for this part's lens
    console.log('optical centre', centre);
    await sensor.setRoi(8, 8);                                                     // Set ROI size, (width SPADs, height SPADs) → None
                                                                                   // 4–16 each; narrows the field of view
    await sensor.setRoiCenter(centre);                                             // Set ROI centre, (spad) → None
                                                                                   // align the narrow ROI with the lens
    console.log('roi', await sensor.roi(), 'centre', await sensor.roiCenter());    // Read ROI size, () → {width, height}; Read ROI centre, () → number
                                                                                   // in SPADs
    await sensor.setRoi(16, 16);                                                   // Set ROI size, (width SPADs, height SPADs) → None
                                                                                   // full array; re-centres on SPAD 199

    const original = await sensor.offset();                                        // Read range offset, () → number mm
                                                                                   // NVM factory value, 0.25 mm steps
    await sensor.setOffset(original - 5.0);                                        // Set range offset, (offsetMm mm) → None
                                                                                   // volatile override, −1024.0 to 1023.75 mm
    console.log('offset', await sensor.offset(), 'mm');                            // Read range offset, () → number mm
                                                                                   // 13-bit two's complement × 0.25
    await sensor.setCrosstalkCompensation(0.01);                                   // Set crosstalk compensation, (rateMcps MCPS) → None
                                                                                   // per-SPAD rate, 7.9 kcps register
    console.log('crosstalk', await sensor.crosstalkCompensation(), 'MCPS');        // Read crosstalk compensation, () → number MCPS
                                                                                   // 0 = off
    console.log('calibrated offset', await sensor.calibrateOffset(140), 'mm');     // Calibrate offset, (targetMm mm) → number mm
                                                                                   // 50 samples against a target at 140 mm; applies it
    console.log('calibrated crosstalk', await sensor.calibrateCrosstalk(600));     // Calibrate crosstalk, (targetMm mm) → number MCPS
                                                                                   // 50 samples against a target at 600 mm; applies it
    await sensor.setOffset(original);                                              // Set range offset, (offsetMm mm) → None
                                                                                   // restore the factory value
    await sensor.setCrosstalkCompensation(0);                                      // Set crosstalk compensation, (rateMcps MCPS) → None
                                                                                   // compensation off

    await sensor.recalibrate();                                                    // Run temperature update, () → None
                                                                                   // full VHV; after a > 8 °C change, not while ranging

    await sensor.setInterruptThresholds(100, 800);                                 // Set distance thresholds, (lowMm mm, highMm mm) → None
                                                                                   // 1 mm resolution
    console.log('thresholds', await sensor.interruptThresholds());                 // Read distance thresholds, () → {lowMm, highMm}
                                                                                   // in mm
    await sensor.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW);                    // Select interrupt source, (source) → None
                                                                                   // fires while 100 mm ≤ range ≤ 800 mm
    await sensor.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW);                   // Disable interrupt source, (source) → None
                                                                                   // reverts to new-sample-ready (no disabled state)
    await sensor.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY);             // Select interrupt source, (source) → None
                                                                                   // the default data-ready source

    await sensor.onInterrupt((status) => console.log('interrupt, source', status));  // Subscribe to GPIO1, (callback) → None
                                                                                   // interrupt is cleared before the callback
    await sensor.startContinuous(200);                                             // Start continuous ranging, (periodMs=0 ms) → None
                                                                                   // timed mode feeds the subscription
    await sleep(1000);
    await sensor.stopContinuous();                                                 // Stop continuous ranging, () → None
                                                                                   // no more samples
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
                                                                                   // detaches the pin handler or stops the polling timer
    console.log('pending', await sensor.pollInterrupt());                          // Read and clear interrupt, () → number
                                                                                   // active SOURCE_* value, 0 = nothing pending

    await sensor.setAddress(0x30);                                                 // Change I²C address, (address) → None
                                                                                   // volatile; this driver instance is now unusable
    await connection.close();
    const movedConnection = new I2CConnection(bus, 0x30);
    const moved = new VL53L1XFull(movedConnection);                                // Create VL53L1X Full driver, (connection)
    await moved.init();                                                            // Wait for init sequence, () → None
                                                                                   // re-init at the new address is safe
    console.log('at 0x30', await moved.distance(), 'mm');                          // Measure distance, () → number mm
                                                                                   // same sensor, new address
    await moved.setAddress(VL53L1XFull.I2C_ADDRESS);                               // Change I²C address, (address) → None
                                                                                   // back to the power-on 0x29
    await movedConnection.close();
})();
