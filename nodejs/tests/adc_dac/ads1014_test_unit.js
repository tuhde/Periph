'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADS1014Minimal, ADS1014Full } = require('../../packages/periph/src/chips/adc_dac/ads1014');

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

// Word-addressed variant of the byte-slot mock: the ADS1014 registers are 16
// bits wide at consecutive pointer values, which would overlap in the shared
// mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock extends I2CConnectionMock {
    constructor() {
        super();
        this.words = new Map([[0, 0x0000], [1, 0x8583], [2, 0x8000], [3, 0x7FFF]]);
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

class FakePin {
    constructor() { this.high = true; this.handler = null; this.trigger = null; }
    isHigh() { return this.high; }
    async onEdge(h, t) { this.handler = h; this.trigger = t; }
    async offEdge(h) { if (this.handler === h) this.handler = null; }
}

async function main() {
    // -- Minimal: init and single-shot ---------------------------------------
    {
        const conn = new WordMock();
        const adc = new ADS1014Minimal(conn);
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

    // -- Full: gain and data rate --------------------------------------------
    {
        const conn = new WordMock();
        const adc = new ADS1014Full(conn);
        await adc.setGain(ADS1014Full.PGA_6_144);
        checkEq('gain_get', adc.getGain(), 0);
        checkEq('full_scale_6_144', adc.fullScale(), 6.144);
        checkTrue('lsb_6_144', Math.abs(adc.lsb() - 0.003) < 1e-12);
        await adc.setGain(ADS1014Full.PGA_0_256);
        checkTrue('lsb_0_256', Math.abs(adc.lsb() - 0.000125) < 1e-12);
        conn.words.set(0, 0x4000);
        checkTrue('read_voltage_gain', Math.abs((await adc.readVoltage()) - 0.256 * 0x400 / 2048) < 1e-9);
        checkEq('config_gain_bits', configWrites(conn).pop(), 0x8000 | (5 << 9) | 0x0100 | (4 << 5) | 0x3);
        for (const bad of [-1, 6, 7]) await checkRejects('gain_rejects_' + bad, () => adc.setGain(bad));

        await adc.setDataRate(ADS1014Full.DR_128);
        checkEq('dr_sps_128', adc.dataRateSps(), 128);
        await adc.setDataRate(ADS1014Full.DR_3300);
        checkTrue('dr_sps_3300', adc.dataRateSps() === 3300 && adc.getDataRate() === 6);
        await checkRejects('dr_rejects_7', () => adc.setDataRate(7));
    }

    // -- Continuous mode -----------------------------------------------------
    {
        const conn = new WordMock();
        const adc = new ADS1014Full(conn);
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
    }

    // -- Comparator and thresholds -------------------------------------------
    {
        const conn = new WordMock();
        const adc = new ADS1014Full(conn);
        await adc.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_HIGH, true, ADS1014Full.QUEUE_4);
        checkEq('comparator_config', configWrites(conn).pop(), (0x0583 & ~0x3F) | 0x10 | 0x08 | 0x04 | 0x02);
        await adc.disableComparator();
        checkEq('comparator_disabled', configWrites(conn).pop() & 0x3, 3);
        await checkRejects('queue_rejects_3', () => adc.configureComparator(0, 0, false, 3));

        await adc.setThresholds(-0.5, 1.0);
        checkEq('threshold_low_word', conn.words.get(2), (-500 & 0xFFF) << 4);
        checkEq('threshold_high_word', conn.words.get(3), 1000 << 4);
        checkTrue('threshold_readback', (await adc.getThresholdsRaw()).join() === '-500,1000');
        await adc.setThresholds(-10, 10);
        checkTrue('threshold_clamp', (await adc.getThresholdsRaw()).join() === '-2048,2047');
        await checkRejects('threshold_rejects_equal', () => adc.setThresholds(1, 1));
        await checkRejects('threshold_raw_rejects_range', () => adc.setHighThresholdRaw(2048));

        conn.writes.length = 0;
        await adc.clearAlert();
        checkTrue('clear_alert_reads_conversion', conn.writes.length === 1 && conn.writes[0].equals(Buffer.from([0])));

        await adc.reset();
        checkEq('reset_config', conn.words.get(1), 0x8583);
        checkTrue('reset_thresholds', conn.words.get(2) === 0x8000 && conn.words.get(3) === 0x7FFF);
    }

    // -- Interrupt routing ---------------------------------------------------
    {
        const conn = new WordMock();
        const pin = new FakePin();
        conn.intPin = pin;
        const adc = new ADS1014Full(conn);
        await adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);
        checkTrue('ready_thresholds', conn.words.get(3) === 0x8000 && conn.words.get(2) === 0x0000);
        checkEq('ready_que_00', conn.words.get(1) & 0x3, 0);
        checkEq('poll_idle', await adc.pollInterrupt(), 0);
        pin.high = false;
        checkEq('poll_ready', await adc.pollInterrupt(), ADS1014Full.SOURCE_CONVERSION_READY);

        await adc.setHighThresholdRaw(-5);
        await adc.setLowThresholdRaw(10);
        await checkRejects('comparator_needs_thresholds', () => adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR));
        await adc.setThresholds(-0.1, 0.1);
        await adc.configureComparator(ADS1014Full.COMP_TRADITIONAL, ADS1014Full.ACTIVE_LOW, true);
        await adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR);
        checkTrue('comparator_thresholds_restored', (await adc.getThresholdsRaw()).join() === '-100,100');
        conn.writes.length = 0;
        checkEq('poll_comparator', await adc.pollInterrupt(), ADS1014Full.SOURCE_COMPARATOR);
        checkTrue('poll_latched_reads_conversion', conn.writes.some((b) => b.length === 1 && b[0] === 0));

        await adc.disableInterrupt(ADS1014Full.SOURCE_COMPARATOR);
        checkEq('disable_que_11', conn.words.get(1) & 0x3, 3);
        checkEq('poll_disabled', await adc.pollInterrupt(), 0);

        const events = [];
        await adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR);
        await adc.onInterrupt((s) => events.push(s));
        checkTrue('on_interrupt_registers_edge', pin.handler !== null && pin.trigger === 'falling');
        await pin.handler();
        checkTrue('on_interrupt_callback', events.length === 1 && events[0] === ADS1014Full.SOURCE_COMPARATOR);
        await adc.offInterrupt();
        checkTrue('off_interrupt', pin.handler === null);
    }

    // -- No pin: conversion-ready falls back to CONFIG.OS, comparator reads 0 --
    {
        const conn = new WordMock();
        const adc = new ADS1014Full(conn);
        await adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);
        conn.words.set(1, conn.words.get(1) | 0x8000);  // the real chip reads OS = 1 when idle
        checkEq('poll_fallback_ready', await adc.pollInterrupt(), ADS1014Full.SOURCE_CONVERSION_READY);
        conn.words.set(1, conn.words.get(1) & 0x7FFF);
        checkEq('poll_fallback_busy', await adc.pollInterrupt(), 0);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
