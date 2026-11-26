'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { CCS811Minimal, CCS811Full } = require('../../packages/periph/src/chips/gas/ccs811');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-6) => Math.abs(a - b) < tol;

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

// ALG_RESULT_DATA: eCO2 500 ppm, TVOC 15 ppb, STATUS 0x98, ERROR_ID 0, RAW 0x8C88
const RESULT = [0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88];
const NTC = [0x06, 0x40, 0x03, 0x20];

// Each register is its own mailbox; STATUS tracks FW_MODE and DATA_READY.
class Mock extends I2CConnectionMock {
    constructor() {
        super();
        this.mail = new Map([
            [0x20, [0x81]], [0x21, [0x12]], [0x23, [0x10, 0x00]], [0x24, [0x11, 0x02]],
            [0x02, RESULT], [0x03, [0x8C, 0x88]], [0x06, NTC], [0x11, [0x84, 0x7B]], [0xE0, [0x00]],
        ]);
        this.appValid = true;
        this.fwMode = false;
        this.dataReady = true;
        this.readyAfterStart = true;
    }

    statusByte() {
        return (this.fwMode ? 0x80 : 0) | (this.appValid ? 0x10 : 0) | (this.dataReady ? 0x08 : 0);
    }

    async write(data) {
        const b = Buffer.from(data);
        this.writes.push(b);
        if (b[0] === 0xF4 && b.length === 1) { if (this.readyAfterStart) this.fwMode = true; }
        else if (b[0] === 0xFF) this.fwMode = false;
        else if (b.length >= 2) this.mail.set(b[0], [...b.subarray(1)]);
    }

    async writeRead(data, n) {
        const reg = Buffer.from(data)[0];
        this.writes.push(Buffer.from(data));
        if (reg === 0x00) return Buffer.from([this.statusByte()]);
        const v = this.mail.get(reg) || [];
        if (reg === 0x02) this.dataReady = false;
        return Buffer.from(Array.from({ length: n }, (_, i) => (i < v.length ? v[i] : 0)));
    }
}

function writesTo(c, reg) { return c.writes.filter((w) => w.length >= 2 && w[0] === reg); }
function last(c, reg) { const w = writesTo(c, reg); return w.length ? [...w[w.length - 1].subarray(1)].join() : null; }
function wrote(c, frame, from = 0) { return c.writes.slice(from).some((w) => [...w].join() === frame.join()); }

async function main() {
    const c = new Mock();
    const s = new CCS811Full(c);
    checkTrue('constructor_no_bus', c.writes.length === 0);
    await s.init();
    checkTrue('init_app_start', wrote(c, [0xF4]));
    checkTrue('init_mode_1', last(c, 0x01) === '16');

    const m = await s.read();
    checkTrue('read_eco2', m.eco2_ppm === 500);
    checkTrue('read_tvoc', m.tvoc_ppb === 15);
    checkTrue('read_clears_ready', !c.dataReady);
    c.dataReady = true;
    checkTrue('read_eco2_single', (await s.readEco2()) === 500);
    c.dataReady = true;
    checkTrue('read_tvoc_single', (await s.readTvoc()) === 15);
    checkTrue('data_ready_false', (await s.dataReady()) === false);
    c.dataReady = true;
    checkTrue('data_ready_true', (await s.dataReady()) === true);

    const [cur, volt] = await s.readRaw();
    checkTrue('read_raw_current', cur === 35);
    checkTrue('read_raw_voltage', near(volt, 136 * 1.65 / 1023));

    await s.enableInterrupt(CCS811Full.SOURCE_DATA_READY);
    checkTrue('int_enable_data_ready', last(c, 0x01) === String(0x18));
    await s.setMode(2);
    checkTrue('set_mode_keeps_int', last(c, 0x01) === String(0x28));
    await s.enableInterrupt(CCS811Full.SOURCE_THRESHOLD);
    checkTrue('int_enable_threshold', last(c, 0x01) === String(0x2C));
    await s.disableInterrupt(CCS811Full.SOURCE_THRESHOLD);
    checkTrue('int_disable_threshold', last(c, 0x01) === String(0x20));
    await s.enableInterrupt(CCS811Full.SOURCE_THRESHOLD);
    await s.disableInterrupt(CCS811Full.SOURCE_DATA_READY);
    checkTrue('int_disable_data_ready', last(c, 0x01) === String(0x24));
    await s.disableInterrupt(CCS811Full.SOURCE_THRESHOLD);
    await s.setMode(4);
    checkTrue('set_mode_4', last(c, 0x01) === String(0x40));
    await s.setMode(0);
    checkTrue('set_mode_idle', last(c, 0x01) === '0');
    checkTrue('set_mode_invalid', await rejects(() => s.setMode(5)) && await rejects(() => s.setMode(-1)));
    await s.setMode(1);

    await s.setEnvironment(48.5, 22.5);
    checkTrue('set_environment', last(c, 0x05) === [0x61, 0x00, 0x5F, 0x00].join());
    await s.setEnvironment(50, 25);
    checkTrue('set_environment_default', last(c, 0x05) === [0x64, 0x00, 0x64, 0x00].join());
    checkTrue('set_environment_invalid', await rejects(() => s.setEnvironment(-1, 25))
        && await rejects(() => s.setEnvironment(101, 25)) && await rejects(() => s.setEnvironment(50, -26))
        && await rejects(() => s.setEnvironment(50, 103)));

    const ntc = await s.readNtc();
    checkTrue('read_ntc', ntc[0] === 1600 && ntc[1] === 800);
    checkTrue('ntc_resistance', near(await s.ntcResistance(100000), 50000));
    c.mail.set(0x06, [0x00, 0x00, 0x03, 0x20]);
    checkTrue('ntc_resistance_no_ref', await rejects(() => s.ntcResistance(100000)));
    c.mail.set(0x06, NTC);

    await s.setThresholds(1000, 2000, 50);
    checkTrue('set_thresholds', last(c, 0x10) === [0x03, 0xE8, 0x07, 0xD0, 0x32].join());
    await s.setThresholds();
    checkTrue('set_thresholds_default', last(c, 0x10) === [0x05, 0xDC, 0x09, 0xC4, 0x32].join());
    checkTrue('set_thresholds_invalid', await rejects(() => s.setThresholds(2000, 1000, 50))
        && await rejects(() => s.setThresholds(1000, 2000, 256)) && await rejects(() => s.setThresholds(1000, 70000, 50)));

    checkTrue('get_baseline', (await s.getBaseline()) === 0x847B);
    await s.setBaseline(0x1234);
    checkTrue('set_baseline', last(c, 0x11) === [0x12, 0x34].join());
    checkTrue('get_baseline_roundtrip', (await s.getBaseline()) === 0x1234);
    checkTrue('set_baseline_invalid', await rejects(() => s.setBaseline(0x10000)));

    c.dataReady = false;
    checkTrue('status', (await s.status()) === 0x90);
    c.mail.set(0xE0, [0x10]);
    checkTrue('error', (await s.error()) === 0x10);
    c.mail.set(0xE0, [0x00]);
    const hw = await s.hardwareVersion();
    checkTrue('hardware_version', hw[0] === 0x81 && hw[1] === 0x12);
    const fw = await s.firmwareVersion();
    checkTrue('firmware_version', fw.boot.join() === '1,0,0' && fw.app.join() === '1,1,2');

    c.dataReady = true;
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 0x98);
    checkTrue('poll_interrupt_keeps_ready', c.dataReady);

    const pin = {
        handler: null, edge: null,
        async onEdge(h, edge) { this.handler = h; this.edge = edge; },
        async offEdge(h) { if (h === this.handler) this.handler = null; },
    };
    const seen = [];
    await s.onInterrupt((st) => seen.push(st), pin);
    checkTrue('on_interrupt_falling_edge', pin.edge === 'falling');
    await s._service();
    checkTrue('on_interrupt_dispatch', seen.length === 1 && seen[0] === 0x98);
    c.dataReady = false;
    pin.handler();
    await new Promise((res) => setTimeout(res, 10));
    checkTrue('on_interrupt_no_spurious', seen.length === 1);
    await s.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);

    c.dataReady = true;
    c.mail.set(0x02, [0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08]);
    c.mail.set(0xE0, [0x08]);
    let msg = '';
    try { await s.read(); } catch (e) { msg = e.message; }
    checkTrue('read_error_flag', msg.includes('0x08'));
    c.mail.set(0x02, RESULT);
    c.mail.set(0xE0, [0x00]);

    const n = c.writes.length;
    await s.setMode(3);
    await s.softReset();
    checkTrue('soft_reset_key', wrote(c, [0xFF, 0x11, 0xE5, 0x72, 0x8A], n));
    checkTrue('soft_reset_app_start', wrote(c, [0xF4], n));
    checkTrue('soft_reset_mode_1', last(c, 0x01) === '16');
    c.dataReady = true;
    checkTrue('read_after_reset', (await s.read()).eco2_ppm === 500);

    const mn = new CCS811Minimal(new Mock());
    await mn.init();
    const mm = await mn.read();
    checkTrue('minimal_read', mm.eco2_ppm === 500 && mm.tvoc_ppb === 15);

    const ac = new Mock();
    ac.fwMode = true;
    await new CCS811Minimal(ac).init();
    checkTrue('init_app_running', !wrote(ac, [0xF4]));

    const bad = new Mock();
    bad.mail.set(0x20, [0x55]);
    checkTrue('init_bad_id', await rejects(() => new CCS811Minimal(bad).init()));
    const noapp = new Mock();
    noapp.appValid = false;
    checkTrue('init_no_app', await rejects(() => new CCS811Minimal(noapp).init()));
    const stuck = new Mock();
    stuck.readyAfterStart = false;
    checkTrue('init_app_start_failed', await rejects(() => new CCS811Minimal(stuck).init()));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
