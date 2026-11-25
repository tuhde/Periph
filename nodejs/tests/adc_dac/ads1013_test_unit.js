'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADS1013Minimal, ADS1013Full } = require('../../packages/periph/src/chips/adc_dac/ads1013');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

async function checkRejects(label, fn) {
    try {
        await fn();
        console.log(`FAIL ${label}: did not reject`); failed++;
    } catch (e) {
        checkTrue(label, e instanceof RangeError);
    }
}

// Word-addressed variant of the byte-slot mock (16-bit registers at
// consecutive pointers would overlap in the byte-slot model).
class WordMock extends I2CConnectionMock {
    constructor() {
        super();
        this.words = new Map([[0, 0x0000], [1, 0x8583]]);
    }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        if (buf.length === 3) this.words.set(buf[0], (buf[1] << 8) | buf[2]);
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        const word = this.words.get(buf[0]) || 0;
        return Buffer.from([(word >> 8) & 0xFF, word & 0xFF]);
    }
}

function configWrites(conn) {
    return conn.writes.filter((b) => b.length === 3 && b[0] === 1).map((b) => (b[1] << 8) | b[2]);
}

async function main() {
    {
        const conn = new WordMock();
        const adc = new ADS1013Minimal(conn);
        checkEq('init_no_traffic', conn.writes.length, 0);
        conn.words.set(0, 0x7FF0);
        checkEq('read_raw_max', await adc.readRaw(), 2047);
        checkEq('config_word_default', configWrites(conn).pop(), 0x8583);
        conn.words.set(0, 0x8000);
        checkEq('read_raw_min', await adc.readRaw(), -2048);
        conn.words.set(0, 0xFFF0);
        checkEq('read_raw_minus_one', await adc.readRaw(), -1);
        conn.words.set(0, 0x3E80);
        checkTrue('read_voltage_1v', Math.abs((await adc.readVoltage()) - 1.0) < 1e-9);
    }

    {
        const conn = new WordMock();
        const adc = new ADS1013Full(conn);
        checkEq('full_scale', adc.fullScale(), 2.048);
        checkEq('lsb', adc.lsb(), 0.001);
        await adc.setDataRate(ADS1013Full.DR_128);
        checkTrue('dr_sps_128', adc.dataRateSps() === 128 && adc.getDataRate() === 0);
        await adc.setDataRate(ADS1013Full.DR_3300);
        checkTrue('dr_sps_3300', adc.dataRateSps() === 3300 && adc.getDataRate() === 6);
        conn.words.set(0, 0x1000);
        await adc.readRaw();
        checkEq('config_word_dr', configWrites(conn).pop(), 0x8000 | 0x0403 | 0x0100 | (6 << 5));
        for (const bad of [-1, 7]) await checkRejects('dr_rejects_' + bad, () => adc.setDataRate(bad));
    }

    {
        const conn = new WordMock();
        const adc = new ADS1013Full(conn);
        await adc.startContinuous();
        checkEq('continuous_config', configWrites(conn).pop(), 0x0483);
        conn.words.set(0, 0x1000);
        conn.writes.length = 0;
        checkEq('continuous_raw', await adc.readContinuousRaw(), 256);
        checkEq('continuous_no_config_write', configWrites(conn).length, 0);
        checkTrue('continuous_volts', Math.abs((await adc.readContinuous()) - 0.256) < 1e-9);
        await adc.stopContinuous();
        checkEq('stop_continuous_config', configWrites(conn).pop(), 0x0583);
        conn.words.set(1, 0x0583);
        checkTrue('is_converting_true', await adc.isConverting());
        conn.words.set(1, 0x8583);
        checkTrue('is_converting_false', !(await adc.isConverting()));
        checkEq('read_config', await adc.readConfig(), 0x8583);
        await adc.setDataRate(ADS1013Full.DR_128);
        await adc.reset();
        checkTrue('reset_config', conn.words.get(1) === 0x8583 && adc.getDataRate() === 4);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
