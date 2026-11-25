'use strict';

// Demo: proximity-activated light meter — ranging, interrupts, ALS, interleaved mode, address change.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL6180XFull } = require('periph/src/chips/tof/vl6180x');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL6180XFull.I2C_ADDRESS, null, null, 2);
    const sensor = new VL6180XFull(connection);                                    // Create VL6180X Full driver, (connection)
    await sensor.init();                                                           // Run the init sequence, () → undefined

    // --- Identification ---
    console.log('model', (await sensor.modelId()).toString(16), 'revision', await sensor.revision());// Identification, () → number ; () → {major, minor}

    // --- Single-shot ranging at 5 Hz for 10 s ---
    for (let i = 0; i < 50; i++) {
        console.log((await sensor.readRange()) + ' mm');                           // Single-shot range, () → number mm
        await sleep(200);
    }

    // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
    await sensor.startContinuousRange(50);                                         // Start continuous ranging, (periodMs) → undefined
    for (let i = 0; i < 40; i++) {
        const { mm, error } = await sensor.readRangeStatus();                      // Range and error code, () → {mm, error}
        console.log(`${mm} mm (error ${error})`);
    }
    await sensor.stopContinuousRange();                                            // Stop continuous ranging, () → undefined
    await sleep(200);

    // --- Out-of-window interrupt for 30-80 mm ---
    await sensor.setRangeThresholds(30, 80);                                       // Range thresholds, (low, high) → undefined
    await sensor.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_OUT_OF_WINDOW);// Enable an interrupt, (source, mode) → undefined
    await sensor.startContinuousRange(50);
    for (let i = 0; i < 50; i++) {
        if ((await sensor.pollInterrupt()) & VL6180XFull.SOURCE_RANGE) console.log('range out of window');// Read and clear, () → number SOURCE_* mask
        await sleep(100);
    }
    await sensor.stopContinuousRange();
    await sleep(200);
    await sensor.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_NEW_SAMPLE_READY);

    // --- Light meter at gain 1 and 20 ---
    for (const code of [6, 0]) {
        await sensor.setAlsGain(code);                                             // ALS gain code, (code) → undefined
        console.log(`gain code ${code}: ${(await sensor.readLux()).toFixed(2)} lux`);// Single-shot ALS in lux, () → number lux
    }
    await sensor.setAlsGain(6);

    // --- Interleaved ALS + range for 5 s ---
    await sensor.setInterleaved(true);                                             // Interleaved ALS + range, (on) → undefined
    await sensor.startContinuousAls(100);                                          // Start continuous ALS, (periodMs) → undefined
    await sleep(5000);
    await sensor.stopContinuousAls();                                              // Stop continuous ALS, () → undefined
    await sensor.setInterleaved(false);

    // --- Calibration and diagnostics ---
    await sensor.setRangeOffset(0);                                                // Part-to-part offset, (mm) → undefined
    await sensor.setCrosstalkRate(0);                                              // Crosstalk rate, (rateMcps) → undefined
    await sensor.setAlsIntegrationTime(100);                                       // ALS integration time, (ms) → undefined
    await sensor.setAlsScaler(1);                                                  // ALS result scaler, (scaler) → undefined
    await sensor.recalibrateVhv();                                                 // Recalibrate VHV, () → undefined
    console.log('als raw', await sensor.readAlsRaw(), 'offset', await sensor.getRangeOffset());// Single-shot ALS count, () → number ; () → number mm
    console.log('history', await sensor.readHistory());                            // History buffer, () → number[8]

    // --- Change the device address and back (volatile) ---
    await sensor.setAddress(0x30);                                                 // Change address, (address) → undefined
    const moved = new VL6180XFull(new I2CConnection(bus, 0x30, null, null, 2));
    await moved.init();
    console.log('on 0x30: model', (await moved.modelId()).toString(16));
    await moved.setAddress(VL6180XFull.I2C_ADDRESS);

    await connection.close();
})();
