'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../packages/periph/src/connection/hx711');

const GPIO_CHIP  = parseInt(process.env.GPIO_CHIP    || '0',  10);
const DOUT_LINE   = parseInt(process.env.HX711_DOUT   || '5',  10);
const PD_SCK_LINE = parseInt(process.env.HX711_PD_SCK || '6',  10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const dout   = Default.input({ chip: GPIO_CHIP, line: DOUT_LINE });
const pd_sck = Default.output({ chip: GPIO_CHIP, line: PD_SCK_LINE });
const connection = new HX711Connection(dout, pd_sck);

checkTrue('isReady returns bool', typeof connection.isReady() === 'boolean');

let val = connection.readRaw(25);
checkTrue('readRaw(25) returns number', typeof val === 'number');
checkTrue('readRaw(25) in 24-bit signed range', val >= -8388608 && val <= 8388607);

val = connection.readRaw(26);
checkTrue('readRaw(26) returns number', typeof val === 'number');
checkTrue('readRaw(26) in 24-bit signed range', val >= -8388608 && val <= 8388607);

val = connection.readRaw(27);
checkTrue('readRaw(27) returns number', typeof val === 'number');
checkTrue('readRaw(27) in 24-bit signed range', val >= -8388608 && val <= 8388607);

try {
    connection.readRaw(24);
    checkTrue('readRaw(24) throws', false);
} catch (e) {
    checkTrue('readRaw(24) throws', true);
}

connection.powerDown();
checkTrue('powerDown accepted', true);

connection.powerUp();
checkTrue('powerUp accepted', true);

connection.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
