'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { FXOS8700Full, FXOS8700Source } = require('periph/src/chips/accelerometer/fxos8700');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const connection = new I2CConnection(I2C_BUS, 0x1E);          // Open I²C bus, (busNumber, addr=0x1E) → I2CConnection
const compass = new FXOS8700Full(connection);                 // Create FXOS8700 Full driver, (connection) → FXOS8700Full

const DIRECTIONS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
                    'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];
const ORIENTATIONS = ['PORTRAIT UP', 'PORTRAIT DOWN', 'LANDSCAPE RIGHT', 'LANDSCAPE LEFT'];
const DECLINATION = 3.0;     // magnetic declination of the site in degrees
const RUN_SECONDS = 60;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    await compass.init();                                     // Wait for initialization, () → Promise<void>

    // --- Configure hybrid mode ---
    // ±4 g keeps tilt maths precise while tolerating handling; magnetometer
    // oversampling ratio 7 gives the lowest noise at this data rate.
    await compass.setRange(4);                                // Set accelerometer range, (rangeG 2|4|8) → Promise<void>
    await compass.setMagOversampling(7);                      // Set magnetometer oversampling, (ratio 0..7, sleepRatio=null) → Promise<void>

    // --- Check the accelerometer ---
    // The electrostatic self-test must move every axis by the datasheet minimum.
    const st = await compass.selfTest();                      // Run accelerometer self-test, () → Promise<{ok, dx, dy, dz}> counts
    console.log('self-test', st.ok ? 'OK' : 'FAILED', st.dx, st.dy, st.dz);

    // --- Calibrate the hard-iron offsets ---
    // Auto-calibration writes the midpoint of the tracked min/max to the
    // offset registers every cycle; rotate the board through all
    // orientations meanwhile.
    await compass.setMagAutoCalibration(true);                // Hardware hard-iron calibration, (enabled boolean) → Promise<void>
    console.log('Rotate the board in all directions for 15 s ...');
    for (let s = 0; s < 15; s++) {
        await sleep(1000);
        const { min, max } = await compass.readMagMinMax();   // Read tracked min / max, () → Promise<{min, max}> uT
        console.log(`span ${(max.x - min.x).toFixed(1)} ${(max.y - min.y).toFixed(1)} ${(max.z - min.z).toFixed(1)} uT`);
    }
    await compass.setMagAutoCalibration(false);               // Hardware hard-iron calibration, (enabled boolean) → Promise<void>
    console.log('offsets', await compass.getMagOffset());     // Get hard-iron offsets, () → Promise<{x, y, z}> uT

    // --- Arm orientation and motion detection ---
    // Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
    // chip to a low rate after 10 s without motion, and motion wakes it again.
    await compass.setOrientation({ debounceMs: 100 });        // Configure orientation detection, ({enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=2}) → Promise<void>
    await compass.setFreefallMotion(true, { thresholdG: 0.3, debounceMs: 60, latch: true });  // Configure freefall / motion, (motion, {axes=7, thresholdG=0.3, debounceMs=0, latch=false, debounceClear=false}) → Promise<void>
    await compass.setAutoSleep(true, { sleepRateHz: 12.5, timeoutS: 10, wakeSources: FXOS8700Source.SOURCE_FF_MT });  // Configure Auto-SLEEP, (enabled, {sleepRateHz=50, timeoutS=10, wakeSources=0}) → Promise<void>
    await compass.enableInterrupt(FXOS8700Source.SOURCE_ORIENTATION | FXOS8700Source.SOURCE_FF_MT, 1);  // Enable and route interrupt, (source mask, pin 1|2) → Promise<void>

    // --- Print the heading ---
    // Every 250 ms the tilt-compensated heading is shown with the board's
    // pitch and roll; the temperature is printed once per second.
    const start = Date.now();
    let lastTemperature = 0;
    while (Date.now() - start < RUN_SECONDS * 1000) {
        const heading = await compass.heading(DECLINATION);   // Compute tilt-compensated heading, (declinationDeg=0) → Promise<float> degrees
        const { accel } = await compass.readHybrid();         // Read both sensors, () → Promise<{accel, mag}>
        const roll = Math.atan2(accel.y, accel.z) * 180 / Math.PI;
        const pitch = Math.atan2(-accel.x, Math.hypot(accel.y, accel.z)) * 180 / Math.PI;
        console.log(`${heading.toFixed(1)} deg ${DIRECTIONS[Math.floor((heading + 11.25) / 22.5) % 16]} pitch ${pitch.toFixed(1)} roll ${roll.toFixed(1)}`);
        const status = await compass.pollInterrupt();         // Poll and clear interrupt sources, () → Promise<int>
        if (status & FXOS8700Source.SOURCE_ORIENTATION) {
            const o = await compass.readOrientation();        // Read orientation, () → Promise<{changed, lockout, portraitLandscape, back}>
            console.log(ORIENTATIONS[o.portraitLandscape]);
        }
        if (status & FXOS8700Source.SOURCE_FF_MT) {
            console.log('MOTION', await compass.readSystemMode());  // Read SYSMOD, () → Promise<int> 0 standby | 1 wake | 2 sleep
        }
        if (Date.now() - lastTemperature >= 1000) {
            lastTemperature = Date.now();
            console.log(`temperature ${(await compass.readTemperature()).toFixed(1)} C`);  // Read die temperature, () → Promise<float> °C
        }
        await sleep(250);
    }
    await connection.close();
})();
