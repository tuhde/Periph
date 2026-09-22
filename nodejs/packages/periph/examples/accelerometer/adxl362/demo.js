'use strict';

const { SPIConnection } = require('../../../src/connection/spi');                    // SPIConnection class, (bus, dev, options) → SPIConnection
const { ADXL362Full } = require('../../../src/chips/accelerometer/adxl362');         // ADXL362Full class, (connection) → ADXL362Full

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });    // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const chip = new ADXL362Full(connection);                                                      // Create ADXL362 Full driver, (connection) → ADXL362Full

    // --- Configure referenced activity/inactivity thresholds ---
    await chip.setActivityThreshold(0.25, true);                                                  // Set activity threshold, (thresholdG=0.25, referenced=true) → Promise<void>
    await chip.setInactivityThreshold(0.15, true);                                                // Set inactivity threshold, (thresholdG=0.15, referenced=true) → Promise<void>
    await chip.setInactivityTime(30);                                                              // Set inactivity time, (samples=30) → Promise<void>

    // --- Engage linked/loop mode and enable both detectors ---
    await chip.enableActivityDetection(true);                                                      // Enable activity detection, (enabled=true) → Promise<void>
    await chip.enableInactivityDetection(true);                                                    // Enable inactivity detection, (enabled=true) → Promise<void>
    await chip.setLinkLoopMode(ADXL362Full.LINKLOOP_LOOP);                                        // Set link/loop mode, (mode=LOOP=3) → Promise<void>

    // --- Map AWAKE to INT2 and enter wake-up mode ---
    await chip.setInterrupt(2, ADXL362Full.SOURCE_AWAKE, true);                                   // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → Promise<void>
    await chip.setWakeupMode(true);                                                                // Enter wake-up mode, (enabled=true) → Promise<void>

    // --- Poll AWAKE for 60 s and count asleep<->awake transitions ---
    console.log('Watching for motion. Pick up or tap the board to wake; '
                + 'let it settle to sleep.');
    let lastAwake = null;
    let transitions = 0;
    const start = Date.now();
    while (Date.now() - start < 60_000) {                                                          // Loop until 60 s elapsed, () → bool
        const nowAwake = await chip.awake();                                                       // Read AWAKE bit, () → Promise<bool>
        if (lastAwake === null || nowAwake !== lastAwake) {
            const elapsed = ((Date.now() - start) / 1000).toFixed(2);
            console.log(`${elapsed}s  ${nowAwake ? 'AWAKE' : 'asleep'}`);                          // Print timestamped state, () → None
            transitions++;
            lastAwake = nowAwake;
        }
        await new Promise(r => setTimeout(r, 200));                                               // Sleep 200 ms between polls, () → None
    }

    console.log(`Total transitions observed: ${transitions}`);                                     // Print final count, () → None
    console.log('Note: during "asleep" periods the ADXL362 draws ~270 nA — '
                + 'roughly two orders of magnitude below the ~1.8 µA of the '
                + 'continuous 100 Hz measurement mode used by the Minimal '
                + 'read() example.');

    await connection.close();
})().catch(err => { console.error(err); process.exit(1); });