'use strict';
const { DHTxxConnectionMock } = require('../../packages/periph/src/connection/dhtxx_mock');
const { DHT11Minimal, DHT11Full, DHT11Error } = require('../../packages/periph/src/chips/humidity/dht11');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function closeEnough(a, b, eps = 0.001) {
    return Math.abs(a - b) < eps;
}

// --- DHT11Minimal: decoding ---

let connection = new DHTxxConnectionMock();
connection.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]);
let sensor = new DHT11Minimal(connection);
let { temperature, humidity } = sensor.read();
checkTrue('decode_datasheet_example', closeEnough(temperature, 24.4) && closeEnough(humidity, 53.0));

connection.queueRead([0x20, 0x00, 0x0A, 0x81, 0xAB]);
({ temperature, humidity } = sensor.read());
checkTrue('decode_negative_temperature', closeEnough(temperature, -10.1) && closeEnough(humidity, 32.0));

// --- Checksum validation ---

connection.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]); // bad checksum
let threw = false;
try {
    sensor.read();
} catch (e) {
    threw = e instanceof DHT11Error;
}
checkTrue('checksum_error_throws', threw);

// --- Frame length validation ---

connection.queueRead([0x35, 0x00, 0x18]); // too short
threw = false;
try {
    sensor.read();
} catch (e) {
    threw = e instanceof DHT11Error;
}
checkTrue('short_frame_throws', threw);

// --- DHT11Full: convenience accessors ---

let connection2 = new DHTxxConnectionMock();
connection2.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]);
let full = new DHT11Full(connection2, 3);
checkTrue('read_temperature', closeEnough(full.readTemperature(), 24.4));

connection2.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]);
checkTrue('read_humidity', closeEnough(full.readHumidity(), 53.0));

// --- readRaw(): unprocessed frame, still checksum-validated ---

connection2.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]);
let raw = full.readRaw();
checkTrue('read_raw_returns_frame', Buffer.compare(raw, Buffer.from([0x35, 0x00, 0x18, 0x04, 0x51])) === 0);

connection2.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]); // bad checksum
threw = false;
try {
    full.readRaw();
} catch (e) {
    threw = e instanceof DHT11Error;
}
checkTrue('read_raw_checksum_error_throws', threw);

// --- readRetry(): the driver's `catch (e)` is a BARE catch — it is not
// scoped to DHT11Error, so it retries on ANY thrown error, checksum or
// transport-level alike (unlike Python's checksum-only DHT11Error catch
// scope — see dht11.js's readRetry: `try { return this.read(); } catch (e)
// { lastErr = e; }`).

let connection3 = new DHTxxConnectionMock();
connection3.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]); // bad checksum, attempt 1
connection3.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]); // good, attempt 2
let full3 = new DHT11Full(connection3, 3);
({ temperature, humidity } = full3.readRetry());
checkTrue('read_retry_succeeds', closeEnough(temperature, 24.4) && closeEnough(humidity, 53.0));

let connection4 = new DHTxxConnectionMock();
connection4.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]);
connection4.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]);
let full4 = new DHT11Full(connection4, 2);
threw = false;
try {
    full4.readRetry();
} catch (e) {
    threw = e instanceof DHT11Error;
}
checkTrue('read_retry_exhausted', threw);

let connection5 = new DHTxxConnectionMock();
connection5.queueError(new Error('sensor timeout')); // transport-level error, attempt 1
connection5.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]); // good, attempt 2
let full5 = new DHT11Full(connection5, 3);
({ temperature, humidity } = full5.readRetry());
checkTrue('read_retry_recovers_from_transport_error', closeEnough(temperature, 24.4) && closeEnough(humidity, 53.0));

// --- readRetry() with no explicit count (undefined) falls back to the constructor's default ---

let connection6 = new DHTxxConnectionMock();
connection6.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]);
connection6.queueRead([0x35, 0x00, 0x18, 0x04, 0x00]);
connection6.queueRead([0x35, 0x00, 0x18, 0x04, 0x51]); // 3rd attempt succeeds
let full6 = new DHT11Full(connection6, 3);
({ temperature } = full6.readRetry()); // no explicit maxRetries -> uses constructor's 3
checkTrue('read_retry_default_uses_constructor_value', closeEnough(temperature, 24.4));

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
