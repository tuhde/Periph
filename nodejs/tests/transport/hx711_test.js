'use strict';

const Gpio = require('onoff').Gpio;
const { HX711Transport } = require('../../packages/periph/src/transport/hx711');

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
const transport = new HX711Transport(dout, pd_sck);

checkTrue('isReady returns bool', typeof transport.isReady() === 'boolean');

let val = transport.readRaw(25);
checkTrue('readRaw(25) returns number', typeof val === 'number');
checkTrue('readRaw(25) in 24-bit signed range', val >= -8388608 && val <= 8388607);

val = transport.readRaw(26);
checkTrue('readRaw(26) returns number', typeof val === 'number');
checkTrue('readRaw(26) in 24-bit signed range', val >= -8388608 && val <= 8388607);

val = transport.readRaw(27);
checkTrue('readRaw(27) returns number', typeof val === 'number');
checkTrue('readRaw(27) in 24-bit signed range', val >= -8388608 && val <= 8388607);

try {
    transport.readRaw(24);
    checkTrue('readRaw(24) throws', false);
} catch (e) {
    checkTrue('readRaw(24) throws', true);
}

transport.powerDown();
checkTrue('powerDown accepted', true);

transport.powerUp();
checkTrue('powerUp accepted', true);

transport.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
