'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { HT16K33Full } = require('../../../src/chips/led/ht16k33');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x70', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const matrix = new HT16K33Full(connection);               // Create HT16K33 driver, (connection)
    await matrix.ready;

    await matrix.clear();                                     // Blank the display, () → void
                                                              // zeros the shadow RAM and writes it
    matrix.setPixel(2, 5);                                    // Set one LED, (com 0–7, row 0–15, on) → void
    await matrix.show();                                      // Write the shadow RAM, () → void
    console.log(matrix.getPixel(2, 5));                       // Read a pixel from the shadow RAM, (com, row) → boolean
    await matrix.writeRaw([0xFF, 0x0F], 0);                   // Write raw RAM bytes, (data 1–16 bytes, address 0–15) → void
                                                              // com 0 rows 0–11 lit
    await matrix.setBrightness(8);                            // Set dimming, (level 0–15) → void
                                                              // 9/16 duty
    await matrix.setBlink(1);                                 // Set blink frequency, (0, 0.5, 1, 2 Hz) → void
                                                              // blinks at 1 Hz
    await sleep(3000);
    await matrix.setBlink(0);
    await matrix.displayOff();                                // Display output off, () → void
    await matrix.displayOn();                                 // Display output on, () → void
    await matrix.setIntOutput(true, false);                   // ROW15/INT pin as INT output, (enable, activeHigh) → void
                                                              // active low interrupt output
    console.log(await matrix.readKeys());                     // Read and clear key data, () → number bitmask
    console.log(await matrix.keyPressed());                   // Read the INT flag, () → boolean
    console.log(await matrix.pollInterrupt());                // Read keys if INT flag set, () → number (0 if none)
    await matrix.onInterrupt((keys) => console.log(keys));    // Subscribe to key presses, (callback, {intPin}) → void
                                                              // polls every 20 ms when no INT pin is wired
    await sleep(100);
    await matrix.offInterrupt();                              // Unsubscribe, () → void
    await matrix.standby();                                   // Oscillator off, () → void
    await matrix.wake();                                      // Oscillator on and restore state, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
