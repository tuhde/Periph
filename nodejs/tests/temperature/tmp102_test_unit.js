'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { TMP102Minimal, TMP102Full } = require('../../packages/periph/src/chips/temperature/tmp102');

const _REG_TEMP   = 0x00;
const _REG_CONFIG = 0x01;
const _REG_TLOW   = 0x02;
const _REG_THIGH  = 0x03;

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

async function checkRejects(label, fn, type) {
    try {
        await fn();
        console.log(`FAIL ${label}: did not reject`); failed++;
    } catch (e) {
        checkTrue(label, !type || e instanceof type);
    }
}

// Word-addressed variant of the byte-slot mock: TMP102 registers are 16 bits
// wide at consecutive pointer values, which would overlap in the shared
// mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock extends I2CConnectionMock {
    constructor() {
        super();
        this.words = new Map([[0x00, 0x0000], [0x01, 0x60A0], [0x02, 0x4B00], [0x03, 0x5000]]);
        this.osDone = true;
    }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        if (buf.length === 3) this.words.set(buf[0], (buf[1] << 8) | buf[2]);
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        let word = this.words.get(buf[0]) || 0;
        if (buf[0] === 0x01) word = this.osDone ? (word | 0x8000) : (word & 0x7FFF);
        return Buffer.from([(word >> 8) & 0xFF, word & 0xFF]);
    }
}

async function main() {
    const conn = new WordMock();
    const sensor = new TMP102Minimal(conn);
    checkTrue('init_no_bus_access', conn.writes.length === 0);

    conn.words.set(_REG_TEMP, 0x1900);
    checkEq('temp_25c', await sensor.readTemperature(), 25.0);
    conn.words.set(_REG_TEMP, 0xE700);
    checkEq('temp_minus25c', await sensor.readTemperature(), -25.0);
    conn.words.set(_REG_TEMP, 0x7FF0);
    checkEq('temp_max', await sensor.readTemperature(), 127.9375);
    conn.words.set(_REG_TEMP, 0xC900);
    checkEq('temp_min', await sensor.readTemperature(), -55.0);
    conn.words.set(_REG_TEMP, (2400 << 3) | 1);
    checkEq('temp_extended_150c', await sensor.readTemperature(), 150.0);
    conn.words.set(_REG_TEMP, ((-1600 << 3) & 0xFFFF) | 1);
    checkEq('temp_extended_negative', await sensor.readTemperature(), -100.0);

    const m2 = new WordMock();
    const full = new TMP102Full(m2);
    m2.words.set(_REG_TEMP, 0x1900);
    checkEq('raw_count', await full.readRaw(), 400);
    checkEq('read_config_default', await full.readConfig(), 0x60A0 | 0x8000);

    await full.setConversionRate(8);
    checkEq('rate_8hz_bits', m2.words.get(_REG_CONFIG) & 0x00C0, 0x00C0);
    checkEq('rate_preserves_reserved', m2.words.get(_REG_CONFIG) & 0x6000, 0x6000);
    await full.setConversionRate(0.25);
    checkEq('rate_025hz_bits', m2.words.get(_REG_CONFIG) & 0x00C0, 0x0000);
    await checkRejects('rate_invalid_rejects', () => full.setConversionRate(2), RangeError);

    await full.setFaultQueue(6);
    checkEq('fault_queue_6', m2.words.get(_REG_CONFIG) & 0x1800, 0x1800);
    await full.setFaultQueue(2);
    checkEq('fault_queue_2', m2.words.get(_REG_CONFIG) & 0x1800, 0x0800);
    await checkRejects('fault_queue_invalid_rejects', () => full.setFaultQueue(3), RangeError);

    await full.setPolarity(true);
    checkTrue('polarity_high', (m2.words.get(_REG_CONFIG) & 0x0400) !== 0);
    await full.setPolarity(false);
    checkTrue('polarity_low', (m2.words.get(_REG_CONFIG) & 0x0400) === 0);
    await full.setThermostatMode(true);
    checkTrue('tm_interrupt', (m2.words.get(_REG_CONFIG) & 0x0200) !== 0);
    await full.setThermostatMode(false);
    checkTrue('tm_comparator', (m2.words.get(_REG_CONFIG) & 0x0200) === 0);
    await full.setExtendedMode(true);
    checkTrue('em_on', (m2.words.get(_REG_CONFIG) & 0x0010) !== 0);
    await full.setExtendedMode(false);
    checkTrue('em_off', (m2.words.get(_REG_CONFIG) & 0x0010) === 0);
    await full.shutdown();
    checkTrue('shutdown_sd', (m2.words.get(_REG_CONFIG) & 0x0100) !== 0);
    await full.wake();
    checkTrue('wake_sd_clear', (m2.words.get(_REG_CONFIG) & 0x0100) === 0);
    await full.writeConfig(0xFFFF);
    checkEq('write_config_masks_readonly', m2.words.get(_REG_CONFIG) & 0x602F, 0x6000);

    const m3 = new WordMock();
    const lim = new TMP102Full(m3);
    await lim.setTHigh(30.0);
    checkEq('t_high_encoding', m3.words.get(_REG_THIGH), 0x1E00);
    checkEq('t_high_roundtrip', await lim.getTHigh(), 30.0);
    await lim.setTLow(-10.5);
    checkEq('t_low_negative_roundtrip', await lim.getTLow(), -10.5);
    await lim.setTHigh(500.0);
    checkEq('t_high_12bit_clamp', await lim.getTHigh(), 127.9375);
    await lim.setExtendedMode(true);
    await lim.setTHigh(140.0);
    checkEq('t_high_extended_encoding', m3.words.get(_REG_THIGH), 2240 << 3);
    checkEq('t_high_extended_roundtrip', await lim.getTHigh(), 140.0);
    await lim.setTHigh(1000.0);
    checkEq('t_high_clamped_150', await lim.getTHigh(), 150.0);

    const m4 = new WordMock();
    const os = new TMP102Full(m4);
    m4.words.set(_REG_TEMP, 0x1900);
    checkEq('one_shot_value', await os.oneShot(), 25.0);
    checkTrue('one_shot_sets_os', m4.writes.some((w) => w.length === 3 && w[0] === _REG_CONFIG && (w[1] & 0x80)));
    checkEq('one_shot_restores_running', m4.words.get(_REG_CONFIG) & 0x0100, 0);
    m4.words.set(_REG_CONFIG, 0x6180);
    await os.oneShot();
    checkEq('one_shot_stays_shutdown', m4.words.get(_REG_CONFIG) & 0x0100, 0x0100);
    m4.osDone = false;
    await checkRejects('one_shot_timeout', () => os.oneShot());

    const m5 = new WordMock();
    const al = new TMP102Full(m5);
    m5.words.set(_REG_CONFIG, 0x6000 | 0x0020);
    checkEq('alert_state_inactive_pol0', await al.alertState(), false);
    m5.words.set(_REG_CONFIG, 0x6000);
    checkEq('alert_state_active_pol0', await al.alertState(), true);
    m5.words.set(_REG_CONFIG, 0x6400 | 0x0020);
    checkEq('alert_state_active_pol1', await al.alertState(), true);
    m5.words.set(_REG_CONFIG, 0x6400);
    checkEq('alert_state_inactive_pol1', await al.alertState(), false);
    m5.writes.length = 0;
    m5.words.set(_REG_CONFIG, 0x6000);
    checkEq('poll_interrupt_true', await al.pollInterrupt(), true);
    checkTrue('poll_interrupt_reads_temperature', m5.writes.some((w) => w.length === 1 && w[0] === _REG_TEMP));

    m5.writes.length = 0;
    await al.reset();
    checkTrue('reset_general_call', m5.writes.length === 1 && m5.writes[0].equals(Buffer.from([0x00, 0x06])));

    // Polling fallback reports a temperature while the alert is active.
    const m6 = new WordMock();
    const poll = new TMP102Full(m6);
    m6.words.set(_REG_TEMP, 0x1900);
    m6.words.set(_REG_CONFIG, 0x6000);
    const calls = [];
    await poll.onInterrupt((t) => calls.push(t));
    await new Promise((r) => setTimeout(r, 1100));
    checkTrue('on_interrupt_polling_reports', calls.length >= 1 && calls[0] === 25.0);
    await poll.offInterrupt();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
