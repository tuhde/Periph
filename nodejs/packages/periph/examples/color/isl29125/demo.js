'use strict';

// Demo: a colour sampler.
// Prints the device ID, samples R/G/B for 10 s and shows the colour as
// #rrggbb, compares the 375 lux and 10 000 lux ranges and the 12/16-bit
// resolutions, varies the IR compensation, reads green only, arms a threshold
// interrupt on green with persistence 2 and finishes with power-down and soft
// reset.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ISL29125Full } = require('periph/src/chips/color/isl29125');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hex2 = (v) => v.toString(16).padStart(2, '0');

function colour(c) {
    const top = Math.max(c.red, c.green, c.blue, 1);
    return '#' + [c.red, c.green, c.blue].map((v) => hex2(Math.floor(v * 255 / top))).join('');
}

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ISL29125Full.I2C_ADDRESS);
    const sensor = new ISL29125Full(connection);                            // Create ISL29125 Full driver, (connection)
    await sensor.init();                                                    // Check DEVICE_ID, start RGB conversion, () → void

    console.log('device id', (await sensor.deviceId()).toString(16));       // Read DEVICE_ID, () → number

    for (let i = 0; i < 20; i++) {
        const c = await sensor.read();                                      // Read the three channels, () → {red, green, blue}
        console.log(colour(c), c.red, c.green, c.blue);
        await sleep(500);
    }

    for (const lux of [375, 10000]) {
        await sensor.setRange(lux);                                         // Set the range, (lux) → void
        await sleep(400);
        console.log(lux, 'lux range', await sensor.readRgb());              // Read without waiting, () → {red, green, blue}
    }
    for (const bits of [12, 16]) {
        await sensor.setResolution(bits);                                   // Set the resolution, (bits) → void
        await sleep(400);
        console.log(bits, 'bit full scale', 2 ** bits - 1, await sensor.readRgb());
    }
    await sensor.setRange(10000);

    for (const adjust of [0, 0x20, 0x3F]) {
        await sensor.setIrCompensation(false, adjust);                      // Set the IR compensation, (offset, adjust) → void
        await sleep(400);
        console.log('IR compensation', adjust, await sensor.readRgb());
    }
    await sensor.setIrCompensation(true, 0x3F);

    await sensor.setMode('green');                                          // Green only, (mode) → void
    await sleep(300);
    console.log('green only', await sensor.readGreen());                    // Read green, () → number
    await sensor.setMode('rgb');
    console.log('ready', await sensor.dataReady());                         // Query CONVENF, () → boolean

    const base = await sensor.readGreen();
    await sensor.setThresholds(Math.max(0, base - (base >> 2)), Math.min(0xFFFF, base + (base >> 2)));   // Set the window, (low, high) → void
    console.log('window', await sensor.getThresholds());                    // Read the window, () → {low, high}
    await sensor.setInterruptChannel('green');                              // Choose the channel, (channel) → void
    await sensor.setPersistence(2);                                         // Set the persistence, (count) → void
    await sensor.enableInterrupt(ISL29125Full.SOURCE_THRESHOLD);            // Enable the interrupt, (source) → void
    for (let i = 0; i < 20; i++) {
        await sleep(500);
        const st = await sensor.pollInterrupt();                            // Read and clear STATUS, () → number
        if (st & ISL29125Full.STATUS_THRESHOLD) console.log('window crossed, green', await sensor.readGreen());
    }
    await sensor.disableInterrupt(ISL29125Full.SOURCE_THRESHOLD);           // Disable the interrupt, (source) → void
    console.log((await sensor.status()).toString(16));                      // Raw STATUS, () → number
    await sensor.setMode('power_down');                                     // Power down, (mode) → void
    await sensor.softReset();                                               // Software reset, () → void

    await connection.close();
})();
