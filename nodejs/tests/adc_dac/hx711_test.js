'use strict';

const Gpio = require('onoff').Gpio;
const { HX711Transport } = require('../../packages/periph/src/transport/hx711');
const { HX711Full }      = require('../../packages/periph/src/chips/adc_dac/hx711');

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
const chip = new HX711Full(transport);

checkTrue('isReady returns boolean', typeof chip.isReady() === 'boolean');

const raw = chip.readRaw();
checkTrue('readRaw returns number', typeof raw === 'number');
checkTrue('readRaw in 24-bit signed range', raw >= -8388608 && raw <= 8388607);

chip.setGain(128);
checkTrue('setGain(128) accepted', true);

chip.setGain(64);
checkTrue('setGain(64) accepted', true);

chip.setGain(32);
checkTrue('setGain(32) accepted', true);

chip.setGain(128);

try {
    chip.setGain(99);
    checkTrue('setGain(99) throws', false);
} catch (e) {
    checkTrue('setGain(99) throws', true);
}

const avg = chip.readAverage(3);
checkTrue('readAverage returns number', typeof avg === 'number');
checkTrue('readAverage in 24-bit signed range', avg >= -8388608 && avg <= 8388607);

chip.tare(3);
checkTrue('tare accepted', true);

const offset = chip.getOffset();
checkTrue('getOffset returns number', typeof offset === 'number');

chip.setScale(420.0);
checkTrue('setScale accepted', true);

const scale = chip.getScale();
checkTrue('getScale returns 420.0', scale === 420.0);

const weight = chip.readWeight(1);
checkTrue('readWeight returns number', typeof weight === 'number');

chip.powerDown();
checkTrue('powerDown accepted', true);

chip.powerUp();
checkTrue('powerUp accepted', true);

transport.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
