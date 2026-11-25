'use strict';

// Demo: a colour sampler.
// Prints the part ID and settings, samples R/G/B/IR for 10 s and shows the
// colour as #rrggbb, compares ALS/IR mode, steps gain and resolution with
// normalised counts, arms a variation interrupt and a threshold interrupt on
// the green channel and finishes with disable and soft reset.

const { I2CConnection } = require('periph/src/connection/i2c');
const { APDS9250Full } = require('periph/src/chips/light/apds9250');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hex2 = (v) => v.toString(16).padStart(2, '0');

function colour(c) {
    const top = Math.max(c.red, c.green, c.blue, 1);
    return '#' + [c.red, c.green, c.blue].map((v) => hex2(Math.floor(v * 255 / top))).join('');
}

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, APDS9250Full.I2C_ADDRESS);
    const sensor = new APDS9250Full(connection);                            // Create APDS-9250 Full driver, (connection)
    await sensor.init();                                                    // Check PART_ID, start colour mode, () → void

    console.log('part/revision', await sensor.partId());                    // Read PART_ID, () → {part, revision}
    console.log('resolution', await sensor.getResolution(), 'bit, rate', await sensor.getMeasurementRate(),
        'ms, gain', await sensor.getGain());                                // Read the settings, () → int each

    for (let i = 0; i < 20; i++) {
        const c = await sensor.read();                                      // Read the four channels, () → {red, green, blue, ir}
        console.log(colour(c), 'IR fraction', (c.ir / Math.max(1, c.ir + c.green)).toFixed(2));
        await sleep(500);
    }

    await sensor.setMode(false);                                            // Select ALS/IR mode, (colour bool) → void
    console.log('ALS/IR', await sensor.readGreen(), await sensor.readIr()); // Read green and IR, () → int each
    await sensor.setMode(true);                                             // Back to colour mode, (colour bool) → void

    for (const gain of [1, 3, 6, 9, 18]) {
        await sensor.setGain(gain);                                         // Set the gain, (gain) → void
        await sleep(300);
        const g = await sensor.readGreen();                                 // Read green, () → int
        console.log('gain', gain, 'green', g, 'normalized', (await sensor.normalized(g)).toFixed(0));
    }
    await sensor.setGain(3);
    for (const bits of [13, 16, 17, 18, 19, 20]) {
        await sensor.setResolution(bits);                                   // Set the resolution, (bits) → void
        await sleep((await sensor.integrationTime()) * 2000 + 50);
        const g = await sensor.readGreen();
        console.log(bits, 'bit full scale', 2 ** bits - 1, 'green', g, 'normalized', (await sensor.normalized(g)).toFixed(0));
    }
    await sensor.setResolution(18);
    await sensor.setMeasurementRate(100);                                   // Set the rate, (milliseconds) → void

    await sensor.setInterruptChannel('green');                              // Choose the channel, (channel) → void
    await sensor.setPersistence(2);                                         // Set the persistence, (count) → void
    await sensor.setVariance(64);                                           // Set the variation threshold, (counts) → void
    await sensor.enableInterrupt(APDS9250Full.SOURCE_VARIATION);            // Enable the variation interrupt, (source) → void
    for (let i = 0; i < 20; i++) {
        await sleep(500);
        const status = await sensor.pollInterrupt();                        // Read and clear MAIN_STATUS, () → int
        if (status & APDS9250Full.STATUS_INTERRUPT) console.log('variation interrupt, green', await sensor.readGreen());
    }

    const base = await sensor.readGreen();
    await sensor.setThresholds(Math.max(0, base - Math.floor(base / 4)), base + Math.floor(base / 4));   // Set the window, (low, high) → void
    await sensor.enableInterrupt(APDS9250Full.SOURCE_THRESHOLD);            // Enable the threshold interrupt, (source) → void
    await sensor.onInterrupt((s) => console.log('threshold interrupt', s.toString(16)));   // Subscribe, (callback, {intPin}) → void
    await sleep(10000);
    await sensor.offInterrupt();                                            // Unsubscribe, () → void
    await sensor.disableInterrupt();                                        // Disable the interrupt, () → void
    console.log(await sensor.getThresholds(), (await sensor.status()).toString(16), await sensor.dataReady());
    console.log(await sensor.readChannels(), await sensor.readRed(), await sensor.readBlue());
    await sensor.disable();                                                 // Standby, () → void
    await sensor.softReset();                                               // Software reset, () → void

    await connection.close();
})();
