'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { SAA1064Full } = require('../../../src/chips/led/saa1064');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const display = new SAA1064Full(connection);              // Create SAA1064 driver, (connection)
    await display.ready;
    await display.showDigits([1, 2, 3, 4]);                   // Show four digits, (digits 4 × 0–9 or null) → void
                                                              // default mapping a=bit0 … g=bit6, dp=bit7
    await sleep(1000);
    await display.showNumber(42);                             // Show a number, (value 0–9999) → void
                                                              // leading zeros are blanked
    await display.writeRaw([0x7F, 0x7F], 3);                  // Write raw segment bytes, (patterns 1–4, firstDigit 1–4) → void
                                                              // digits 3 and 4 show 8
    await sleep(1000);
    await display.showDigit(2, 7, true);                      // Show one digit, (position 1–4, value 0–9 or null, dp) → void
                                                              // digit 2 = 7 with decimal point
    await display.setDecimalPoint(2, false);                  // Switch a decimal point, (position 1–4, on) → void
                                                              // keeps the cached segments
    display.setSegmentMap({ a: 0, b: 1, c: 2, d: 3, e: 4, f: 5, g: 6, dp: 7 }); // Remap segment bits, (mapping a–g, dp → bit 0–7) → void
    await display.setCurrent(21);                             // Set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → void
                                                              // C4–C6
    await display.setMode(false);                             // Select static/dynamic mode, (dynamic) → void
                                                              // static shows only digits 1 and 2
    await sleep(1000);
    await display.setMode(true);
    await display.blank(true, false);                         // Blank digit pairs, (digits13, digits24) → void
                                                              // digits 1+3 off
    await sleep(1000);
    await display.blank(false, false);
    await display.segmentTest(true);                          // Segment test, (on) → void
                                                              // lights every segment
    await sleep(1000);
    await display.segmentTest(false);
    console.log(await display.readStatus());                  // Read the status byte, () → number
    console.log(await display.powerResetFlag());              // Read the PR flag, () → boolean
                                                              // set after power-up, clears when read
    await display.writeControl(0x47);                         // Write the raw control byte, (value 0–127) → void
                                                              // dynamic mode, unblanked, 12 mA
    await display.clear();                                    // Blank all digits, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
