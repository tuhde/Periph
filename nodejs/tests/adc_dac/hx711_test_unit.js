'use strict';
const { HX711ConnectionMock } = require('../../packages/periph/src/connection/hx711_mock');
const { HX711Minimal, HX711Full } = require('../../packages/periph/src/chips/adc_dac/hx711');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// --- HX711Minimal ---

let connection = new HX711ConnectionMock();
connection.queueRead(0);  // discarded by construction
let sensor = new HX711Minimal(connection);
checkTrue('init_discards_first_reading', connection.reads.length === 1 && connection.reads[0] === 25);

connection.ready = false;
checkTrue('is_ready_false', sensor.isReady() === false);
connection.ready = true;
checkTrue('is_ready_true', sensor.isReady() === true);

connection.queueRead(12345);
checkTrue('read_raw_gain_128', sensor.readRaw() === 12345);
checkTrue('read_raw_uses_25_pulses', connection.reads[connection.reads.length - 1] === 25);

// --- HX711Full ---

connection = new HX711ConnectionMock();
connection.queueRead(0);
sensor = new HX711Full(connection);
checkTrue('full_init_discards_first_reading', connection.reads.length === 1 && connection.reads[0] === 25);

connection.queueRead(1000);
checkTrue('full_read_raw_default_gain_128', sensor.readRaw() === 1000);
checkTrue('full_read_raw_default_25_pulses', connection.reads[connection.reads.length - 1] === 25);

// setGain(): selects pulse count and issues one dummy read to apply it.
connection.queueRead(0);  // dummy read issued by setGain(64)
sensor.setGain(64);
checkTrue('set_gain_64_issues_dummy_read', connection.reads[connection.reads.length - 1] === 27);
connection.queueRead(2000);
sensor.readRaw();
checkTrue('set_gain_64_pulses', connection.reads[connection.reads.length - 1] === 27);

connection.queueRead(0);  // dummy read issued by setGain(32)
sensor.setGain(32);
checkTrue('set_gain_32_issues_dummy_read', connection.reads[connection.reads.length - 1] === 26);
connection.queueRead(3000);
sensor.readRaw();
checkTrue('set_gain_32_pulses', connection.reads[connection.reads.length - 1] === 26);

connection.queueRead(0);  // dummy read issued by setGain(128)
sensor.setGain(128);
checkTrue('set_gain_128_issues_dummy_read', connection.reads[connection.reads.length - 1] === 25);

let threw = false;
try {
    sensor.setGain(99);
} catch (e) {
    threw = true;
}
checkTrue('set_gain_invalid_throws', threw);

// readAverage(): mean of `times` raw readings, integer division.
connection.queueRead(10);
connection.queueRead(20);
connection.queueRead(33);
checkTrue('read_average', sensor.readAverage(3) === Math.trunc((10 + 20 + 33) / 3));

// tare(): captures readAverage() as the offset.
connection.queueRead(100);
connection.queueRead(100);
sensor.tare(2);
checkTrue('tare_sets_offset', sensor.getOffset() === 100);

// setScale()/getScale().
sensor.setScale(2.5);
checkTrue('set_scale', sensor.getScale() === 2.5);

// readWeight(): (readAverage(times) - offset) / scale.
connection.queueRead(350);
checkTrue('read_weight', sensor.readWeight(1) === (350 - 100) / 2.5);

// powerDown()/powerUp(): powerUp() resets pulse count to 25 and discards
// one reading, even if a non-default gain was previously selected.
connection.queueRead(0);  // dummy read issued by setGain(64)
sensor.setGain(64);
sensor.powerDown();
checkTrue('power_down_calls_connection', connection.powerCalls[connection.powerCalls.length - 1] === 'down');
connection.queueRead(0);  // discarded by powerUp()
sensor.powerUp();
checkTrue('power_up_calls_connection', connection.powerCalls[connection.powerCalls.length - 1] === 'up');
checkTrue('power_up_resets_gain', connection.reads[connection.reads.length - 1] === 25);
connection.queueRead(4242);
sensor.readRaw();
checkTrue('power_up_read_raw_uses_25_pulses', connection.reads[connection.reads.length - 1] === 25);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
