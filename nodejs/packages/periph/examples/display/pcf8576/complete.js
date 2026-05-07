'use strict';

const { I2CTransport } = require('periph/src/transport/i2c');
const { PCF8576Full } = require('periph/src/chips/display/pcf8576');

const transport = new I2CTransport(1, 0x38);
const lcd = new PCF8576Full(transport);

lcd.clear();
lcd.setBlink(lcd.BLINK_OFF);
lcd.enable();
lcd.setMode(4, 0);
lcd.deviceSelect(0);
lcd.setBank(0, 0);
lcd.setDigit7Seg(0, PCF8576Full.SEG_7SEG[1]);
lcd.setDigit7Seg(1, PCF8576Full.SEG_7SEG[2]);
lcd.setDigit7Seg(2, PCF8576Full.SEG_7SEG[3]);
lcd.setDigit7Seg(3, PCF8576Full.SEG_7SEG[4]);
setTimeout(() => {
    lcd.disable();
    setTimeout(() => {
        lcd.enable();
        lcd.setBlink(lcd.BLINK_1HZ);
    }, 1000);
}, 1000);