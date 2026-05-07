'use strict';

const { I2CTransport } = require('periph/src/transport/i2c');
const { PCF8576Full } = require('periph/src/chips/display/pcf8576');

const transport = new I2CTransport(1, 0x38);
const lcd = new PCF8576Full(transport);

lcd.clear();

let counter = 9999;
const interval = setInterval(() => {
    if (counter < 0) {
        clearInterval(interval);
        const dash = Buffer.from([0x49, 0x49, 0x49, 0x49]);
        lcd.writeRaw(0, dash);
        console.log('done');
        return;
    }
    const digits = [
        Math.floor(counter / 1000) % 10,
        Math.floor(counter / 100) % 10,
        Math.floor(counter / 10) % 10,
        counter % 10
    ];
    console.log(counter);
    const data = Buffer.from(digits.map(d => PCF8576Full.SEG_7SEG[d]));
    lcd.writeRaw(0, data);
    counter--;
}, 1000);