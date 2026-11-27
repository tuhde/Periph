'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Si1145Minimal, Si1145Full } = require('../../packages/periph/src/chips/light/si1145');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function rejects(fn, cls = Error) {
    try { await fn(); } catch (e) { return e instanceof cls; }
    return false;
}

const near = (a, b) => Math.abs(a - b) < 1e-9;
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// Simulates the command engine: RESPONSE counter, parameter RAM, forced measurements filling the
// data registers and IRQ_STATUS.
class Sim extends I2CConnectionMock {
    constructor(partId = 0x45, seqId = 0x08) {
        super();
        this.regs = new Array(0x40).fill(0);
        this.regs[0x00] = partId;
        this.regs[0x02] = seqId;
        this.params = new Array(32).fill(0);
        this.params[0x00] = 0x60;
        this.params[0x02] = 0x21;
        this.params[0x0C] = 0x04;
        this.params[0x0F] = 0x65;
        this.samples = { vis: 260, ir: 300, ps: [1500, 1600, 1700], uv: 345, temp: 0x1111, vdd: 0x2222 };
        this.commands = [];
        this.ignoreCommands = false;
        this.failParam = null;
    }

    last() { return this.commands[this.commands.length - 1]; }

    put16(reg, v) { this.regs[reg] = v & 0xFF; this.regs[reg + 1] = (v >> 8) & 0xFF; }

    measure(ps, als) {
        const ch = this.params[0x01];
        let status = 0;
        if (als && (ch & 0xF0)) {
            if (ch & 0x10) this.put16(0x22, this.samples.vis);
            if (ch & 0x20) this.put16(0x24, this.samples.ir);
            if (ch & 0x80) this.put16(0x2C, this.samples.uv);
            else if (ch & 0x40) this.put16(0x2C, this.params[0x0F] === 0x65 ? this.samples.temp : this.samples.vdd);
            status |= 0x01;
        }
        if (ps) {
            for (let i = 0; i < 3; i++) {
                if (ch & (1 << i)) { this.put16(0x26 + 2 * i, this.samples.ps[i]); status |= 0x04 << i; }
            }
        }
        this.regs[0x21] |= status & this.regs[0x04];
    }

    command(cmd) {
        this.commands.push(cmd);
        if (this.ignoreCommands) return;
        const resp = this.regs[0x20];
        if (cmd === 0x00) { this.regs[0x20] = 0; return; }
        if (cmd === 0x01) { this.regs[0x20] = 0; this.regs[0x07] = 0; return; }
        if (resp & 0x80) return;
        if ((cmd & 0xE0) === 0xA0) {
            const off = cmd & 0x1F;
            if (off === this.failParam) { this.regs[0x20] = 0x80; return; }
            this.params[off] = this.regs[0x17];
            this.regs[0x2E] = this.regs[0x17];
        } else if ((cmd & 0xE0) === 0x80) {
            this.regs[0x2E] = this.params[cmd & 0x1F];
        } else if (cmd >= 0x05 && cmd <= 0x07) {
            this.measure(cmd !== 0x06, cmd !== 0x05);
        } else if (cmd === 0x12) {
            for (let i = 0; i < 12; i++) this.regs[0x22 + i] = 0x10 + i;
        } else if (![0x02, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F].includes(cmd)) {
            this.regs[0x20] = 0x80; return;
        }
        this.regs[0x20] = (resp + 1) & 0x0F;
    }

    async write(data) {
        const b = Buffer.from(data);
        this.writes.push(b);
        for (let i = 1; i < b.length; i++) {
            const r = b[0] + i - 1;
            if (r === 0x18) this.command(b[i]);
            else if (r === 0x21) this.regs[0x21] &= ~b[i] & 0xFF;
            else this.regs[r] = b[i];
        }
    }

    async writeRead(data, n) {
        const reg = Buffer.from(data)[0];
        this.writes.push(Buffer.from(data));
        return Buffer.from(Array.from({ length: n }, (_, i) => this.regs[(reg + i) & 0x3F]));
    }
}

class FakePin {
    constructor() { this.handler = null; this.edge = null; }
    async onEdge(h, e) { this.handler = h; this.edge = e; }
    async offEdge() { this.handler = null; }
}

(async () => {
    const c = new Sim();
    const s = new Si1145Full(c);
    checkTrue('constructor_no_bus', c.writes.length === 0);
    await s.init();
    checkTrue('init_hw_key', c.regs[0x07] === 0x17);
    checkTrue('init_ucoef', eq(c.regs.slice(0x13, 0x17), [0x7B, 0x6B, 0x01, 0x00]));
    checkTrue('init_chlist', c.params[0x01] === 0xB1);
    checkTrue('init_psled12', c.params[0x02] === 0x01);
    checkTrue('init_ps_led21', c.regs[0x0F] === 0x03);
    checkTrue('init_irq_enable', c.regs[0x04] === 0x05);
    checkTrue('init_bad_part_id', await rejects(() => new Si1145Minimal(new Sim(0x32)).init()));

    let m = await s.read();
    checkTrue('read_proximity', m.proximity === 1500);
    checkTrue('read_uv_index', near(m.uv_index, 3.45));
    checkTrue('read_visible_infrared', m.visible === 260 && m.infrared === 300);
    checkTrue('read_psals_force', c.commands.includes(0x07));
    checkTrue('read_irq_cleared', c.regs[0x21] === 0);

    c.samples.ps[0] = 42;
    checkTrue('read_proximity_ps1', (await s.readProximity()) === 42 && c.last() === 0x05);
    checkTrue('read_proximity_ps2_unsupported', await rejects(() => s.readProximity(2), RangeError));
    c.samples.uv = 1000;
    checkTrue('read_uv_index_single', (await s.readUvIndex()) === 10 && c.last() === 0x06);
    checkTrue('read_visible_single', (await s.readVisible()) === 260);
    checkTrue('read_infrared_single', (await s.readInfrared()) === 300);
    checkTrue('read_raw', eq(await s.readRaw(), { visible: 260, infrared: 300, ps1: 42, ps2: 0, ps3: 0, aux: 1000 }));
    checkTrue('read_temperature_raw', (await s.readTemperatureRaw()) === 0x1111 && c.params[0x0F] === 0x65);
    checkTrue('read_aux_restores_chlist', c.params[0x01] === 0xB1);
    checkTrue('read_vdd_raw', (await s.readVddRaw()) === 0x2222 && c.params[0x0F] === 0x75);

    await s.setChannels(false, true, false, true);
    checkTrue('set_channels', c.params[0x01] === 0x11);
    checkTrue('read_uv_disabled', await rejects(() => s.readUvIndex(), RangeError));
    checkTrue('read_infrared_disabled', await rejects(() => s.readInfrared(), RangeError));
    checkTrue('set_channels_uv_aux', await rejects(() => s.setChannels(true, false, false, false, false, false, true), RangeError));
    checkTrue('set_channels_ps2_si1145', await rejects(() => s.setChannels(false, false, false, true, true), RangeError));
    await s.setChannels(false, false, false, false, false, false, true);
    checkTrue('set_channels_aux', c.params[0x01] === 0x40);
    await s.setChannels(true, true, true, true);
    checkTrue('set_channels_default', c.params[0x01] === 0xB1);

    await s.setLedCurrent(1, 15);
    checkTrue('set_led_current', c.regs[0x0F] === 0x0F);
    checkTrue('set_led_current_led2_si1145', await rejects(() => s.setLedCurrent(2, 1), RangeError));
    checkTrue('set_led_current_level', await rejects(() => s.setLedCurrent(1, 16), RangeError));
    await s.setPsLed(1, 0);
    checkTrue('set_ps_led_off', c.params[0x02] === 0);
    await s.setPsLed(1, 1);
    checkTrue('set_ps_led', c.params[0x02] === 1);
    checkTrue('set_ps_led_led2_si1145', await rejects(() => s.setPsLed(1, 2), RangeError));
    await s.setPsPhotodiode(1, false);
    checkTrue('set_ps_photodiode_small', c.params[0x07] === 0);
    await s.setPsPhotodiode(1, true);
    checkTrue('set_ps_photodiode_large', c.params[0x07] === 3);

    const c47 = new Sim(0x47);
    const s47 = new Si1145Full(c47);
    await s47.init();
    await s47.setLedCurrent(2, 5);
    await s47.setLedCurrent(3, 9);
    checkTrue('si1147_led_current', c47.regs[0x0F] === 0x53 && c47.regs[0x10] === 0x09);
    await s47.setPsLed(2, 2);
    await s47.setPsLed(3, 4);
    checkTrue('si1147_ps_led', c47.params[0x02] === 0x21 && c47.params[0x03] === 0x04);
    await s47.setChannels(false, false, false, true, true, true);
    checkTrue('si1147_read_ps3', (await s47.readProximity(3)) === 1700);

    await s.setGain(3, 2, 1);
    checkTrue('set_gain_vis', c.params[0x11] === 3 && c.params[0x10] === 0x40);
    checkTrue('set_gain_ir', c.params[0x1E] === 2 && c.params[0x1D] === 0x50);
    checkTrue('set_gain_ps', c.params[0x0B] === 1 && c.params[0x0A] === 0x60);
    checkTrue('set_gain_range', await rejects(() => s.setGain(8), RangeError));
    await s.setGain();
    checkTrue('set_gain_default', c.params[0x10] === 0x70 && c.params[0x11] === 0);
    await s.setHighRange(true, true, true);
    checkTrue('set_high_range', c.params[0x12] === 0x20 && c.params[0x1F] === 0x20 && c.params[0x0C] === 0x24);
    await s.setHighRange(false, false, false);
    checkTrue('set_high_range_off', c.params[0x0C] === 0x04);
    await s.setAlignment({ visible: true, ps1: true });
    checkTrue('set_alignment', c.params[0x06] === 0x10 && c.params[0x05] === 0x10);
    await s.setUvCoefficients([1, 2, 3, 4]);
    checkTrue('set_uv_coefficients', eq(c.regs.slice(0x13, 0x17), [1, 2, 3, 4]));
    checkTrue('set_uv_coefficients_len', await rejects(() => s.setUvCoefficients([1, 2, 3]), RangeError));

    checkTrue('read_calibration', eq(await s.readCalibration(), Array.from({ length: 12 }, (_, i) => 0x10 + i)) && c.last() === 0x12);

    await s.startAutonomous(0.1);
    checkTrue('start_autonomous_rate', c.regs[0x08] === (3200 & 0xFF) && c.regs[0x09] === 3200 >> 8);
    checkTrue('start_autonomous_cmd', c.last() === 0x0F);
    await s.startAutonomous(0.01, false);
    checkTrue('start_autonomous_als', c.last() === 0x0E && c.regs[0x08] === 64);
    await s.startAutonomous(0.01, true, false);
    checkTrue('start_autonomous_ps', c.last() === 0x0D);
    checkTrue('start_autonomous_range', await rejects(() => s.startAutonomous(3.0), RangeError));
    checkTrue('start_autonomous_none', await rejects(() => s.startAutonomous(0.1, false, false), RangeError));
    await s.pause();
    checkTrue('pause', c.last() === 0x0B);
    const cs = new Sim(0x45, 0x01);
    const ss = new Si1145Full(cs);
    await ss.init();
    await ss.startAutonomous(0.1);
    checkTrue('start_autonomous_swapped', cs.regs[0x0A] === (3200 & 0xFF) && cs.regs[0x08] === 3200 >> 8);

    await s.force();
    checkTrue('force_psals', c.last() === 0x07);
    await s.force(true, false);
    checkTrue('force_ps', c.last() === 0x05);
    await s.force(false, true);
    checkTrue('force_als', c.last() === 0x06);

    let resp = await s.readResponse();
    checkTrue('read_response', resp.error === 0 && resp.counter === c.regs[0x20]);
    c.failParam = 0x0B;
    checkTrue('param_error', await rejects(() => s.setGain(0, 0, 1)));
    checkTrue('param_error_nop', c.last() === 0x00 && c.regs[0x20] === 0);
    c.failParam = null;
    c.regs[0x20] = 0x8C;
    resp = await s.readResponse();
    checkTrue('read_response_error', resp.error === 0x8C && resp.counter === 0);
    await s.setGain();
    checkTrue('pending_error_cleared', (c.regs[0x20] & 0x80) === 0 && c.params[0x0B] === 0);

    await s.enableInterrupt(Si1145Full.SOURCE_CMD);
    checkTrue('enable_interrupt', c.regs[0x03] === 0x01 && c.regs[0x04] === 0x25);
    await s.disableInterrupt(Si1145Full.SOURCE_CMD);
    checkTrue('disable_interrupt', c.regs[0x04] === 0x05);
    c.regs[0x21] = 0x05;
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 0x05);
    checkTrue('poll_interrupt_cleared', c.regs[0x21] === 0 && (await s.pollInterrupt()) === 0);

    const events = [];
    const pin = new FakePin();
    await s.onInterrupt((st) => events.push(st), pin);
    checkTrue('on_interrupt_falling', pin.edge === 'falling');
    c.regs[0x21] = 0x04;
    await s._service();
    checkTrue('on_interrupt', eq(events, [0x04]));
    await s._service();
    checkTrue('on_interrupt_no_duplicate', eq(events, [0x04]));
    await s.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);
    await s.onInterrupt((st) => events.push(st));
    c.regs[0x21] = 0x01;
    await new Promise((res) => setTimeout(res, 50));
    await s.offInterrupt();
    checkTrue('on_interrupt_polling', eq(events, [0x04, 0x01]));

    await s.disableInterrupt(0x3D);
    checkTrue('read_without_irq', (await s.read()).proximity === 42);

    checkTrue('read_id', eq(await s.readId(), { partId: 0x45, revId: 0, seqId: 0x08 }));
    await s.setI2cAddress(0x30);
    checkTrue('set_i2c_address', c.params[0x00] === 0x30 && c.last() === 0x02);
    checkTrue('set_i2c_address_invalid', await rejects(() => s.setI2cAddress(0x80), RangeError));

    c.regs[0x0F] = 0x0F;
    await s.reset();
    checkTrue('reset_cmd', c.commands.includes(0x01));
    checkTrue('reset_defaults', c.regs[0x07] === 0x17 && c.params[0x01] === 0xB1 && c.regs[0x0F] === 0x03);
    checkTrue('reset_read', (await s.read()).proximity === 42);

    const stuck = new Sim();
    const s2 = new Si1145Full(stuck);
    await s2.init();
    stuck.ignoreCommands = true;
    checkTrue('measure_timeout', await rejects(() => s2.read()));
    checkTrue('command_timeout', await rejects(() => s2.pause()));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
