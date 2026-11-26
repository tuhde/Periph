'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Si1133Minimal, Si1133Full } = require('../../packages/periph/src/chips/light/si1133');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

// Simulates the command engine: RESPONSE0 counter, parameter table, FORCE packing HOSTOUT.
class Mock extends I2CConnectionMock {
    constructor() {
        super();
        this.partId = 0x33;
        this.params = new Array(64).fill(0);
        this.counter = 0x0F;
        this.error = 0;
        this.resp1 = 0;
        this.irqEnable = 0;
        this.irqStatus = 0;
        this.hostout = new Array(26).fill(0);
        this.samples = [0x012345, -5, 0xABCD, 0, 0, 0];
        this.commands = [];
        this.ignoreCommands = false;
    }

    resp0() {
        return this.error ? (0x30 | (this.error & 0x0F)) : (0x20 | this.counter);
    }

    command(cmd, hostin) {
        this.commands.push(cmd);
        if (this.ignoreCommands) return;
        if (cmd === 0x00) { this.counter = 0; this.error = 0; return; }
        if (cmd === 0x01) {
            this.params.fill(0);
            this.counter = 0x0F; this.error = 0; this.irqEnable = 0;
            return;
        }
        if ((cmd & 0xC0) === 0x80) {
            const p = cmd & 0x3F;
            if (p > 0x2B) { this.error = 0x11; return; }
            this.params[p] = hostin; this.resp1 = hostin;
        } else if ((cmd & 0xC0) === 0x40) {
            this.resp1 = this.params[cmd & 0x3F];
        } else if (cmd === 0x11) {
            const out = [];
            for (let ch = 0; ch < 6; ch++) {
                if (!(this.params[1] & (1 << ch))) continue;
                const v = this.samples[ch];
                if (this.params[0x04 + 4 * ch] & 0x40) {
                    const u = v & 0xFFFFFF;
                    out.push(u >> 16, (u >> 8) & 0xFF, u & 0xFF);
                } else {
                    out.push((v >> 8) & 0xFF, v & 0xFF);
                }
            }
            this.hostout = out.concat(new Array(26 - out.length).fill(0));
            this.irqStatus |= this.params[1] & this.irqEnable;
        } else if (cmd !== 0x12 && cmd !== 0x13) {
            this.error = 0x10; return;
        }
        this.counter = (this.counter + 1) & 0x0F;
    }

    async write(data) {
        const b = Buffer.from(data);
        this.writes.push(b);
        if (b[0] === 0x0A && b.length >= 3) this.command(b[2], b[1]);
        else if (b[0] === 0x0B && b.length >= 2) this.command(b[1], 0);
        else if (b[0] === 0x0F && b.length >= 2) this.irqEnable = b[1];
    }

    async writeRead(data, n) {
        const reg = Buffer.from(data)[0];
        this.writes.push(Buffer.from(data));
        let block = [];
        if (reg === 0x00) block = [this.partId, 0x03, 0x10];
        else if (reg === 0x10) block = [this.resp1];
        else if (reg === 0x11) block = [this.resp0()];
        else if (reg === 0x12) { block = [this.irqStatus]; this.irqStatus = 0; }
        else if (reg >= 0x13) block = this.hostout.slice(reg - 0x13);
        return Buffer.from(Array.from({ length: n }, (_, i) => (i < block.length ? block[i] : 0)));
    }
}

const wrote = (c, frame) => c.writes.some((w) => w.length === frame.length && frame.every((x, i) => w[i] === x));
const last = (a) => a[a.length - 1];

async function main() {
    const c = new Mock();
    const s = new Si1133Full(c);
    checkTrue('constructor_no_bus', c.writes.length === 0);
    await s.init();
    checkTrue('init_reset_sw', c.commands[0] === 0x01);
    checkTrue('init_channel0_uv', c.params[0x02] === 0x18 && c.params[0x03] === 0x09 && c.params[0x04] === 0x40);
    checkTrue('init_channel1_white', c.params[0x06] === 0x0B && c.params[0x07] === 0x07 && c.params[0x08] === 0x40);
    checkTrue('init_chan_list', c.params[0x01] === 0x03);
    checkTrue('init_irq_enable', c.irqEnable === 0x3F);
    checkTrue('param_set_one_write', wrote(c, [0x0A, 0x03, 0x81]));

    const m = await s.read();
    checkTrue('read_uv', m.uv === 0x012345);
    checkTrue('read_white_negative', m.white === -5);
    checkTrue('read_force', last(c.commands) === 0x11);

    await s.configureChannel(2, Si1133Full.MUX_LARGE_IR, { hwGain: 3, swGain: 2, decimRate: 1, highSignal: true, bits24: false, postshift: 1 });
    checkTrue('configure_values', c.params[0x0A] === 0x22 && c.params[0x0B] === 0xA3 && c.params[0x0C] === 0x08);
    await s.enableChannels(0x07);
    checkTrue('enable_channels', c.params[0x01] === 0x07);
    const v = await s.readChannels();
    checkTrue('read_channels', v.length === 3 && v[0] === 0x012345 && v[1] === -5 && v[2] === 0xABCD);
    const v2 = await s.readChannels(false);
    checkTrue('read_channels_no_force', v2.length === 3 && v2[2] === 0xABCD);
    const m2 = await s.read();
    checkTrue('read_still_works', m2.uv === 0x012345 && m2.white === -5);
    checkTrue('configure_invalid_channel', await rejects(() => s.configureChannel(6, 0)));
    checkTrue('configure_invalid_gain', await rejects(() => s.configureChannel(0, 0x18, { hwGain: 12 })));
    checkTrue('enable_invalid', await rejects(() => s.enableChannels(0x40)));

    c.samples[5] = 1234;
    checkTrue('read_uv_scratch', (await s.readUv()) === 1234 && c.params[0x16] === 0x18 && c.params[0x17] === 0x09);
    checkTrue('read_uv_restores', c.params[0x01] === 0x07);
    checkTrue('read_white_scratch', (await s.readWhite()) === 1234 && c.params[0x16] === 0x0B);
    checkTrue('read_ir_medium', (await s.readIr(Si1133Full.MUX_MEDIUM_IR)) === 1234 && c.params[0x16] === 0x01);
    checkTrue('read_ir_default', (await s.readIr()) === 1234 && c.params[0x16] === 0x00);
    checkTrue('read_ir_invalid', await rejects(() => s.readIr(0x0B)));

    await s.force();
    checkTrue('force', last(c.commands) === 0x11);

    await s.startAutonomous(1.0, [2, 3, 4]);
    checkTrue('measrate', ((c.params[0x1A] << 8) | c.params[0x1B]) === 1250);
    checkTrue('meascount', c.params[0x1C] === 2 && c.params[0x1D] === 3 && c.params[0x1E] === 4);
    checkTrue('counter_index', c.params[0x05] === 0x40 && c.params[0x09] === 0x40 && c.params[0x0D] === 0x40);
    checkTrue('start', last(c.commands) === 0x13);
    await s.setChannelCounter(2, 3);
    checkTrue('set_channel_counter', c.params[0x0D] === 0xC0);
    await s.pause();
    checkTrue('pause', last(c.commands) === 0x12);
    checkTrue('autonomous_invalid', await rejects(() => s.startAutonomous(0)));

    await s.setBurst(4);
    checkTrue('burst', c.params[0x2B] === 0x84);
    await s.setBurst(0);
    checkTrue('burst_off', c.params[0x2B] === 0);
    checkTrue('burst_invalid', await rejects(() => s.setBurst(128)));

    await s.setThreshold(1, 0x1234);
    checkTrue('threshold', c.params[0x27] === 0x12 && c.params[0x28] === 0x34);
    await s.setChannelThreshold(0, 2);
    checkTrue('channel_threshold', c.params[0x04] === 0x42);
    checkTrue('threshold_invalid', await rejects(() => s.setThreshold(3, 0)));

    await s.writeParameter(0x1C, 0x55);
    checkTrue('write_parameter', c.params[0x1C] === 0x55);
    checkTrue('read_parameter', (await s.readParameter(0x1C)) === 0x55 && last(c.commands) === 0x5C);

    const r = await s.readResponse();
    checkTrue('read_response', r.state === 0x20 && !r.error && r.counter === c.counter);

    checkTrue('param_error', await rejects(() => s.writeParameter(0x30, 1)));
    checkTrue('param_error_cleared', last(c.commands) === 0x00 && c.error === 0);

    c.irqStatus = 0x05;
    checkTrue('data_ready', (await s.dataReady()) === 0x05);
    checkTrue('data_ready_cleared', (await s.dataReady()) === 0);

    await s.disableInterrupt(Si1133Full.SOURCE_CHANNEL1);
    checkTrue('disable_interrupt', c.irqEnable === 0x3D);
    await s.enableInterrupt(Si1133Full.SOURCE_CHANNEL1);
    checkTrue('enable_interrupt', c.irqEnable === 0x3F);
    c.irqStatus = 0x02;
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 0x02);

    const pin = { handler: null, edge: null,
        async onEdge(h, e) { this.handler = h; this.edge = e; },
        async offEdge() { this.handler = null; } };
    const seen = [];
    await s.onInterrupt((st) => seen.push(st), pin);
    checkTrue('on_interrupt_subscribes', pin.handler !== null && pin.edge === 'falling');
    c.irqStatus = 0x01;
    pin.handler();
    await new Promise((res) => setTimeout(res, 5));
    checkTrue('on_interrupt_callback', seen.length === 1 && seen[0] === 0x01);
    await s.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);

    const id = await s.readId();
    checkTrue('read_id', id.partId === 0x33 && id.hwId === 0x03 && id.revId === 0x10);

    await s.setI2cAddress(0x30);
    checkTrue('set_i2c_address', c.params[0x00] === 0x30);
    checkTrue('set_i2c_address_invalid', await rejects(() => s.setI2cAddress(0x80)));

    await s.reset();
    checkTrue('reset_defaults', c.params[0x01] === 0x03 && c.params[0x02] === 0x18);
    const m3 = await s.read();
    checkTrue('reset_read', m3.uv === 0x012345 && m3.white === -5);

    const stuck = new Mock();
    const s2 = new Si1133Minimal(stuck);
    await s2.init();
    stuck.ignoreCommands = true;
    checkTrue('command_timeout', await rejects(() => s2.read()));

    const bad = new Mock();
    bad.partId = 0x32;
    checkTrue('init_wrong_part', await rejects(() => new Si1133Minimal(bad).init()));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
