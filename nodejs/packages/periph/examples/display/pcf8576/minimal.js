'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { PCF8576Minimal } = require('../../../src/chips/display/pcf8576');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const lcd = new PCF8576Minimal(transport);                // Create PCF8576 driver, (transport)

const digits = [1, 2, 3, 4];
for (let i = 0; i < digits.length; i++) {
    const seg = PCF8576Minimal.SEVEN_SEG[digits[i]];     // Encode 7-segment digit, (digit 0–9) → number
    lcd.setDigit7seg(i, seg);                            // Write one digit, (position 0–19, segments 0–255) → void
}

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
