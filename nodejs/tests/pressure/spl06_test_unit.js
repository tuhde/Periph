'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { SPL06Minimal, SPL06Full } = require('../../packages/periph/src/chips/pressure/spl06');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-3) => Math.abs(a - b) < tol;

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

// Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
const COEF = [0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
    0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA];
const TRAW = [0x02, 0x5C, 0x28];   // 154664
const PRAW = [0xFE, 0x94, 0xB7];   // -93001
const T_EXP = 25.0054779053;
const P_EXP = 98263.8611522177;

// MEAS_CFG keeps its read-only ready flags on write; PRS_B2..0 pops queued FIFO entries.
class Mock extends I2CConnectionMock {
    constructor() {
        super();
        this.flags = 0xF0;
        this.fifo = null;
    }

    async write(data) {
        const r = await super.write(data);
        const b = Buffer.from(data);
        if (b.length >= 2 && b[0] === 0x08) this.registers.set(0x08, (b[1] & 0x07) | this.flags);
        return r;
    }

    async writeRead(data, n) {
        const reg = Buffer.from(data)[0];
        if (this.fifo && reg === 0x0B) this.registers.set(0x0B, this.fifo.length ? 0x00 : 0x01);
        if (this.fifo && reg === 0x00 && n === 3 && this.fifo.length) {
            this.writes.push(Buffer.from(data));
            return Buffer.from(this.fifo.shift());
        }
        return super.writeRead(data, n);
    }
}

function make() {
    const c = new Mock();
    c.setRegister(0x0D, [0x10]);
    c.setRegister(0x08, [0xF0]);
    c.setRegister(0x10, COEF);
    c.setRegister(0x00, [...PRAW, ...TRAW]);
    return c;
}

function writesTo(c, reg) { return c.writes.filter((w) => w.length >= 2 && w[0] === reg); }
function last(c, reg) { const w = writesTo(c, reg); return w.length ? w[w.length - 1][1] : -1; }

async function main() {
    const c = make();
    const s = new SPL06Full(c);
    checkTrue('constructor_no_bus', c.writes.length === 0);
    await s.init();
    checkTrue('init_tmp_cfg', last(c, 0x07) === 0x80);
    checkTrue('init_prs_cfg', last(c, 0x06) === 0x04);
    checkTrue('init_cfg_reg', last(c, 0x09) === 0x04);

    const co = s.coefficients();
    checkTrue('coef_c0_c1', co.c0 === 204 && co.c1 === -261);
    checkTrue('coef_c00_c10', co.c00 === 80469 && co.c10 === -54769);
    checkTrue('coef_c01_c11_c20', co.c01 === -2659 && co.c11 === 1258 && co.c20 === -10529);
    checkTrue('coef_c21_c30', co.c21 === 94 && co.c30 === -1350);

    const m = await s.read();
    checkTrue('read_temperature', near(m.temperature_c, T_EXP));
    checkTrue('read_pressure', near(m.pressure_pa, P_EXP));
    const meas = writesTo(c, 0x08).map((w) => w[1]);
    checkTrue('read_temperature_then_pressure', meas.slice(-2).join() === '2,1');
    checkTrue('read_pressure_single', near(await s.readPressure(), P_EXP));
    checkTrue('read_temperature_single', near(await s.readTemperature(), T_EXP));
    checkTrue('read_temperature_command', last(c, 0x08) === 0x02);
    const raw = await s.readRaw();
    checkTrue('read_raw', raw[0] === -93001 && raw[1] === 154664);

    let r = s.compensate(-93001, 154664, 16, 1);
    checkTrue('compensate', near(r.pressure_pa, P_EXP) && near(r.temperature_c, T_EXP));
    r = s.compensate(-93001, 154664, 8, 2);
    checkTrue('compensate_other_scale', near(r.pressure_pa, 80852.2826419888) && near(r.temperature_c, 76.3351593018));
    let threw = false;
    try { s.compensate(0, 0, 3, 1); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('compensate_invalid', threw);

    checkTrue('altitude', near(await s.altitude(), 258.0291543011, 1e-2));
    checkTrue('altitude_reference', near(await s.altitude(P_EXP), 0.0, 1e-6));

    await s.setPressureConfig(128, 8);
    checkTrue('prs_cfg', last(c, 0x06) === 0x37);
    checkTrue('p_shift_set', (last(c, 0x09) & 0x04) !== 0);
    await s.setPressureConfig(8, 1);
    checkTrue('prs_cfg_8x', last(c, 0x06) === 0x03);
    checkTrue('p_shift_cleared', (last(c, 0x09) & 0x04) === 0);
    await s.setTemperatureConfig(32, 4);
    checkTrue('tmp_cfg', last(c, 0x07) === 0xA5);
    checkTrue('t_shift_set', (last(c, 0x09) & 0x08) !== 0);
    await s.setTemperatureConfig(2, 1);
    checkTrue('tmp_cfg_ext_kept', last(c, 0x07) === 0x81);
    checkTrue('t_shift_cleared', (last(c, 0x09) & 0x08) === 0);
    checkTrue('read_uses_new_scale', near((await s.read()).pressure_pa, 80852.2826419888));
    checkTrue('pressure_config_invalid', await rejects(() => s.setPressureConfig(3, 1))
        && await rejects(() => s.setPressureConfig(16, 3)));
    checkTrue('temperature_config_invalid', await rejects(() => s.setTemperatureConfig(0)));
    await s.setPressureConfig();
    await s.setTemperatureConfig();

    await s.startContinuous();
    checkTrue('continuous_both', last(c, 0x08) === 0x07);
    await s.startContinuous(true, false);
    checkTrue('continuous_pressure', last(c, 0x08) === 0x05);
    await s.startContinuous(false, true);
    checkTrue('continuous_temperature', last(c, 0x08) === 0x06);
    checkTrue('continuous_none_rejected', await rejects(() => s.startContinuous(false, false)));
    await s.startContinuous();
    const n = writesTo(c, 0x08).length;
    const rc = await s.readContinuous();
    checkTrue('read_continuous', near(rc.pressure_pa, P_EXP) && near(rc.temperature_c, T_EXP));
    checkTrue('read_continuous_no_command', writesTo(c, 0x08).length === n);
    checkTrue('read_in_background', near((await s.read()).pressure_pa, P_EXP) && writesTo(c, 0x08).length === n);
    let dr = await s.dataReady();
    checkTrue('data_ready', dr[0] && dr[1]);
    c.registers.set(0x08, 0xD7);
    dr = await s.dataReady();
    checkTrue('data_ready_pressure_only', dr[0] && !dr[1]);
    c.registers.set(0x08, 0xF7);
    await s.stop();
    checkTrue('stop', last(c, 0x08) === 0x00);

    await s.enableFifo();
    checkTrue('fifo_enable', (last(c, 0x09) & 0x02) !== 0);
    c.fifo = [TRAW, PRAW];
    const entries = await s.readFifo();
    checkTrue('fifo_count', entries.length === 2);
    checkTrue('fifo_temperature', entries[0].kind === 'temperature' && near(entries[0].value, T_EXP));
    checkTrue('fifo_pressure', entries[1].kind === 'pressure' && near(entries[1].value, 98264.0472093194));
    checkTrue('fifo_empty_read', (await s.readFifo()).length === 0);
    c.fifo = null;
    c.setRegister(0x0B, [0x02]);
    let fs = await s.fifoStatus();
    checkTrue('fifo_status_full', !fs[0] && fs[1]);
    c.setRegister(0x0B, [0x01]);
    fs = await s.fifoStatus();
    checkTrue('fifo_status_empty', fs[0] && !fs[1]);
    await s.flushFifo();
    checkTrue('fifo_flush', last(c, 0x0C) === 0x80);
    await s.disableFifo();
    checkTrue('fifo_disable', (last(c, 0x09) & 0x02) === 0);

    await s.enableInterrupt(SPL06Full.SOURCE_PRESSURE);
    checkTrue('int_enable_pressure', (last(c, 0x09) & 0x20) !== 0);
    await s.enableInterrupt(SPL06Full.SOURCE_TEMPERATURE | SPL06Full.SOURCE_FIFO_FULL);
    checkTrue('int_enable_combined', (last(c, 0x09) & 0x70) === 0x70);
    checkTrue('int_keeps_p_shift', (last(c, 0x09) & 0x04) !== 0);
    await s.disableInterrupt(SPL06Full.SOURCE_PRESSURE | SPL06Full.SOURCE_FIFO_FULL);
    checkTrue('int_disable', (last(c, 0x09) & 0x70) === 0x10);
    c.setRegister(0x0A, [0xFA]);
    checkTrue('poll_interrupt_masked', (await s.pollInterrupt()) === 0x02);

    const pin = {
        handler: null, edge: null,
        async onEdge(h, edge) { this.handler = h; this.edge = edge; },
        async offEdge(h) { if (h === this.handler) this.handler = null; },
    };
    const seen = [];
    await s.onInterrupt((st) => seen.push(st), pin);
    checkTrue('on_interrupt_falling_edge', pin.edge === 'falling');
    c.setRegister(0x0A, [0x01]);
    await s._service();
    checkTrue('on_interrupt_dispatch', seen.length === 1 && seen[0] === 0x01);
    c.setRegister(0x0A, [0x00]);
    pin.handler();
    await new Promise((res) => setTimeout(res, 10));
    checkTrue('on_interrupt_no_spurious', seen.length === 1);
    await s.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);
    await s.disableInterrupt(SPL06Full.SOURCE_TEMPERATURE);

    await s.setPressureConfig(64, 2);
    await s.reset();
    checkTrue('reset_command', last(c, 0x0C) === 0x09);
    checkTrue('reset_defaults', last(c, 0x06) === 0x04 && last(c, 0x07) === 0x80 && last(c, 0x09) === 0x04);
    checkTrue('read_after_reset', near((await s.read()).pressure_pa, P_EXP));

    const mn = new SPL06Minimal(make());
    await mn.init();
    const mm = await mn.read();
    checkTrue('minimal_read', near(mm.pressure_pa, P_EXP) && near(mm.temperature_c, T_EXP));

    const bad = make();
    bad.setRegister(0x0D, [0x58]);
    checkTrue('init_bad_id', await rejects(() => new SPL06Minimal(bad).init()));
    const slow = make();
    slow.setRegister(0x08, [0x40]);
    checkTrue('init_coef_not_ready', await rejects(() => new SPL06Minimal(slow).init()));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
