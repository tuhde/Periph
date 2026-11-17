'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { HT16K33Full } = require('../../../src/chips/led/ht16k33');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x70', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    const matrix = new HT16K33Full(connection);               // Create HT16K33 driver, (connection)
    await matrix.ready;

    for (let com = 0; com < HT16K33Full.COMMONS; com++) {
        for (let row = 0; row < HT16K33Full.ROWS; row++) {
            await matrix.clear();                             // Blank the display, () → void
            matrix.setPixel(com, row);                        // Set one LED, (com 0–7, row 0–15, on) → void
            await matrix.show();                              // Write the shadow RAM, () → void
            await sleep(50);
        }
    }

    await matrix.writeRaw(new Array(16).fill(0xFF), 0);       // All LEDs on, (data, address) → void
    for (let level = 0; level < 16; level++) {
        await matrix.setBrightness(level);                    // Sweep dimming, (level 0–15) → void
        await sleep(100);
    }

    for (const f of [2, 1, 0.5, 0]) {
        await matrix.setBlink(f);                             // Set blink frequency, (0, 0.5, 1, 2 Hz) → void
        await sleep(3000);
    }

    await matrix.setIntOutput(true);                          // INT output, active low, (enable, activeHigh) → void
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline) {
        const keys = await matrix.pollInterrupt();            // Read keys if pending, () → number bitmask
        if (keys) console.log('keys: 0x' + keys.toString(16));
        await sleep(20);
    }

    await matrix.standby();                                   // Oscillator off, () → void
    await sleep(1000);
    await matrix.wake();                                      // Oscillator on, state restored, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
