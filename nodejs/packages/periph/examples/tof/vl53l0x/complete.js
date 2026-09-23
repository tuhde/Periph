'use strict';

// Exercises every method in the VL53L0X Full API, and finally moves the
// sensor to another I²C address and back to 0x29.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL53L0XFull } = require('periph/src/chips/tof/vl53l0x');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL53L0XFull.I2C_ADDRESS);
    const sensor = new VL53L0XFull(connection);                                    // Create VL53L0X Full driver, (connection)
    await sensor.init();                                                           // Wait for init sequence, () → None
                                                                                   // ID check, tuning, SPADs, VHV + phase calibration

    console.log('model', (await sensor.modelId()).toString(16));                   // Read model ID, () → number
                                                                                   // IDENTIFICATION_MODEL_ID, always 0xEE
    console.log('revision', (await sensor.revisionId()).toString(16));             // Read revision ID, () → number
                                                                                   // IDENTIFICATION_REVISION_ID, 0x10 on current silicon

    const d = await sensor.distance();                                             // Measure distance, () → number mm
                                                                                   // single shot; blocks for about one timing budget
    console.log('distance', d, 'mm, valid', await sensor.rangeValid());            // Check last measurement, () → boolean
                                                                                   // device range status == 11 (range complete)
    console.log('range status', await sensor.rangeStatus());                       // Read last range status, () → number 0–15
                                                                                   // 11 = valid, 4 = no target
    const m = await sensor.readMeasurement();                                      // Read result block, () → {distanceMm, rangeStatus, signalRateMcps, ambientRateMcps, effectiveSpadCount}
                                                                                   // distance, status, signal/ambient MCPS, SPAD count
    console.log(`signal ${m.signalRateMcps.toFixed(2)} MCPS, ambient ${m.ambientRateMcps.toFixed(2)} MCPS`);

    await sensor.startContinuous();                                                // Start continuous ranging, (periodMs=0 ms) → None
                                                                                   // 0 = back-to-back measurements
    for (let i = 0; i < 5; i++) {
        console.log('continuous', await sensor.readContinuous(), 'mm');            // Read next continuous result, () → number mm
                                                                                   // waits for a fresh data-ready, then clears it
    }
    await sensor.stopContinuous();                                                 // Stop continuous ranging, () → None
                                                                                   // does not wait for a running measurement

    await sensor.startContinuous(100);                                             // Start continuous ranging, (periodMs=0 ms) → None
                                                                                   // timed mode: one measurement every 100 ms
    while (!(await sensor.dataReady())) {                                          // Check for a result, () → boolean
        await sleep(10);                                                           // RESULT_INTERRUPT_STATUS bits 2:0 non-zero
    }
    console.log('timed', (await sensor.readMeasurement()).distanceMm, 'mm');       // Read result block, () → {distanceMm, …}
                                                                                   // non-blocking; clears the interrupt
    await sensor.stopContinuous();                                                 // Stop continuous ranging, () → None
                                                                                   // back to software standby

    console.log('budget', await sensor.timingBudget(), 'us');                      // Read timing budget, () → number µs
                                                                                   // computed from the sequence-step timeouts
    await sensor.setTimingBudget(50000);                                           // Set timing budget, (budgetUs µs) → None
                                                                                   // longer budget = lower noise, ≥ 20000 µs
    console.log('signal limit', await sensor.signalRateLimit(), 'MCPS');           // Read signal-rate limit, () → number MCPS
                                                                                   // 9.7 fixed point
    await sensor.setSignalRateLimit(0.1);                                          // Set signal-rate limit, (limitMcps MCPS) → None
                                                                                   // lower = longer range, more noise
    await sensor.setVcselPulsePeriod('pre_range', 18);                             // Set VCSEL period, (periodType, pclks) → None
                                                                                   // pre-range 12/14/16/18; redoes phase calibration
    await sensor.setVcselPulsePeriod('final_range', 14);                           // Set VCSEL period, (periodType, pclks) → None
                                                                                   // final-range 8/10/12/14
    console.log('vcsel', await sensor.vcselPulsePeriod('pre_range'),              // Read VCSEL period, (periodType) → number PCLKs
        await sensor.vcselPulsePeriod('final_range'));                             // (reg + 1) × 2
    await sensor.setProfile('default');                                            // Apply ranging profile, (profile) → None
                                                                                   // 0.25 MCPS, 14/10 PCLKs, 33 ms

    const original = await sensor.offset();                                        // Read range offset, () → number mm
                                                                                   // NVM factory value, 0.25 mm steps
    await sensor.setOffset(original - 5);                                          // Set range offset, (offsetMm mm) → None
                                                                                   // volatile override, −512.0 to 511.75 mm
    console.log('offset', await sensor.offset(), 'mm');                            // Read range offset, () → number mm
                                                                                   // 12-bit two's complement × 0.25
    await sensor.setOffset(original);                                              // Set range offset, (offsetMm mm) → None
                                                                                   // restore the factory value
    await sensor.setCrosstalkCompensation(0);                                      // Set crosstalk compensation, (rateMcps MCPS) → None
                                                                                   // 0 = compensation off

    await sensor.recalibrate();                                                    // Rerun reference calibration, () → None
                                                                                   // VHV + phase; needed after a > 8 °C change

    await sensor.setInterruptThresholds(100, 800);                                 // Set distance thresholds, (lowMm mm, highMm mm) → None
                                                                                   // 2 mm resolution
    console.log('thresholds', await sensor.interruptThresholds());                 // Read distance thresholds, () → {lowMm, highMm} mm
                                                                                   // decoded from SYSTEM_THRESH_LOW/HIGH
    await sensor.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);                // Select interrupt source, (source) → None
                                                                                   // replaces the active source (mutually exclusive)
    await sensor.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);               // Disable interrupt source, (source) → None
                                                                                   // only if it is the active one
    await sensor.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY);             // Select interrupt source, (source) → None
                                                                                   // back to the default data-ready source

    await sensor.onInterrupt((status) => console.log('interrupt, source', status)); // Subscribe to GPIO1, (callback) → None
                                                                                   // status is read and cleared before the callback
    await sensor.startContinuous(200);                                             // Start continuous ranging, (periodMs=0 ms) → None
                                                                                   // timed mode feeds the subscription
    await sleep(1000);
    await sensor.stopContinuous();                                                 // Stop continuous ranging, () → None
                                                                                   // no more samples
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
                                                                                   // detaches the pin handler or stops the polling timer
    console.log('pending', await sensor.pollInterrupt());                          // Read and clear status, () → number
                                                                                   // SOURCE_* value that fired, 0 = nothing pending

    await sensor.setAddress(0x30);                                                 // Change I²C address, (address) → None
                                                                                   // volatile; this driver instance is now unusable
    await connection.close();
    const movedConnection = new I2CConnection(bus, 0x30);
    const moved = new VL53L0XFull(movedConnection);                                // Create VL53L0X Full driver, (connection)
    await moved.init();                                                            // Wait for init sequence, () → None
                                                                                   // re-init at the new address is safe
    console.log('at 0x30', await moved.distance(), 'mm');                          // Measure distance, () → number mm
                                                                                   // same sensor, new address
    await moved.setAddress(VL53L0XFull.I2C_ADDRESS);                               // Change I²C address, (address) → None
                                                                                   // back to the power-on 0x29
    await movedConnection.close();
})();
