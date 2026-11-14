'use strict';

// Complete example: every method of the VL6180X Full API.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL6180XFull } = require('periph/src/chips/tof/vl6180x');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL6180XFull.I2C_ADDRESS, null, null, 2);
    const sensor = new VL6180XFull(connection);                                    // Create VL6180X Full driver, (connection)
    await sensor.init();                                                           // Run the init sequence, () → undefined
                                                                                   // private settings only when fresh out of reset

    console.log(await sensor.readRange());                                         // Single-shot range, () → number mm
                                                                                   // rejects on a non-zero range error code
    console.log(await sensor.readRangeStatus());                                   // Range and error code, () → {mm, error}
                                                                                   // never rejects on a range error; 0 = valid
    console.log(await sensor.readAlsRaw());                                        // Single-shot ALS count, () → number
    console.log(await sensor.readLux());                                           // Single-shot ALS in lux, () → number lux
                                                                                   // 0.32 lux/count at gain 1, 100 ms

    await sensor.startContinuousRange(50);                                         // Start continuous ranging, (periodMs) → undefined
                                                                                   // 10-2550 ms in 10 ms steps
    console.log(await sensor.readRangeContinuous());                               // Wait for next sample, () → number mm
    await sensor.stopContinuousRange();                                            // Stop continuous ranging, () → undefined
    await sensor.startContinuousAls(100);                                          // Start continuous ALS, (periodMs) → undefined
    await sensor.stopContinuousAls();                                              // Stop continuous ALS, () → undefined
    await sensor.setInterleaved(false);                                            // Interleaved ALS + range, (on) → undefined

    await sensor.setAlsGain(6);                                                    // ALS gain code, (code) → undefined
                                                                                   // 0 = 20 ... 6 = 1, 7 = 40
    await sensor.setAlsIntegrationTime(100);                                       // ALS integration time, (ms) → undefined
                                                                                   // 1-512 ms
    await sensor.setAlsScaler(1);                                                  // ALS result scaler, (scaler) → undefined
    await sensor.setRangeThresholds(30, 80);                                       // Range thresholds, (low, high) → undefined
    await sensor.setAlsThresholds(100, 5000);                                      // ALS thresholds, (low, high) → undefined
    await sensor.setMaxConvergenceTime(49);                                        // Max convergence time, (ms) → undefined
    await sensor.setAveragingPeriod(48);                                           // Averaging period, (units) → undefined
    await sensor.setRangeOffset(0);                                                // Part-to-part offset, (mm) → undefined
    console.log(await sensor.getRangeOffset());                                    // Read the offset, () → number mm
    await sensor.setCrosstalkRate(0);                                              // Crosstalk rate, (rateMcps) → undefined
    await sensor.recalibrateVhv();                                                 // Recalibrate VHV, () → undefined

    console.log((await sensor.modelId()).toString(16), await sensor.revision());   // Identification, () → number ; () → {major, minor}
                                                                                   // model ID always 0xB4
    console.log(await sensor.readHistory());                                       // History buffer, () → number[8]

    await sensor.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_OUT_OF_WINDOW);// Enable an interrupt, (source, mode) → undefined
    console.log(await sensor.pollInterrupt());                                     // Read and clear, () → number SOURCE_* mask
    await sensor.onInterrupt((status) => console.log('irq', status));              // Subscribe, (callback) → undefined
                                                                                   // falling edge on connection.intPin, else a 5 ms polling timer
    await sleep(100);                                                              // Wait
    await sensor.offInterrupt();                                                   // Unsubscribe, () → undefined
    await sensor.disableInterrupt(VL6180XFull.SOURCE_RANGE);                       // Disable an interrupt, (source) → undefined
    await sensor.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_NEW_SAMPLE_READY);// Restore the default mode, (source, mode) → undefined
    // await sensor.setAddress(0x30);                                              // Change address (volatile), (address) → undefined

    await connection.close();
})();
