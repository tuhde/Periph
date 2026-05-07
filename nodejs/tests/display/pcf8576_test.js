'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { PCF8576Minimal, PCF8576Full } = require('../../packages/periph/src/chips/display/pcf8576');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log(`FAIL ${label}: got 0x${got.toString(16).toUpperCase().padStart(4,'0')}, expected 0x${expected.toString(16).toUpperCase().padStart(4,'0')}`);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const lcd = new PCF8576Full(transport);

lcd.clear();
checkTrue('clear: no exception', true);

lcd.setDigit7Seg(0, PCF8576Full.SEG_7SEG[0]);
checkTrue('set_digit_7seg: no exception', true);

lcd.writeRaw(0, Buffer.from([0xED, 0x60]));
checkTrue('write_raw: no exception', true);

lcd.enable();
checkTrue('enable: no exception', true);

lcd.disable();
checkTrue('disable: no exception', true);

lcd.setMode(4, 0);
checkTrue('set_mode: no exception', true);

lcd.setBlink(PCF8576Full.BLINK_OFF);
checkTrue('set_blink: no exception', true);

lcd.setBank(0, 0);
checkTrue('set_bank: no exception', true);

lcd.deviceSelect(0);
checkTrue('device_select: no exception', true);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);