'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADS1113Minimal, ADS1113Full } = require('../../packages/periph/src/chips/adc_dac/ads1113');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function checkRejects(label, fn) {
    try {
        await fn();
        console.log(`FAIL ${label}: did not reject`); failed++;
    } catch (e) {
        checkTrue(label, e instanceof RangeError);
    }
}

// Word-addressed variant of the byte-slot mock: the four ADS111x registers are
// 16 bits wide at consecutive pointer values, which would overlap in the
// shared mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock extends I2CConnectionMock {
    constructor() {
        super();
        this.words = new Map([[0, 0x0000], [1, 0x8583], [2, 0x8000], [3, 0x7FFF]]);
        this.intPin = null;
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
const lastConfig = (conn) => configWrites(conn).slice(-1)[0];

class FakePin {
    constructor(high) { this.high = high; }
    async isHigh() { return this.high; }
    async onEdge() {}
    async offEdge() {}
}

async function main() {
    const mock = new WordMock();
    const a13 = new ADS1113Minimal(mock);
    mock.words.set(0, 0x0200);
    checkTrue('ads1113_read_voltage', (await a13.readVoltage()) === 0x0200 * 0.0000625);
    const full = new ADS1113Full(mock);
    checkTrue('ads1113_fixed_range', full.fullScale() === 2.048 && full.lsb() === 2.048 / 32768);
    checkTrue('ads1113_no_gain_or_comparator',
        full.setGain === undefined && full.configureComparator === undefined && full.pollInterrupt === undefined);
    await full.setDataRate(ADS1113Full.DR_8);
    await full.startContinuous();
    checkTrue('ads1113_continuous_config', lastConfig(mock) === 0x0403);
    await full.reset();
    checkTrue('ads1113_reset', mock.words.get(1) === 0x8583 && full.dataRateSps() === 128);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
