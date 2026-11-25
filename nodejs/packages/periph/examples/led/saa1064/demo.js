'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { SAA1064Full } = require('../../../src/chips/led/saa1064');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four
    // positions, the segment current stepped through 3/9/15/21 mA, a switch to
    // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
    // status byte (the PR flag is set after power-up and clears on the first read).
    const display = new SAA1064Full(connection);              // Create SAA1064 driver, (connection)
    await display.ready;

    for (let n = 0; n < 10000; n += 37) {
        await display.showNumber(n);                          // Show the counter, (value 0–9999) → void
        await sleep(10);
    }

    await display.showDigits([1, 2, 3, 4]);                   // Show four digits, (digits) → void
    for (const pos of [1, 2, 3, 4]) {
        await display.setDecimalPoint(pos, true);             // Light a decimal point, (position, on) → void
        await sleep(400);
        await display.setDecimalPoint(pos, false);
    }

    for (const ma of [3, 9, 15, 21]) {
        await display.setCurrent(ma);                         // Step the segment current, (ma) → void
        await display.showNumber(8888);
        await sleep(1000);
    }
    await display.setCurrent(12);

    await display.setMode(false);                             // Static mode, (dynamic) → void
    await sleep(2000);
    await display.setMode(true);

    await display.segmentTest(true);                          // Segment test, (on) → void
    await sleep(2000);
    await display.segmentTest(false);

    console.log((await display.readStatus()).toString(16));   // Read the status byte, () → number
    console.log(await display.powerResetFlag());              // Read the PR flag, () → boolean
    await display.clear();                                    // Blank all digits, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
