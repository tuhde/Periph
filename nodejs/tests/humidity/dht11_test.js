'use strict';

const { DHT11Minimal, DHT11Full, DHT11Error } = require('../../packages/periph/src/chips/humidity/dht11');

let passed = 0;
let failed = 0;

function checkEq(label, got, expected, tol) {
    const ok = Math.abs(got - expected) < (tol || 0.001);
    if (ok) { console.log('PASS', label); passed++; }
    else    { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function checkTrue(label, cond) {
    if (cond) { console.log('PASS', label); passed++; }
    else      { console.log('FAIL', label); failed++; }
}

class MockTransport {
    constructor(frame) { this._frame = frame; }
    read() { return this._frame; }
}

// Test 1: Decode datasheet example
{
    const mock = new MockTransport(Buffer.from([0x35, 0x00, 0x18, 0x04, 0x51]));
    const dht = new DHT11Minimal(mock);
    const r = dht.read();
    checkEq('decode_datasheet_example.t', r.temperature, 24.4);
    checkEq('decode_datasheet_example.h', r.humidity, 53.0);
}

// Test 2: Negative temperature
{
    const mock = new MockTransport(Buffer.from([0x20, 0x00, 0x0A, 0x81, 0xAB]));
    const dht = new DHT11Minimal(mock);
    const r = dht.read();
    checkEq('decode_negative_temperature.t', r.temperature, -10.1);
    checkEq('decode_negative_temperature.h', r.humidity, 32.0);
}

// Test 3: Checksum error
{
    const mock = new MockTransport(Buffer.from([0x35, 0x00, 0x18, 0x04, 0x00]));
    const dht = new DHT11Minimal(mock);
    let err = null;
    try { dht.read(); } catch (e) { err = e; }
    checkTrue('checksum_error_raises', err instanceof DHT11Error);
}

// Test 4: read_temperature / read_humidity
{
    const mock = new MockTransport(Buffer.from([0x35, 0x00, 0x18, 0x04, 0x51]));
    const dht = new DHT11Full(mock, 3);
    checkEq('read_temperature', dht.readTemperature(), 24.4);
    checkEq('read_humidity', dht.readHumidity(), 53.0);
}

// Test 5: read_retry success
{
    let attempts = 0;
    class Flaky {
        read() {
            attempts++;
            return attempts < 2 ? Buffer.from([0x35, 0x00, 0x18, 0x04, 0x00])
                                : Buffer.from([0x35, 0x00, 0x18, 0x04, 0x51]);
        }
    }
    const dht = new DHT11Full(new Flaky(), 3);
    const r = dht.readRetry();
    checkEq('read_retry_succeeds.t', r.temperature, 24.4);
    checkTrue('read_retry_succeeds.attempts', attempts === 2);
}

// Test 6: read_retry exhausted
{
    class AlwaysBad { read() { return Buffer.from([0x35, 0x00, 0x18, 0x04, 0x00]); } }
    const dht = new DHT11Full(new AlwaysBad(), 2);
    let err = null;
    try { dht.readRetry(); } catch (e) { err = e; }
    checkTrue('read_retry_exhausted', err instanceof DHT11Error);
}

// Test 7: read_raw
{
    const mock = new MockTransport(Buffer.from([0x35, 0x00, 0x18, 0x04, 0x51]));
    const dht = new DHT11Full(mock, 3);
    const raw = dht.readRaw();
    checkTrue('read_raw.first_byte', raw[0] === 0x35);
    checkTrue('read_raw.checksum',   raw[4] === 0x51);
}

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
