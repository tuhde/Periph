'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Si1132Full, Si1132Minimal } = require('../../packages/periph/src/chips/light/si1132');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-6) => Math.abs(a - b) <= tol;

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

/** Models the Si1132 sequencer: RESPONSE counter, parameter RAM, forced measurement. */
class ChipMock extends I2CConnectionMock {
    constructor(seqId = 0x08, partId = 0x32) {
        super();
        this.setRegister(0x00, [partId]);
        this.setRegister(0x02, [seqId]);
        this.params = {};
        this.cmds = [];
        this.vis = 1234;
        this.ir = 567;
        this.aux = 250;
        this.stuck = false;
        this.cmdError = null;
    }

    r(a) { return this.registers.get(a) || 0; }

    count() { this.setRegister(0x20, [(this.r(0x20) + 1) & 0x0F]); }

    async write(data) {
        await super.write(data);
        if (data.length !== 2) return;
        const [reg, val] = [data[0], data[1]];
        if (reg === 0x21) this.setRegister(0x21, [this.r(0x21) & ~val & 0xFF]);
        else if (reg === 0x18) { this.cmds.push(val); this.command(val); }
    }

    command(cmd) {
        if (this.cmdError !== null && cmd !== 0x00) { this.setRegister(0x20, [this.cmdError]); return; }
        if ((cmd & 0xE0) === 0xA0) {
            this.params[cmd & 0x1F] = this.r(0x17);
            this.setRegister(0x2E, [this.params[cmd & 0x1F]]);
            this.count();
        } else if ((cmd & 0xE0) === 0x80) {
            this.setRegister(0x2E, [this.params[cmd & 0x1F] || 0]);
            this.count();
        } else if (cmd === 0x00) {
            this.setRegister(0x20, [0]);
        } else if (cmd === 0x06) {
            if (!this.stuck) this.measure();
            this.count();
        } else if (cmd === 0x12) {
            for (let i = 0; i < 12; i++) this.setRegister(0x22 + i, [0x10 + i]);
            this.count();
        } else if (cmd !== 0x01) {
            this.count();
        }
    }

    measure() {
        let a = this.aux;
        if (this.params[0x01] === 0x40) a = this.params[0x0F] === 0x65 ? 0x4000 : 0x2000;
        this.setRegister(0x22, [this.vis & 0xFF, this.vis >> 8, this.ir & 0xFF, this.ir >> 8]);
        this.setRegister(0x2C, [a & 0xFF, a >> 8]);
        this.setRegister(0x21, [this.r(0x21) | 0x01]);
    }
}

const wrote = (c, bytes) => c.writes.some((w) => JSON.stringify([...w]) === JSON.stringify(bytes));

async function main() {
    // --- Minimal ---
    let c = new ChipMock();
    const s = new Si1132Minimal(c);
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_hw_key', wrote(c, [0x07, 0x17]));
    checkTrue('init_ucoef', wrote(c, [0x13, 0x7B, 0x6B, 0x01, 0x00]));
    checkTrue('init_chlist', c.params[0x01] === 0xB0);
    checkTrue('init_irq_enable', c.r(0x04) === 0x01);
    const m = await s.read();
    checkTrue('read_uv_index', close(m.uv_index, 2.5));
    checkTrue('read_visible', m.visible === 1234);
    checkTrue('read_infrared', m.infrared === 567);
    checkTrue('read_forces_measurement', c.cmds.includes(0x06));
    checkTrue('read_clears_status', c.r(0x21) === 0);

    checkTrue('wrong_part_id', await rejects(() => new Si1132Minimal(new ChipMock(0x08, 0x45)).init()));

    const st = new ChipMock();
    st.stuck = true;
    const ss = new Si1132Minimal(st);
    await ss.init();
    checkTrue('measurement_timeout', await rejects(() => ss.read()));

    const ce = new ChipMock();
    const se = new Si1132Full(ce);
    await se.init();
    ce.cmdError = 0x80;
    checkTrue('response_error', await rejects(() => se.setGain(1, 1)));
    checkTrue('response_error_nop', ce.cmds[ce.cmds.length - 1] === 0x00);

    // --- Full ---
    c = new ChipMock();
    const f = new Si1132Full(c);
    await f.init();
    checkTrue('full_is_minimal', f instanceof Si1132Minimal);
    checkTrue('read_uv_index_full', close(await f.readUvIndex(), 2.5));
    checkTrue('read_visible_full', (await f.readVisible()) === 1234);
    checkTrue('read_infrared_full', (await f.readInfrared()) === 567);
    const raw = await f.readRaw();
    checkTrue('read_raw', raw.visible === 1234 && raw.infrared === 567 && raw.aux === 250);
    checkTrue('read_temperature_raw', (await f.readTemperatureRaw()) === 0x4000);
    checkTrue('temperature_mux', c.params[0x0F] === 0x65);
    checkTrue('temperature_restores_chlist', c.params[0x01] === 0xB0);
    checkTrue('read_vdd_raw', (await f.readVddRaw()) === 0x2000);
    checkTrue('vdd_mux', c.params[0x0F] === 0x75);

    await f.setChannels(true, false, true);
    checkTrue('set_channels', c.params[0x01] === 0xA0);
    await f.setChannels(false, true, false, true);
    checkTrue('set_channels_aux', c.params[0x01] === 0x50);
    checkTrue('set_channels_none', await rejects(() => f.setChannels(false, false, false)));
    checkTrue('set_channels_uv_aux', await rejects(() => f.setChannels(true, true, true, true)));
    await f.setChannels(true, true, true);

    await f.setGain(3, 5);
    checkTrue('gain_vis', c.params[0x11] === 3 && c.params[0x10] === 0x40);
    checkTrue('gain_ir', c.params[0x1E] === 5 && c.params[0x1D] === 0x20);
    await f.setGain();
    checkTrue('gain_default', c.params[0x11] === 0 && c.params[0x10] === 0x70);
    checkTrue('gain_invalid', await rejects(() => f.setGain(8, 0)));

    await f.setHighRange(true, false);
    checkTrue('high_range', c.params[0x12] === 0x20 && c.params[0x1F] === 0x00);
    await f.setAlignment(true, true);
    checkTrue('alignment', c.params[0x06] === 0x30);

    await f.setUvCoefficients([1, 2, 3, 4]);
    checkTrue('uv_coefficients', wrote(c, [0x13, 1, 2, 3, 4]));
    checkTrue('uv_coefficients_invalid', await rejects(() => f.setUvCoefficients([1, 2])));

    const cal = await f.readCalibration();
    checkTrue('read_calibration', cal.length === 12 && cal[0] === 0x10 && cal[11] === 0x1B);

    await f.startAutonomous(0.5);
    checkTrue('autonomous_rate', c.r(0x08) === 0x80 && c.r(0x09) === 0x3E);
    checkTrue('autonomous_command', c.cmds[c.cmds.length - 1] === 0x0E);
    await f.startAutonomous(0);
    checkTrue('autonomous_stop', c.cmds[c.cmds.length - 1] === 0x0A);
    checkTrue('autonomous_invalid', await rejects(() => f.startAutonomous(3.0)));

    const c1 = new ChipMock(0x01);
    const f1 = new Si1132Full(c1);
    await f1.init();
    await f1.startAutonomous(0.5);
    checkTrue('autonomous_erratum', c1.r(0x0A) === 0x80 && c1.r(0x08) === 0x3E);

    await f.pause();
    checkTrue('pause', c.cmds[c.cmds.length - 1] === 0x0A);
    await f.force();
    checkTrue('force', c.cmds[c.cmds.length - 1] === 0x06);
    checkTrue('measurement_ready', (await f.measurementReady()) === true);
    c.setRegister(0x21, [0]);
    checkTrue('measurement_not_ready', (await f.measurementReady()) === false);

    c.setRegister(0x20, [0x05]);
    let r = await f.readResponse();
    checkTrue('read_response_counter', r.counter === 5 && r.error === 0);
    c.setRegister(0x20, [0x8C]);
    r = await f.readResponse();
    checkTrue('read_response_error', r.counter === 0 && r.error === 0x8C);

    await f.setI2cAddress(0x61);
    checkTrue('set_i2c_address', c.params[0x00] === 0x61 && c.cmds[c.cmds.length - 1] === 0x02);

    await f.reset();
    checkTrue('reset_command', c.cmds.includes(0x01));
    checkTrue('reset_rewrites_hw_key', JSON.stringify([...c.writes[c.writes.length - 1]]) === JSON.stringify([0x07, 0x17]));

    await f.enableInterrupt();
    checkTrue('enable_interrupt', c.r(0x03) === 0x01 && c.r(0x04) === 0x01);
    await f.disableInterrupt();
    checkTrue('disable_interrupt', c.r(0x03) === 0x00);
    c.setRegister(0x21, [0x01]);
    checkTrue('poll_interrupt', (await f.pollInterrupt()) === 0x01);
    checkTrue('poll_interrupt_clears', c.r(0x21) === 0);
    checkTrue('poll_interrupt_none', (await f.pollInterrupt()) === 0);

    // interrupt via pin
    const pin = {
        handler: null, trigger: null,
        async onEdge(h, t) { this.handler = h; this.trigger = t; },
        async offEdge(h) { if (this.handler === h) this.handler = null; },
    };
    const got = [];
    await f.onInterrupt((status) => got.push(status), pin);
    checkTrue('interrupt_enabled', c.r(0x03) === 0x01);
    checkTrue('interrupt_falling', pin.trigger === 'falling');
    c.setRegister(0x21, [0x01]);
    await pin.handler();
    await new Promise((resolve) => setTimeout(resolve, 10));
    checkTrue('interrupt_callback', got.length === 1 && got[0] === 0x01);
    await f.offInterrupt();
    checkTrue('interrupt_unsubscribed', pin.handler === null);
    checkTrue('interrupt_released', c.r(0x03) === 0x00);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
