'use strict';

const { I2CTransport } = require('periph/src/transport/i2c');
const { PCF8576Minimal } = require('periph/src/chips/display/pcf8576');

const transport = new I2CTransport(1, 0x38);
const lcd = new PCF8576Minimal(transport);

lcd.clear();
lcd.setDigit7Seg(0, 0xCB);
lcd.setDigit7Seg(1, 0xCF);
lcd.setDigit7Seg(2, 0xE3);
lcd.setDigit7Seg(3, 0xE0);