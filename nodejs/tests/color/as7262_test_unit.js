'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AS7262Full, AS7262Minimal } = require('../../packages/periph/src/chips/color/as7262');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-4) => Math.abs(a - b) <= tol;

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

class Sim extends I2CConnectionMock {
    constructor() {
        super();
        this.v = new Map([[0, 0x40], [1, 0x3E], [2, 0x41], [3, 0x21], [4, 0x08], [5, 0xFF], [6, 25], [7, 0]]);
        for (let i = 0x08; i < 0x2C; i++) this.v.set(i, 0);
        this.pending = null;
        this.out = null;
        this.vwrites = [];
        this.stall = false;
    }

    async readReg(reg, length) {
        if (reg === 0x00) return Buffer.from([this.stall ? 0x02 : (this.out !== null ? 1 : 0)]);
        if (reg === 0x02) { const v = this.out || 0; this.out = null; return Buffer.from([v]); }
        return Buffer.alloc(length);
    }

    async writeReg(reg, data) {
        const b = typeof data === 'number' ? data : data[0];
        this.writes.push(Buffer.from([reg, b]));
        if (this.pending !== null) {
            const r = this.pending;
            this.pending = null;
            this.vwrites.push([r, b]);
            if (r === 0x04) {
                if (b & 0x80) { this.v.set(4, 0x08); this.v.set(5, 0xFF); this.v.set(7, 0); return; }
                this.v.set(r, b | (this.v.get(r) & 0x02));
                if (((b >> 2) & 3) === 3) this.v.set(r, this.v.get(r) | 0x02);
            } else {
                this.v.set(r, b);
            }
        } else if (b & 0x80) {
            this.pending = b & 0x7F;
        } else {
            this.out = this.v.get(b) || 0;
            if (b >= 0x08 && b < 0x2C) this.v.set(4, this.v.get(4) & ~0x02);
        }
    }
}

function loadCal(sim, values) {
    const buf = Buffer.alloc(24);
    values.forEach((x, i) => buf.writeFloatBE(x, 4 * i));
    for (let i = 0; i < 24; i++) sim.v.set(0x14 + i, buf[i]);
}

async function make(full = true) {
    const c = new Sim();
    const s = full ? new AS7262Full(c) : new AS7262Minimal(c);
    await s.init();
    return [c, s];
}

const wrote = (c, r, v) => c.vwrites.some((w) => w[0] === r && w[1] === v);

async function main() {
    let [c, s] = await make(false);
    checkTrue('init_int_t', c.v.get(5) === 0x3B);
    checkTrue('init_gain_16x', ((c.v.get(4) >> 4) & 3) === 2);
    checkTrue('init_leds_off', c.v.get(7) === 0);
    checkTrue('init_reset_written', wrote(c, 4, 0x80));

    loadCal(c, [1.0, 2.5, 3.0, 4.0, 5.0, 6.5]);
    const d = await s.read();
    checkTrue('read_violet', close(d.violet, 1.0));
    checkTrue('read_blue', close(d.blue, 2.5));
    checkTrue('read_red', close(d.red, 6.5));
    checkTrue('read_starts_one_shot', wrote(c, 4, 0x2C));
    checkTrue('temperature', (await s.temperature()) === 25);

    [c, s] = await make();
    for (let i = 0; i < 12; i++) c.v.set(0x08 + i, i % 2 === 0 ? 0x01 : 0x02);
    checkTrue('read_raw', (await s.readRaw()).violet === 0x0102);
    checkTrue('read_channel_raw', (await s.readChannel(5, false)) === 0x0102);
    loadCal(c, [0, 0, 0, 0, 0, 7.25]);
    checkTrue('read_channel_cal', close(await s.readChannel(5), 7.25));
    checkTrue('read_channel_range', await rejects(() => s.readChannel(6)));

    await s.setGain(64);
    checkTrue('gain_64', ((c.v.get(4) >> 4) & 3) === 3 && (await s.getGain()) === 64);
    await s.setGain(3.7);
    checkTrue('gain_3_7', (await s.getGain()) === 3.7);
    await s.setGain(1);
    checkTrue('gain_1x', (await s.getGain()) === 1);
    checkTrue('gain_invalid', await rejects(() => s.setGain(5)));
    await s.setIntegrationTime(50);
    checkTrue('int_time_50', c.v.get(5) === 18 && close(await s.getIntegrationTime(), 50.4, 1e-6));
    checkTrue('int_time_low', await rejects(() => s.setIntegrationTime(1)));
    checkTrue('int_time_high', await rejects(() => s.setIntegrationTime(800)));
    await s.setMode(2);
    checkTrue('mode_2', (await s.getMode()) === 2 && ((c.v.get(4) >> 4) & 3) === 0);
    await s.setContinuous(0);
    checkTrue('continuous_0', (await s.getMode()) === 0);
    checkTrue('continuous_3_rejected', await rejects(() => s.setContinuous(3)));
    await s.setInterrupt(true);
    checkTrue('interrupt_on', (c.v.get(4) & 0x40) !== 0);
    await s.setInterrupt(false);
    checkTrue('interrupt_off', (c.v.get(4) & 0x40) === 0);

    await s.setLed(true, 25);
    checkTrue('led_drv_25', c.v.get(7) === 0x18);
    await s.setIndicatorLed(true, 4);
    checkTrue('led_ind_4', c.v.get(7) === (0x18 | 0x05));
    await s.setLed(false, 100);
    checkTrue('led_drv_off_keeps_ind', c.v.get(7) === (0x30 | 0x05));
    checkTrue('led_drv_too_high', await rejects(() => s.setLed(true, 200)));
    checkTrue('led_ind_too_high', await rejects(() => s.setIndicatorLed(true, 9)));

    const hw = await s.hardwareVersion();
    checkTrue('hardware_version', hw[0] === 0x40 && hw[1] === 0x3E);
    checkTrue('device_type', (await s.deviceType()) === 0x40);
    const fw = await s.firmwareVersion();
    checkTrue('firmware_version', fw[0] === 2 && fw[1] === (((0x21 & 0x0F) << 2) | 1) && fw[2] === 1);
    await s.writeVirtual(0x05, 0x10);
    checkTrue('write_read_virtual', (await s.readVirtual(0x05)) === 0x10);
    await s.softReset();
    checkTrue('soft_reset_defaults', c.v.get(5) === 0xFF);

    await s.measure();
    checkTrue('data_ready', await s.dataReady());
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 1);

    const calls = [];
    await s.onInterrupt((st) => calls.push(st));
    await s._dispatch();
    checkTrue('on_interrupt_dispatch', calls.length >= 1 && calls[0] === 1);
    await s.offInterrupt();
    checkTrue('off_interrupt', (c.v.get(4) & 0x40) === 0);

    c.stall = true;
    checkTrue('handshake_timeout', await rejects(() => s.readVirtual(0x00)));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
