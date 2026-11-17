'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AHT20Full } = require('../../packages/periph/src/chips/environmental/aht20');

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

function _crc8Of(data) {
    let crc = 0xFF;
    for (const b of data) {
        crc ^= b;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

// 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
// rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4       = 0x80000 (524288 / 2**20 * 100 = 50.0)
// rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]   = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
const MEASUREMENT = [0x08, 0x80, 0x00, 0x08, 0x00, 0x00];

async function main() {
    const connection = new I2CConnectionMock();

    // Status 0x18 (idle + calibrated): no reset or calibration init.
    connection.queueRead([0x18]);

    const sensor = new AHT20Full(connection);
    await sensor._ready;
    checkTrue('init_reads_status', writesContain(connection, [0x71]));
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

    connection.queueRead(MEASUREMENT);
    const raw = await sensor.readRaw();
    checkTrue('read_raw', raw.raw_humidity === 0x80000 && raw.raw_temperature === 0x80000);

    // BUSY on the first read, cleared on the second.
    connection.queueRead([0x88, 0x80, 0x00, 0x08, 0x00, 0x00]);
    connection.queueRead(MEASUREMENT);
    checkTrue('read_polls_busy', (await sensor.read()).temperature_c === 50.0);

    // Spec example: raw_t = 0x6B300 -> 33.74 degC
    connection.queueRead([0x08, 0x80, 0x00, 0x06, 0xB3, 0x00]);
    checkTrue('read_temperature_example', Math.abs((await sensor.readTemperature()) - 33.74) < 0.01);

    connection.queueRead(MEASUREMENT);
    checkTrue('dew_point', Math.abs((await sensor.dewPoint()) - 36.7) < 0.1);

    connection.queueRead([0x08]);
    checkTrue('read_status', (await sensor.readStatus()) === 0x08);

    // CRC-8 (poly 0x31, init 0xFF); Sensirion-style vector 0xBEEF -> 0x92.
    const frame = [0x1C, 0x80, 0x00, 0x08, 0x00, 0x00];
    const crc = _crc8Of(frame);
    connection.queueRead(frame.concat([crc]));
    let res = await sensor.readWithCrc();
    checkTrue('read_with_crc_ok', res.crc_ok === true && res.humidity_pct === 50.0 && res.temperature_c === 50.0);
    connection.queueRead(frame.concat([crc ^ 0xFF]));
    checkTrue('read_with_crc_bad', (await sensor.readWithCrc()).crc_ok === false);
    checkTrue('crc8_known_vector', _crc8Of([0xBE, 0xEF]) === 0x92);

    // Soft reset re-runs the calibration check (status 0x18: no calibration init).
    connection.queueRead([0x18]);
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

    // Uncalibrated at power-on: soft reset fixes it, no calibration init.
    const c1 = new I2CConnectionMock();
    c1.queueRead([0x00]);
    c1.queueRead([0x18]);
    const s1 = new AHT20Full(c1);
    await s1._ready;
    checkTrue('init_reset', writesContain(c1, [0xBA]) && !writesContain(c1, [0x1B, 0x00, 0x00]));

    // Still uncalibrated after the reset: calibration registers are written.
    const c4 = new I2CConnectionMock();
    c4.queueRead([0x00]);
    c4.queueRead([0x00]);
    c4.queueRead([0x18]);
    const s4 = new AHT20Full(c4);
    await s4._ready;
    checkTrue('init_cal_init', writesContain(c4, [0x1B, 0x00, 0x00]) && writesContain(c4, [0x1C, 0x00, 0x00])
        && writesContain(c4, [0x1E, 0x00, 0x00]));

    // Still uncalibrated after calibration init: init rejects.
    const c2 = new I2CConnectionMock();
    c2.queueRead([0x00]);
    c2.queueRead([0x00]);
    c2.queueRead([0x00]);
    const s2 = new AHT20Full(c2);
    let threw = false;
    try { await s2.read(); } catch (e) { threw = true; }
    checkTrue('init_uncalibrated_rejects', threw);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
