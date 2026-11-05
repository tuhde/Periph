'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADS1115Minimal, ADS1115Full } = require('../../packages/periph/src/chips/adc_dac/ads1115');

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
    {
        const mock = new WordMock();
        const adc = new ADS1115Minimal(mock);
        checkTrue('init_no_traffic', mock.writes.length === 0);
        mock.words.set(0, 0x4000);
        checkTrue('read_raw_value', (await adc.readRaw()) === 0x4000);
        checkTrue('read_raw_config_word', lastConfig(mock) === 0xC583);
        mock.words.set(0, 0xFFFF);
        checkTrue('read_raw_negative', (await adc.readRaw(2)) === -1);
        checkTrue('read_raw_ch2_mux', lastConfig(mock) === 0xE583);
        await adc.readRaw(9);
        checkTrue('read_raw_clamps_channel', lastConfig(mock) === 0xF583);
        mock.words.set(0, 0x4000);
        checkTrue('read_voltage', (await adc.readVoltage()) === 0x4000 * 0.0000625);
    }
    {
        const mock = new WordMock();
        const full = new ADS1115Full(mock);
        await full.setGain(ADS1115Full.PGA_4_096);
        checkTrue('gain_scale', full.fullScale() === 4.096 && full.lsb() === 4.096 / 32768);
        mock.words.set(0, 0x2000);
        checkTrue('read_voltage_uses_gain', (await full.readVoltage(1)) === 0x2000 * 4.096 / 32768);
        checkTrue('gain_in_config', lastConfig(mock) === 0xD383);
        await checkRejects('gain_rejects_6', () => full.setGain(6));
        await full.setDataRate(ADS1115Full.DR_860);
        checkTrue('data_rate_sps', full.dataRateSps() === 860 && full.getDataRate() === 7);
        await full.readRaw();
        checkTrue('data_rate_in_config', lastConfig(mock) === 0xC3E3);
        checkTrue('differential_raw', (await full.readDifferentialRaw(ADS1115Full.DIFF_2_3)) === 0x2000);
        checkTrue('differential_mux', lastConfig(mock) === 0xB3E3);
        await checkRejects('differential_rejects', () => full.readDifferentialRaw(4));
        await full.startContinuous(ADS1115Full.MUX_AIN1_GND);
        checkTrue('continuous_config', lastConfig(mock) === 0x52E3);
        mock.words.set(0, 0x0100);
        checkTrue('continuous_read', (await full.readContinuousRaw()) === 256);
        await full.stopContinuous();
        checkTrue('stop_continuous', lastConfig(mock) === 0x53E3);
        mock.words.set(1, 0x0583);
        checkTrue('is_converting', await full.isConverting());
        mock.words.set(1, 0x8583);
        checkTrue('not_converting', !(await full.isConverting()));
        checkTrue('read_config', (await full.readConfig()) === 0x8583);
    }
    {
        const mock = new WordMock();
        const full = new ADS1115Full(mock);
        await full.setThresholds(0.5, 3.0);
        checkTrue('thresholds_raw_low', mock.words.get(2) === 8000);
        checkTrue('thresholds_raw_high_clamped', mock.words.get(3) === 0x7FFF);
        await full.setHighThresholdRaw(-100);
        checkTrue('negative_threshold_encoding', mock.words.get(3) === 0xFF9C);
        await full.setLowThresholdRaw(-200);
        const [lo, hi] = await full.getThresholdsRaw();
        checkTrue('get_thresholds_raw', lo === -200 && hi === -100);
        await checkRejects('thresholds_reject_inverted', () => full.setThresholds(1.0, 1.0));
        await full.setThresholds(0.5, 1.5);
        await full.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_HIGH, true, ADS1115Full.QUEUE_2);
        checkTrue('comparator_bits', lastConfig(mock) === (0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0001));
        await full.disableComparator();
        checkTrue('disable_comparator', (lastConfig(mock) & 3) === 3);
        mock.writes.length = 0;
        await full.clearAlert();
        checkTrue('clear_alert_reads_conversion', mock.writes.length === 1 && mock.writes[0][0] === 0);
    }
    {
        const mock = new WordMock();
        const full = new ADS1115Full(mock);
        await full.enableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY);
        checkTrue('ready_thresholds', mock.words.get(3) === 0x8000 && mock.words.get(2) === 0x0000);
        checkTrue('ready_queue', (lastConfig(mock) & 3) === 0);
        mock.words.set(1, 0x8583);
        checkTrue('poll_ready_fallback', (await full.pollInterrupt()) === ADS1115Full.SOURCE_CONVERSION_READY);
        mock.words.set(1, 0x0583);
        checkTrue('poll_ready_idle', (await full.pollInterrupt()) === 0);
        mock.intPin = new FakePin(false);
        checkTrue('poll_pin_asserted_low', (await full.pollInterrupt()) === ADS1115Full.SOURCE_CONVERSION_READY);
        mock.intPin.high = true;
        checkTrue('poll_pin_deasserted', (await full.pollInterrupt()) === 0);
        await full.disableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY);
        checkTrue('disable_interrupt', (lastConfig(mock) & 3) === 3);
        checkTrue('poll_disabled', (await full.pollInterrupt()) === 0);
        await full.setThresholds(0.5, 1.5);
        await full.enableInterrupt(ADS1115Full.SOURCE_COMPARATOR);
        checkTrue('enable_comparator', (lastConfig(mock) & 3) === 0);
        mock.intPin.high = false;
        checkTrue('poll_comparator_asserted', (await full.pollInterrupt()) === ADS1115Full.SOURCE_COMPARATOR);
        await full.setGain(0);
        await full.reset();
        checkTrue('reset_writes', mock.words.get(1) === 0x8583 && mock.words.get(2) === 0x8000 && mock.words.get(3) === 0x7FFF);
        checkTrue('reset_clears_cache', full.getGain() === 2 && full.getDataRate() === 4);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
