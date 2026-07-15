'use strict';

const { DHT11Pin } = require('../../packages/periph/src/transport/dht11');
const { DHT11Full } = require('../../packages/periph/src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DHT11_PIN || '4', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

function checkRange(label, value, lo, hi) {
    checkTrue(`${label} in [${lo}, ${hi}]`, value >= lo && value <= hi);
}

const pin = new DHT11Pin(DATA_PIN);
const dht = new DHT11Full(pin);

let r;
try {
    r = dht.read();
    checkTrue('read returns object',         typeof r === 'object');
    checkTrue('read has temperature_c',      r && typeof r.temperature_c === 'number');
    checkTrue('read has humidity_rh',        r && typeof r.humidity_rh   === 'number');
    checkRange('read temperature',    r.temperature_c, -20.0, 60.0);
    checkRange('read humidity',       r.humidity_rh,    0.0, 100.0);
} catch (e) {
    checkTrue('read threw (no sensor wired?): ' + e.message, false);
}

const r = dht.readRetry(3);
checkTrue('readRetry returns object',         typeof r === 'object');
checkTrue('readRetry has temperature_c',      r && typeof r.temperature_c === 'number');
checkTrue('readRetry has humidity_rh',        r && typeof r.humidity_rh   === 'number');
checkRange('readRetry temperature',     r.temperature_c, -20.0, 60.0);
checkRange('readRetry humidity',        r.humidity_rh,    0.0, 100.0);

let raw;
try {
    raw = dht.readRaw();
    checkTrue('readRaw returns array',        Array.isArray(raw));
    checkTrue('readRaw length is 5',          Array.isArray(raw) && raw.length === 5);
    if (Array.isArray(raw) && raw.length === 5) {
        const checksum = (raw[0] + raw[1] + raw[2] + raw[3]) & 0xff;
        checkTrue('readRaw checksum OK',       checksum === raw[4]);
    }
} catch (e) {
    checkTrue('readRaw threw (no sensor wired?): ' + e.message, false);
}

pin.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
