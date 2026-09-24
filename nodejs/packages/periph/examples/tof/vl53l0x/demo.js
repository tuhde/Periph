'use strict';

// Touchless presence gate with multi-rate ranging: a first measurement picks
// the profile (long range in a dark room, default otherwise), then timed
// continuous ranging at 100 ms feeds an out-of-window interrupt — closer than
// 10 cm is an ENTER event, the scene clearing beyond 80 cm a LEAVE event.
// After 20 events or 60 s, it prints statistics over 10 fresh samples, stops
// ranging and recalibrates. Without a GPIO1 pin on the connection the
// driver's polling fallback delivers the events.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL53L0XFull } = require('periph/src/chips/tof/vl53l0x');

const MAX_EVENTS = 20;
const MAX_MS = 60000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL53L0XFull.I2C_ADDRESS);
    const sensor = new VL53L0XFull(connection);                                    // Create VL53L0X Full driver, (connection)
    await sensor.init();                                                           // Wait for init sequence, () → None

    // --- Pick a profile from the ambient light level ---
    // The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods) reaches
    // ~2 m, but only without IR background; in daylight it mostly adds invalid
    // readings. One single-shot measurement tells us how bright the scene is.
    await sensor.distance();                                                       // Measure distance, () → number mm
    const first = await sensor.readMeasurement();                                  // Read result block, () → {distanceMm, …}
    if (first.ambientRateMcps < 0.5) {
        await sensor.setProfile('long_range');                                     // Apply ranging profile, (profile) → None
        console.log(`dark scene (${first.ambientRateMcps.toFixed(2)} MCPS ambient): long_range profile`);
    } else {
        await sensor.setProfile('default');                                        // Apply ranging profile, (profile) → None
        console.log(`bright scene (${first.ambientRateMcps.toFixed(2)} MCPS ambient): default profile`);
    }

    // --- Arm the presence gate ---
    // Timed ranging every 100 ms keeps the laser mostly idle. The firmware
    // compares each result with the 100 mm / 800 mm window itself and only
    // raises GPIO1 when a reading falls outside it.
    await sensor.setInterruptThresholds(100, 800);                                 // Set distance thresholds, (lowMm mm, highMm mm) → None
    await sensor.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);                // Select interrupt source, (source) → None
    await sensor.startContinuous(100);                                             // Start continuous ranging, (periodMs=0 ms) → None

    // --- Classify each event ---
    // The status is already cleared; the result block still holds the
    // measurement that triggered it.
    let events = 0;
    await sensor.onInterrupt(async () => {                                         // Subscribe to GPIO1, (callback) → None
        const d = (await sensor.readMeasurement()).distanceMm;                     // Read result block, () → {distanceMm, …}
        events++;
        console.log(d < 100 ? 'ENTER' : 'LEAVE', d, 'mm');
    });
    const start = Date.now();
    while (events < MAX_EVENTS && Date.now() - start < MAX_MS) await sleep(50);

    // --- Statistics over fresh samples ---
    // Threshold sources hide ordinary samples from dataReady(), so switch back
    // to new-sample-ready before using the blocking continuous reads.
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
    await sensor.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY);             // Select interrupt source, (source) → None
    await sensor.pollInterrupt();                                                  // Read and clear status, () → number
    const samples = [];
    let rate = 0;
    for (let i = 0; i < 10; i++) {
        samples.push(await sensor.readContinuous());                               // Read next continuous result, () → number mm
        rate += (await sensor.readMeasurement()).signalRateMcps;                   // Read result block, () → {signalRateMcps, …}
    }
    const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
    console.log(`mean ${mean.toFixed(0)} mm, min ${Math.min(...samples)} mm, max ${Math.max(...samples)} mm, ` +
        `signal ${(rate / samples.length).toFixed(2)} MCPS`);

    // --- Shut down and recalibrate ---
    // Reference calibration must run in software standby. Repeat it whenever
    // the sensor's temperature has drifted more than 8 °C.
    await sensor.stopContinuous();                                                 // Stop continuous ranging, () → None
    await sleep(200);
    await sensor.recalibrate();                                                    // Rerun reference calibration, () → None
    console.log('done');
    await connection.close();
})();
