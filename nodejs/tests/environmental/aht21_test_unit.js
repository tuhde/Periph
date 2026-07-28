'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AHT21Full } = require('../../packages/periph/src/chips/environmental/aht21');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function writesContain(connection, expected) {
    return connection.writes.some((w) => w.equals(Buffer.from(expected)));
}

// 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
// rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4       = 0x80000 (524288 / 2**20 * 100 = 50.0)
// rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]   = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
const MEASUREMENT = [0x00, 0x80, 0x00, 0x08, 0x00, 0x00];

async function main() {
    const connection = new I2CConnectionMock();

    // Status byte with both CAL (0x08) and the paired 0x10 bit set, so the
    // (status & 0x18) !== 0x18 check in _init() passes and no soft-reset
    // /recalibration path runs.
    connection.queueRead([0x18]);

    const sensor = new AHT21Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish before asserting on it
    checkTrue('init_no_reset', !writesContain(connection, [0xBA]));

    connection.queueRead(MEASUREMENT);
    const data = await sensor.read();
    checkTrue('read_triggers', writesContain(connection, [0xAC, 0x33, 0x00]));
    checkTrue('read_humidity_value', data.humidity_pct === 50.0);
    checkTrue('read_temperature_value', data.temperature_c === 50.0);

    connection.queueRead(MEASUREMENT);
    checkTrue('read_temperature', (await sensor.readTemperature()) === 50.0);

    connection.queueRead(MEASUREMENT);
    checkTrue('read_humidity', (await sensor.readHumidity()) === 50.0);

    // read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer.
    connection.queueRead([...MEASUREMENT, 0x8B]);
    const withCrc = await sensor.readWithCrc();
    checkTrue('read_with_crc_values', withCrc.temperature_c === 50.0 && withCrc.humidity_pct === 50.0);
    checkTrue('read_with_crc_ok', withCrc.crc_ok === true);

    connection.queueRead([...MEASUREMENT, 0x00]);
    checkTrue('read_with_crc_detects_bad_crc', (await sensor.readWithCrc()).crc_ok === false);

    await sensor.softReset();
    checkTrue('soft_reset', writesContain(connection, [0xBA]));

    connection.queueRead([0x08]);
    checkTrue('is_calibrated_true', (await sensor.isCalibrated()) === true);

    connection.queueRead([0x00]);
    checkTrue('is_calibrated_false', (await sensor.isCalibrated()) === false);

    connection.queueRead([0x80]);
    checkTrue('is_busy_true', (await sensor.isBusy()) === true);

    connection.queueRead([0x00]);
    checkTrue('is_busy_false', (await sensor.isBusy()) === false);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
