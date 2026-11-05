'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { PCF8578Minimal } = require('../../../src/chips/display/pcf8578');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x3C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lcd = new PCF8578Minimal(connection, 16);              // Create PCF8578 driver, (connection, rows=16 8/16/24/32)

(async () => {
    await lcd.clear();                                       // Clear display RAM, () → void
    await lcd.setPixel(0, 0);                                // Set one pixel, (x 0–23, y 0–15, on=true) → void
    await lcd.setPixel(23, 15);                              // Set opposite corner, (x 0–23, y 0–15, on=true) → void
    await lcd.writeColumns(4, 0, [0xFF, 0x81, 0x81, 0xFF]);  // Write column bytes, (x 0–23, bank 0–1, data bytes) → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
