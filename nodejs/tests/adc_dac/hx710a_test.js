'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../packages/periph/src/connection/hx711');
const { HX710AFull }      = require('../../packages/periph/src/chips/adc_dac/hx710a');

const GPIO_CHIP  = parseInt(process.env.GPIO_CHIP     || '0',  10);
const DOUT_LINE   = parseInt(process.env.HX710A_DOUT   || '5',  10);
const PD_SCK_LINE = parseInt(process.env.HX710A_PD_SCK || '6',  10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const dout   = Default.input({ chip: GPIO_CHIP, line: DOUT_LINE });
const pd_sck = Default.output({ chip: GPIO_CHIP, line: PD_SCK_LINE });
const connection = new HX711Connection(dout, pd_sck);
const chip = new HX710AFull(connection);

checkTrue('isReady returns boolean', typeof chip.isReady() === 'boolean');

const raw = chip.readRaw();
checkTrue('readRaw returns number', typeof raw === 'number');
checkTrue('readRaw in 24-bit signed range', raw >= -8388608 && raw <= 8388607);

chip.setRate(40);
checkTrue('setRate(40) accepted', true);

chip.setRate(10);
checkTrue('setRate(10) accepted', true);

try {
    chip.setRate(20);
    checkTrue('setRate(20) throws', false);
} catch (e) {
    checkTrue('setRate(20) throws', true);
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

const tempRaw = chip.readTemperatureRaw();
checkTrue('readTemperatureRaw returns number', typeof tempRaw === 'number');
checkTrue('readTemperatureRaw in 24-bit signed range', tempRaw >= -8388608 && tempRaw <= 8388607);

connection.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
