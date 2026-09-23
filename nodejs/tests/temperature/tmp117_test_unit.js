'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { TMP117Minimal, TMP117Full } = require('../../packages/periph/src/chips/temperature/tmp117');

const _REG_TEMP      = 0x00;
const _REG_CONFIG    = 0x01;
const _REG_THIGH     = 0x02;
const _REG_TLOW      = 0x03;
const _REG_EEPROM_UL = 0x04;
const _REG_EEPROM1   = 0x05;
const _REG_EEPROM2   = 0x06;
const _REG_OFFSET    = 0x07;
const _REG_EEPROM3   = 0x08;

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

// Word-addressed variant of the byte-slot mock: TMP117 registers are 16 bits
// wide at consecutive pointer values, which would overlap in the shared
// mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock extends I2CConnectionMock {
    constructor() {
        super();
        this.words = new Map([[0x00, 0x8000], [0x01, 0x0220], [0x02, 0x6000], [0x03, 0x8000], [0x0F, 0x1117]]);
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

function writesTo(conn, reg) {
    return conn.writes.filter((b) => b.length >= 2 && b[0] === reg);
}

async function main() {
    // -- Identity check --------------------------------------------------
    const conn = new WordMock();
    const sensor = new TMP117Minimal(conn);
    await sensor.init();
    checkTrue('init_reads_device_id_only', conn.writes.length === 1 && conn.writes[0].length === 1 &&
        conn.writes[0][0] === 0x0F);
    const bad = new WordMock();
    bad.words.set(0x0F, 0x0118);
    await checkRejects('init_rejects_wrong_device_id', () => new TMP117Minimal(bad).init());
    const badRead = new WordMock();
    badRead.words.set(0x0F, 0x0000);
    await checkRejects('read_rejects_wrong_device_id', () => new TMP117Minimal(badRead).readTemperature());
    const rev = new WordMock();
    rev.words.set(0x0F, 0x2117);
    await new TMP117Minimal(rev).init();
    checkTrue('init_ignores_revision', true);

    // -- Temperature decoding -------------------------------------------
    conn.words.set(_REG_TEMP, 0x0C80);
    checkEq('temperature_positive', await sensor.readTemperature(), 25.0);
    conn.words.set(_REG_TEMP, 0xFFFF);
    checkEq('temperature_minus_lsb', await sensor.readTemperature(), -0.0078125);
    conn.words.set(_REG_TEMP, 0xF380);
    checkEq('temperature_negative', await sensor.readTemperature(), -25.0);
    conn.words.set(_REG_TEMP, 0x8000);
    checkEq('temperature_power_up_sentinel', await sensor.readTemperature(), -256.0);
    conn.words.set(_REG_TEMP, 0x7FFF);
    checkEq('temperature_max', await sensor.readTemperature(), 255.9921875);

    // -- Limits and offset ------------------------------------------------
    const full = new TMP117Full(conn);
    await full.setHighLimit(30.0);
    checkEq('set_high_limit_raw', conn.words.get(_REG_THIGH), 0x0F00);
    checkEq('get_high_limit', await full.getHighLimit(), 30.0);
    await full.setLowLimit(-10.25);
    checkEq('set_low_limit_raw', conn.words.get(_REG_TLOW), 0xFAE0);
    checkEq('get_low_limit', await full.getLowLimit(), -10.25);
    await full.setLowLimit(0.004);
    checkEq('limit_rounding', conn.words.get(_REG_TLOW), 0x0001);
    await full.setHighLimit(1000);
    checkEq('limit_clamp_high', conn.words.get(_REG_THIGH), 0x7FFF);
    await full.setLowLimit(-1000);
    checkEq('limit_clamp_low', conn.words.get(_REG_TLOW), 0x8000);
    await full.setTemperatureOffset(-0.5);
    checkEq('set_offset_raw', conn.words.get(_REG_OFFSET), 0xFFC0);
    checkEq('get_offset', await full.getTemperatureOffset(), -0.5);

    // -- Conversion configuration -----------------------------------------
    conn.words.set(_REG_CONFIG, 0x0220);
    let cfg = await full.getConfig();
    checkTrue('get_config_default', cfg.mode === 'continuous' && cfg.averaging === 8 && cfg.cycleSeconds === 1.0);
    await full.configure('shutdown', 64, 16.0);
    checkEq('configure_shutdown_raw', conn.words.get(_REG_CONFIG), 0x07E0);
    cfg = await full.getConfig();
    checkTrue('get_config_shutdown', cfg.mode === 'shutdown' && cfg.averaging === 64 && cfg.cycleSeconds === 16.0);
    checkTrue('is_shutdown', await full.isShutdown());
    await full.configure('continuous', 0, 0.01);
    checkEq('configure_fastest_raw', conn.words.get(_REG_CONFIG), 0x0000);
    checkTrue('not_shutdown', !(await full.isShutdown()));
    await full.configure('continuous', 8, 0.3);
    checkEq('configure_nearest_cycle', conn.words.get(_REG_CONFIG), 0x0120);
    await full.configure('one_shot', 32, 2.0);
    checkEq('configure_one_shot_raw', conn.words.get(_REG_CONFIG), 0x0E40);
    checkEq('get_config_one_shot', (await full.getConfig()).mode, 'one_shot');
    conn.words.set(_REG_CONFIG, 0x0800);
    checkEq('get_config_mod_10', (await full.getConfig()).mode, 'continuous');
    conn.words.set(_REG_CONFIG, 0xF01C);
    await full.configure();
    checkEq('configure_preserves_alert_bits', conn.words.get(_REG_CONFIG), 0x023C);
    await checkRejects('configure_rejects_mode', () => full.configure('bogus'), RangeError);
    await checkRejects('configure_rejects_averaging', () => full.configure('continuous', 16), RangeError);

    conn.words.set(_REG_CONFIG, 0xE660);
    await full.triggerOneShot();
    checkEq('trigger_one_shot', conn.words.get(_REG_CONFIG), 0x0E60);

    conn.words.set(_REG_CONFIG, 0x2220);
    checkTrue('is_data_ready', await full.isDataReady());
    conn.words.set(_REG_CONFIG, 0x0220);
    checkTrue('is_not_data_ready', !(await full.isDataReady()));

    // -- Soft reset -------------------------------------------------------
    await full.reset();
    const last = writesTo(conn, _REG_CONFIG).pop();
    checkTrue('reset_write', last.length === 3 && last[1] === 0x00 && last[2] === 0x02);

    // -- EEPROM -------------------------------------------------------------
    await full.unlockEeprom();
    checkEq('unlock_eeprom', conn.words.get(_REG_EEPROM_UL), 0x8000);
    await full.lockEeprom();
    checkEq('lock_eeprom', conn.words.get(_REG_EEPROM_UL), 0x0000);
    conn.words.set(_REG_EEPROM_UL, 0x4000);
    checkTrue('is_eeprom_busy', await full.isEepromBusy());
    conn.words.set(_REG_EEPROM_UL, 0x8000);
    checkTrue('is_eeprom_not_busy', !(await full.isEepromBusy()));

    conn.words.set(_REG_EEPROM1, 0x1111);
    conn.words.set(_REG_EEPROM2, 0x2222);
    conn.words.set(_REG_EEPROM3, 0x3333);
    checkEq('read_scratch_1', await full.readEepromScratch(1), 0x1111);
    checkEq('read_scratch_2', await full.readEepromScratch(2), 0x2222);
    checkEq('read_scratch_3', await full.readEepromScratch(3), 0x3333);
    await checkRejects('read_scratch_rejects_slot', () => full.readEepromScratch(4), RangeError);
    await full.writeEepromScratch(2, 0xBEEF);
    checkEq('write_scratch_2', conn.words.get(_REG_EEPROM2), 0xBEEF);
    await checkRejects('write_scratch_rejects_1', () => full.writeEepromScratch(1, 0), RangeError);
    await checkRejects('write_scratch_rejects_3', () => full.writeEepromScratch(3, 0), RangeError);
    checkTrue('factory_slots_untouched', conn.words.get(_REG_EEPROM1) === 0x1111 &&
        conn.words.get(_REG_EEPROM3) === 0x3333);

    // -- Alert configuration --------------------------------------------------
    conn.words.set(_REG_CONFIG, 0x0220);
    await full.configureAlert('therm', 'active_high', 'data_ready');
    checkEq('configure_alert_bits', conn.words.get(_REG_CONFIG), 0x023C);
    await full.configureAlert();
    checkEq('configure_alert_defaults', conn.words.get(_REG_CONFIG), 0x0220);
    await checkRejects('configure_alert_rejects_invalid', () => full.configureAlert('alert', 'active_low', 'bogus'), RangeError);

    // -- pollInterrupt ------------------------------------------------------
    conn.words.set(_REG_CONFIG, 0x2220);
    checkEq('poll_interrupt_none', await full.pollInterrupt(), 0);
    conn.words.set(_REG_CONFIG, 0x8220);
    checkEq('poll_interrupt_high', await full.pollInterrupt(), TMP117Full.SOURCE_HIGH);
    conn.words.set(_REG_CONFIG, 0x4220);
    checkEq('poll_interrupt_low', await full.pollInterrupt(), TMP117Full.SOURCE_LOW);
    conn.words.set(_REG_CONFIG, 0xC220);
    checkEq('poll_interrupt_both', await full.pollInterrupt(), TMP117Full.SOURCE_HIGH | TMP117Full.SOURCE_LOW);

    // Polling fallback (no intPin): calls back only when the mask changes.
    conn.words.set(_REG_CONFIG, 0x0220);
    const calls = [];
    await full.onInterrupt((status) => calls.push(status));
    await new Promise((r) => setTimeout(r, 30));
    checkEq('on_interrupt_quiet_when_unchanged', calls.length, 0);
    conn.words.set(_REG_CONFIG, 0x8220);
    await new Promise((r) => setTimeout(r, 30));
    checkTrue('on_interrupt_reports_change', calls.length === 1 && calls[0] === TMP117Full.SOURCE_HIGH);
    await full.offInterrupt();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
