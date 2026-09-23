'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MCP9808Minimal, MCP9808Full } = require('../../packages/periph/src/chips/temperature/mcp9808');

const _REG_CONFIG = 0x01;
const _REG_TA     = 0x05;

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

// Word-addressed variant of the byte-slot mock: MCP9808 registers are 16 bits
// wide at consecutive pointer values (MANUFACTURER_ID at 0x06, DEVICE_ID at
// 0x07), which would overlap in the shared mock's byte-slot model. Each
// pointer owns one 16-bit word here; 1-byte accesses (RESOLUTION) use the
// word's low byte.
class WordMock extends I2CConnectionMock {
    constructor() {
        super();
        this.words = new Map([[0x06, 0x0054], [0x07, 0x0400], [0x08, 0x0003]]);
    }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        if (buf.length === 3) this.words.set(buf[0], (buf[1] << 8) | buf[2]);
        else if (buf.length === 2) this.words.set(buf[0], buf[1]);
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        const word = this.words.get(buf[0]) || 0;
        if (n === 1) return Buffer.from([word & 0xFF]);
        return Buffer.from([(word >> 8) & 0xFF, word & 0xFF]);
    }
}

function writesTo(conn, reg) {
    return conn.writes.filter((b) => b.length >= 2 && b[0] === reg);
}

async function main() {
    // -- Identity check --------------------------------------------------
    {
        const conn = new WordMock();
        const sensor = new MCP9808Minimal(conn);
        await sensor.init();
        checkTrue('init_no_register_writes', conn.writes.every((b) => b.length === 1));

        const bad = new WordMock();
        bad.words.set(0x06, 0x1234);
        await checkRejects('init_rejects_wrong_manufacturer', () => new MCP9808Minimal(bad).init());
        const badDev = new WordMock();
        badDev.words.set(0x07, 0x0500);
        await checkRejects('read_rejects_wrong_device', () => new MCP9808Minimal(badDev).readTemperature());
        const rev = new WordMock();
        rev.words.set(0x07, 0x0401);
        await new MCP9808Minimal(rev).init();
        checkTrue('init_ignores_revision', true);

        // -- Temperature decoding -----------------------------------------
        conn.words.set(_REG_TA, 0x0194);
        checkEq('temperature_positive', await sensor.readTemperature(), 25.25);
        conn.words.set(_REG_TA, 0xE194);
        checkEq('temperature_masks_flags', await sensor.readTemperature(), 25.25);
        conn.words.set(_REG_TA, 0x1FF0);
        checkEq('temperature_negative', await sensor.readTemperature(), -1.0);
        conn.words.set(_REG_TA, 0x1E6C);
        checkEq('temperature_negative_fraction', await sensor.readTemperature(), -25.25);
        conn.words.set(_REG_TA, 0x0001);
        checkEq('temperature_lsb', await sensor.readTemperature(), 0.0625);
    }

    const conn = new WordMock();
    const full = new MCP9808Full(conn);
    await full.init();

    // -- Boundaries --------------------------------------------------------
    await full.setUpperLimit(80.0);
    checkEq('upper_limit_encode', conn.words.get(0x02), 0x0500);
    checkEq('upper_limit_decode', await full.getUpperLimit(), 80.0);
    await full.setLowerLimit(-25.0);
    checkEq('lower_limit_encode', conn.words.get(0x03), 0x1E70);
    checkEq('lower_limit_decode', await full.getLowerLimit(), -25.0);
    await full.setCriticalLimit(-5.1);
    checkEq('critical_limit_rounds', await full.getCriticalLimit(), -5.0);
    await full.setCriticalLimit(22.13);
    checkEq('critical_limit_rounds_up', await full.getCriticalLimit(), 22.25);
    await full.setUpperLimit(1000.0);
    checkEq('limit_clamps_high', await full.getUpperLimit(), 255.75);
    await full.setLowerLimit(-1000.0);
    checkEq('limit_clamps_low', await full.getLowerLimit(), -256.0);

    // -- Resolution ----------------------------------------------------------
    await full.setResolution(0.25);
    const res = writesTo(conn, 0x08);
    checkTrue('resolution_write', res[res.length - 1].equals(Buffer.from([0x08, 0x01])));
    checkEq('resolution_read', await full.getResolution(), 0.25);
    await checkRejects('resolution_rejects_invalid', () => full.setResolution(0.3), RangeError);

    // -- Hysteresis ----------------------------------------------------------
    conn.words.set(_REG_CONFIG, 0x0000);
    await full.setHysteresis(3.0);
    checkEq('hysteresis_write', conn.words.get(_REG_CONFIG), 0x0400);
    checkEq('hysteresis_read', await full.getHysteresis(), 3.0);
    await checkRejects('hysteresis_rejects_invalid', () => full.setHysteresis(2.0), RangeError);

    // -- Shutdown / wake -----------------------------------------------------
    conn.words.set(_REG_CONFIG, 0x0400);
    await full.shutdown();
    checkEq('shutdown_sets_shdn_keeps_thyst', conn.words.get(_REG_CONFIG), 0x0500);
    checkTrue('is_shutdown_true', await full.isShutdown());
    await full.wake();
    checkEq('wake_clears_shdn', conn.words.get(_REG_CONFIG), 0x0400);
    checkTrue('is_shutdown_false', !(await full.isShutdown()));
    conn.words.set(_REG_CONFIG, 0x0080);
    const before = writesTo(conn, _REG_CONFIG).length;
    await full.shutdown();
    checkEq('shutdown_noop_when_locked', writesTo(conn, _REG_CONFIG).length, before);

    // -- Locks ---------------------------------------------------------------
    conn.words.set(_REG_CONFIG, 0x0000);
    await full.lockCriticalLimit();
    checkEq('lock_critical_sets_bit', conn.words.get(_REG_CONFIG), 0x0080);
    checkTrue('is_critical_locked', await full.isCriticalLimitLocked());
    checkTrue('is_window_unlocked', !(await full.isWindowLimitsLocked()));
    conn.words.set(_REG_CONFIG, 0x0000);
    await full.lockWindowLimits();
    checkEq('lock_window_sets_bit', conn.words.get(_REG_CONFIG), 0x0040);
    checkTrue('is_window_locked', await full.isWindowLimitsLocked());

    // -- Alert configuration -------------------------------------------------
    conn.words.set(_REG_CONFIG, 0x0000);
    await full.configureAlert('critical_only', 'interrupt', 'active_high');
    checkEq('configure_alert_bits', conn.words.get(_REG_CONFIG), 0x0007);
    await full.configureAlert();
    checkEq('configure_alert_defaults', conn.words.get(_REG_CONFIG), 0x0000);
    conn.words.set(_REG_CONFIG, 0x0040);
    await checkRejects('configure_alert_rejects_locked', () => full.configureAlert('all', 'interrupt'));
    await checkRejects('configure_alert_rejects_invalid', () => full.configureAlert('bogus'), RangeError);

    conn.words.set(_REG_CONFIG, 0x0000);
    await full.enableAlert();
    checkEq('enable_alert', conn.words.get(_REG_CONFIG), 0x0008);
    await full.disableAlert();
    checkEq('disable_alert', conn.words.get(_REG_CONFIG), 0x0000);

    conn.words.set(_REG_CONFIG, 0x0019);
    checkTrue('is_alert_asserted', await full.isAlertAsserted());
    await full.clearInterrupt();
    const clr = writesTo(conn, _REG_CONFIG);
    checkTrue('clear_interrupt_write', clr[clr.length - 1].equals(Buffer.from([_REG_CONFIG, 0x00, 0x29])));
    conn.words.set(_REG_CONFIG, 0x0009);
    checkTrue('is_alert_not_asserted', !(await full.isAlertAsserted()));

    // -- pollInterrupt / onInterrupt ------------------------------------------
    conn.words.set(_REG_TA, 0x0194);
    checkEq('poll_interrupt_none', await full.pollInterrupt(), 0);
    conn.words.set(_REG_TA, 0x2194);
    checkEq('poll_interrupt_lower', await full.pollInterrupt(), MCP9808Full.SOURCE_LOWER);
    conn.words.set(_REG_TA, 0xC194);
    checkEq('poll_interrupt_upper_critical', await full.pollInterrupt(),
        MCP9808Full.SOURCE_UPPER | MCP9808Full.SOURCE_CRITICAL);

    // Polling fallback (no intPin): calls back only when the mask changes.
    conn.words.set(_REG_TA, 0x0194);
    const calls = [];
    await full.onInterrupt((status) => calls.push(status));
    await new Promise((r) => setTimeout(r, 30));
    checkEq('on_interrupt_quiet_when_unchanged', calls.length, 0);
    conn.words.set(_REG_TA, 0x4194);
    await new Promise((r) => setTimeout(r, 30));
    checkTrue('on_interrupt_reports_change', calls.length === 1 && calls[0] === MCP9808Full.SOURCE_UPPER);
    await full.offInterrupt();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
