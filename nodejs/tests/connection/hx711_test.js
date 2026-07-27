'use strict';

const Gpio = require('onoff').Gpio;
const { HX711Connection } = require('../../packages/periph/src/connection/hx711');

const DOUT_PIN   = parseInt(process.env.HX711_DOUT   || '5',  10);
const PD_SCK_PIN = parseInt(process.env.HX711_PD_SCK || '6',  10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const dout   = new Gpio(DOUT_PIN,   'in');
const pd_sck = new Gpio(PD_SCK_PIN, 'out');
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
