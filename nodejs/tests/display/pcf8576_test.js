'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { PCF8576Minimal, PCF8576Full } = require('../../packages/periph/src/chips/display/pcf8576');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

let passed = 0, failed = 0;

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const lcd = new PCF8576Minimal(transport);

if (lcd._cmdMode(false, 0x00, 0x00) === 0x40) { console.log('PASS mode_set_off'); passed++; }
else { console.log('FAIL mode_set_off'); failed++; }

if (lcd._cmdMode(true, 0x00, 0x00) === 0x48) { console.log('PASS mode_set_on'); passed++; }
else { console.log('FAIL mode_set_on'); failed++; }

if (lcd._cmdMode(true, 0x00, 0x01) === 0x49) { console.log('PASS mode_set_static'); passed++; }
else { console.log('FAIL mode_set_static'); failed++; }

if (lcd._cmdMode(true, 0x04, 0x00) === 0x4C) { console.log('PASS mode_set_half_bias'); passed++; }
else { console.log('FAIL mode_set_half_bias'); failed++; }

if (PCF8576Minimal.SEVEN_SEG[0] === 0xED && PCF8576Minimal.SEVEN_SEG[9] === 0xEB) {
    console.log('PASS seven_seg_lookup');
    passed++;
} else {
    console.log('FAIL seven_seg_lookup');
    failed++;
}

try {
    lcd.clear();
    console.log('PASS clear');
    passed++;
} catch (e) { console.log('FAIL clear: ' + e.message); failed++; }

try {
    lcd.setDigit7seg(0, 0xED);
    lcd.setDigit7seg(1, 0x60);
    console.log('PASS set_digit_7seg');
    passed++;
} catch (e) { console.log('FAIL set_digit_7seg: ' + e.message); failed++; }

try {
    lcd.writeRaw(0, [0xED, 0x60, 0xA7, 0xE3]);
    console.log('PASS write_raw');
    passed++;
} catch (e) { console.log('FAIL write_raw: ' + e.message); failed++; }

const lcdFull = new PCF8576Full(transport);
try {
    lcdFull.enable();
    lcdFull.disable();
    lcdFull.enable();
    console.log('PASS enable_disable');
    passed++;
} catch (e) { console.log('FAIL enable_disable: ' + e.message); failed++; }

try {
    lcdFull.setMode(PCF8576Full.BACKPLANES_4, PCF8576Full.BIAS_1_3);
    lcdFull.setMode(PCF8576Full.BACKPLANES_2, PCF8576Full.BIAS_1_2);
    lcdFull.setMode(PCF8576Full.BACKPLANES_1, PCF8576Full.BIAS_1_3);
    console.log('PASS set_mode');
    passed++;
} catch (e) { console.log('FAIL set_mode: ' + e.message); failed++; }

try {
    lcdFull.setBlink(PCF8576Full.BLINK_2_HZ);
    lcdFull.setBlink(PCF8576Full.BLINK_OFF);
    console.log('PASS set_blink');
    passed++;
} catch (e) { console.log('FAIL set_blink: ' + e.message); failed++; }

try {
    lcdFull.setBank(0, 0);
    lcdFull.setBank(1, 1);
    console.log('PASS set_bank');
    passed++;
} catch (e) { console.log('FAIL set_bank: ' + e.message); failed++; }

try {
    lcdFull.deviceSelect(0);
    lcdFull.deviceSelect(7);
    console.log('PASS device_select');
    passed++;
} catch (e) { console.log('FAIL device_select: ' + e.message); failed++; }

transport.close();
console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
