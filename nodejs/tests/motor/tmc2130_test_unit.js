'use strict';
const { TMC2130Minimal, TMC2130Full } = require('../../packages/periph/src/chips/motor/tmc2130');

let passed = 0;
let failed = 0;
function checkTrue(label, c) { if (c) { console.log('PASS', label); passed++; } else { console.log('FAIL', label); failed++; } }
async function rejects(fn, type) { try { await fn(); } catch (e) { return type ? e instanceof type : true; } return false; }

/** Scripted TMC2130 implementing the pipelined 40-bit datagram protocol. */
class ChipSim {
    constructor() {
        this.regs = new Map([[0x04, 0x11000040], [0x01, 0x01]]);
        this.frames = [];
        this.writes = [];
        this.pending = 0;
        this.mirrorBroken = false;
        this.gstatReads = 0;
    }
    _datagram(frame) {
        this.frames.push(Buffer.from(frame));
        const reply = this.pending;
        const reg = frame[0] & 0x7F;
        if (frame[0] & 0x80) {
            const value = Buffer.from(frame).readUInt32BE(1);
            this.regs.set(reg, value);
            this.writes.push([reg, value]);
            this.pending = this.mirrorBroken ? 0 : value;
        } else {
            if (reg === 0x01) this.gstatReads++;
            this.pending = this.regs.get(reg) ?? 0;
            if (reg === 0x01) this.regs.set(0x01, 0);
        }
        return reply;
    }
    async write(data) { if (data.length !== 5) throw new Error('bad frame'); this._datagram(data); }
    async writeRead(data, n) {
        if (n !== 4) throw new Error('bad read length');
        const frame = Buffer.concat([Buffer.from(data), Buffer.alloc(5 - data.length)]);
        const b = Buffer.alloc(4);
        b.writeUInt32BE(this._datagram(frame) >>> 0);
        return b;
    }
    lastWrite(reg) { const v = this.writes.filter((w) => w[0] === reg); return v.length ? v[v.length - 1][1] : undefined; }
}
class Pin {
    constructor() { this.levels = []; }
    async set(h) { this.levels.push(!!h); }
    highs() { return this.levels.filter(Boolean).length; }
}

async function make(Cls = TMC2130Minimal, opts = {}) {
    const sim = new ChipSim();
    const step = new Pin();
    const dir = new Pin();
    const m = new Cls(sim, step, dir, opts);
    await m.init();
    return { sim, step, dir, m };
}

async function main() {
    let { sim, step, dir, m } = await make();
    checkTrue('chopconf_write_frame', sim.frames.some((f) => f.equals(Buffer.from([0xEC, 0x14, 0x03, 0x00, 0xC3]))));
    checkTrue('init_tpowerdown', sim.lastWrite(0x11) === 10);
    checkTrue('init_gconf', sim.lastWrite(0x00) === 0x4);
    checkTrue('init_tpwmthrs', sim.lastWrite(0x13) === 500);
    checkTrue('init_pwmconf', sim.lastWrite(0x70) === 0x000401C8);
    const ih = sim.lastWrite(0x10);
    checkTrue('init_current_0_5a', ((ih >>> 8) & 0x1F) === 15 && (ih & 0x1F) === 5 && ((ih >>> 16) & 0xF) === 6);
    checkTrue('init_clears_gstat_once', sim.gstatReads === 1);

    await m.setCurrent(1.0);
    checkTrue('set_current_1a', ((sim.lastWrite(0x10) >>> 8) & 0x1F) === 17 && !(sim.lastWrite(0x6C) & (1 << 17)));
    await m.setCurrent(0.5);
    checkTrue('set_current_auto_vsense', ((sim.lastWrite(0x10) >>> 8) & 0x1F) === 15 && !!(sim.lastWrite(0x6C) & (1 << 17)));
    checkTrue('set_current_range', await rejects(() => m.setCurrent(1.5), RangeError));

    await m.move(2, 1e6);
    checkTrue('move_pulses', step.highs() === 32 && dir.levels[dir.levels.length - 1] === true);
    await m.move(-1, 1e6);
    checkTrue('move_reverse', dir.levels[dir.levels.length - 1] === false && step.highs() === 48);
    checkTrue('move_speed_range', await rejects(() => m.move(1, 0), RangeError));

    await m.disable();
    checkTrue('disable_toff0', (sim.lastWrite(0x6C) & 0xF) === 0);
    await m.enable();
    checkTrue('enable_toff3', (sim.lastWrite(0x6C) & 0xF) === 3);

    sim.regs.set(0x6F, ((1 << 31) | (1 << 26) | (1 << 28) | (1 << 30)) >>> 0);
    checkTrue('standstill', await m.isStandstill());
    checkTrue('fault_flags', (await m.readFault()) === (0x01 | 0x04 | 0x10));

    const en = new Pin();
    const sim2 = new ChipSim();
    const m2 = new TMC2130Minimal(sim2, new Pin(), new Pin(), { enPin: en });
    await m2.init();
    checkTrue('en_pin_high_at_init', en.levels.length === 1 && en.levels[0] === true);
    await m2.enable();
    checkTrue('en_pin_low_on_enable', en.levels[en.levels.length - 1] === false);

    for (const [label, setup] of [['bad_version', (s) => s.regs.set(0x04, 0)],
        ['bad_bit6', (s) => s.regs.set(0x04, 0x11000000)], ['broken_mirror', (s) => { s.mirrorBroken = true; }]]) {
        const bad = new ChipSim();
        setup(bad);
        checkTrue(label + '_raises', await rejects(() => new TMC2130Minimal(bad, new Pin(), new Pin()).init()));
    }

    ({ sim, step, dir, m } = await make(TMC2130Full, { diag0Pin: { isHigh: () => true } }));
    const f = m;
    await f.setMicrosteps(256);
    checkTrue('microsteps_256', ((sim.lastWrite(0x6C) >>> 24) & 0xF) === 0);
    await f.setMicrosteps(1);
    checkTrue('microsteps_1', ((sim.lastWrite(0x6C) >>> 24) & 0xF) === 8);
    checkTrue('microsteps_invalid', await rejects(() => f.setMicrosteps(3), RangeError));
    await f.setMicrosteps(32);
    await f.move(1, 1e6);
    checkTrue('move_uses_microsteps', step.highs() === 32);
    await f.setInterpolation(false);
    checkTrue('intpol_off', ((sim.lastWrite(0x6C) >>> 28) & 1) === 0);
    await f.setChopperMode(true);
    checkTrue('spreadcycle', (sim.lastWrite(0x00) & 0x4) === 0);
    await f.setChopperMode(false);
    checkTrue('stealthchop', (sim.lastWrite(0x00) & 0x4) === 0x4);
    await f.setDirectionInverted(true);
    checkTrue('shaft', (sim.lastWrite(0x00) & 0x10) === 0x10);
    await f.configureDiag({ diag0Stall: true, diag1Index: true, diag0Pushpull: true });
    const g = sim.lastWrite(0x00);
    checkTrue('diag_bits', ((g >>> 7) & 1) && ((g >>> 9) & 1) && ((g >>> 12) & 1) && !((g >>> 5) & 1) && (g & 0x14) === 0x14);
    await f.setDoubleEdge(true);
    checkTrue('dedge', ((sim.lastWrite(0x6C) >>> 29) & 1) === 1);
    await f.setShortProtection(false);
    checkTrue('diss2g', ((sim.lastWrite(0x6C) >>> 30) & 1) === 1);
    await f.setVsense(false);
    await f.setCurrent(0.5);
    checkTrue('manual_vsense_kept', !((sim.lastWrite(0x6C) >>> 17) & 1) && ((sim.lastWrite(0x10) >>> 8) & 0x1F) === 8);
    await f.setHoldCurrent(0.2);
    checkTrue('hold_current', (sim.lastWrite(0x10) & 0x1F) === 3);
    await f.setCurrentDelay(2, 100);
    checkTrue('current_delay', ((sim.lastWrite(0x10) >>> 16) & 0xF) === 2 && sim.lastWrite(0x11) === 100);
    await f.setStealthchopThreshold(300);
    checkTrue('tpwmthrs', sim.lastWrite(0x13) === 300);
    await f.setHighVelocityThreshold(77);
    checkTrue('thigh', sim.lastWrite(0x15) === 77);
    await f.configureChopper(4, 1, 3, 2);
    let c = sim.lastWrite(0x6C);
    checkTrue('configure_chopper', (c & 0xF) === 4 && ((c >>> 15) & 3) === 1 && ((c >>> 4) & 7) === 3 && ((c >>> 7) & 0xF) === 2 && !((c >>> 14) & 1));
    await f.configureConstantOffTime(5, 2, 11, -3, true, true);
    c = sim.lastWrite(0x6C);
    checkTrue('configure_constant_off_time', (c & 0xF) === 5 && ((c >>> 14) & 1) && ((c >>> 4) & 7) === 3 && ((c >>> 11) & 1) &&
        ((c >>> 7) & 0xF) === 0 && ((c >>> 12) & 1) && ((c >>> 13) & 1));
    await f.configureHighVelocity(true, true);
    checkTrue('high_velocity', ((sim.lastWrite(0x6C) >>> 18) & 3) === 3);
    await f.setChopperSync(5);
    checkTrue('chopper_sync', ((sim.lastWrite(0x6C) >>> 20) & 0xF) === 5);
    await f.configureStealthchop(true, false, 40, 5, 1);
    let p = sim.lastWrite(0x70);
    checkTrue('configure_stealthchop', (p & 0xFF) === 40 && ((p >>> 8) & 0xFF) === 5 && ((p >>> 16) & 3) === 1 && ((p >>> 18) & 1) && !((p >>> 19) & 1));
    await f.setFreewheel(1);
    p = sim.lastWrite(0x70);
    checkTrue('freewheel', ((p >>> 20) & 3) === 1 && (p & 0xFF) === 40);
    await f.setSmallHysteresis(true);
    checkTrue('small_hysteresis', ((sim.lastWrite(0x00) >>> 14) & 1) === 1);
    await f.setEmergencyStop(true);
    checkTrue('stop_enable', ((sim.lastWrite(0x00) >>> 15) & 1) === 1);
    await f.setAnalogScaling(true);
    checkTrue('analog_scaling', (sim.lastWrite(0x00) & 1) === 1);

    await f.setStallThreshold(-5);
    checkTrue('sgt_negative', ((sim.lastWrite(0x6D) >>> 16) & 0x7F) === (-5 & 0x7F));
    await f.setStallguardFilter(true);
    await f.configureCoolstep(5, 2, 1, 1, 0);
    const cc = sim.lastWrite(0x6D);
    checkTrue('coolconf_keeps_sgt', (cc & 0xF) === 5 && ((cc >>> 5) & 3) === 1 && ((cc >>> 8) & 0xF) === 2 &&
        ((cc >>> 13) & 3) === 1 && ((cc >>> 24) & 1) && ((cc >>> 16) & 0x7F) === (-5 & 0x7F));
    checkTrue('range_errors', await rejects(() => f.setStallThreshold(64), RangeError) &&
        await rejects(() => f.setStallThreshold(-65), RangeError) &&
        await rejects(() => f.configureCoolstep(16, 0, 0, 0, 0), RangeError) &&
        await rejects(() => f.setCoolstepThreshold(1 << 20), RangeError));
    await f.setCoolstepThreshold(500);
    checkTrue('tcoolthrs', sim.lastWrite(0x14) === 500);
    sim.regs.set(0x6F, ((1 << 24) | (17 << 16) | 0x2A) >>> 0);
    checkTrue('read_stallguard', (await f.readStallguard()) === 0x2A);
    checkTrue('is_stalled', await f.isStalled());
    checkTrue('read_diag0', f.readDiag(0) === true);
    let threw = false;
    try { f.readDiag(1); } catch (e) { threw = true; }
    checkTrue('read_diag_unwired', threw);
    checkTrue('read_diag_range', (() => { try { f.readDiag(2); } catch (e) { return e instanceof RangeError; } return false; })());

    checkTrue('coil_requires_direct', await rejects(() => f.setCoilCurrents(1, 1)));
    await f.setDirectMode(true);
    await f.setCoilCurrents(100, -100);
    checkTrue('xdirect', sim.lastWrite(0x2D) === ((100 | ((-100 & 0x1FF) << 16)) >>> 0));
    await f.setDcstep(1000, 200, 3);
    checkTrue('dcstep', sim.lastWrite(0x33) === 1000 && sim.lastWrite(0x6E) === (200 | (3 << 16)));
    await f.writeMicrostepTable([1, 2, 3, 4, 5, 6, 7, 8], 0x12345678, 0x00F70000);
    checkTrue('mslut', sim.lastWrite(0x60) === 1 && sim.lastWrite(0x67) === 8 && sim.lastWrite(0x68) === 0x12345678 && sim.lastWrite(0x69) === 0x00F70000);

    sim.regs.set(0x6A, 513);
    sim.regs.set(0x6B, ((0x1FF << 16) | 0x100) >>> 0);
    sim.regs.set(0x12, 1234);
    sim.regs.set(0x71, 77);
    sim.regs.set(0x73, 0x123456);
    sim.regs.set(0x6F, (0x80000000 | (1 << 15) | (17 << 16) | (1 << 25) | (1 << 24) | 300) >>> 0);
    sim.regs.set(0x04, (0x11000000 | 0x40 | 0x1 | 0x10) >>> 0);
    sim.regs.set(0x01, 0x03);
    checkTrue('microstep_position', (await f.microstepPosition()) === 513);
    const cur = await f.readCoilCurrents();
    checkTrue('coil_currents', cur[0] === -1 && cur[1] === -256);
    checkTrue('tstep', (await f.readTstep()) === 1234);
    checkTrue('pwm_scale', (await f.readPwmScale()) === 77);
    checkTrue('lost_steps', (await f.readLostSteps()) === 0x23456);
    checkTrue('current_scale', (await f.readCurrentScale()) === 17);
    const st = await f.readStatus();
    checkTrue('status', st.stst && st.fsactive && st.ot && st.stallguard && st.cs_actual === 17 && st.sg_result === 300 && !st.otpw);
    const gs = await f.readGlobalStatus();
    checkTrue('global_status', gs.reset && gs.drv_err && !gs.uv_cp && sim.regs.get(0x01) === 0);
    const io = await f.readIoin();
    checkTrue('ioin', io.version === 0x11 && io.step && io.enn && !io.dir);
    checkTrue('read_register', (await f.readRegister(0x6A)) === 513);
    checkTrue('read_register_writeonly', await rejects(() => f.readRegister(0x10), RangeError));
    await f.writeRegister(0x13, 5, true);
    checkTrue('write_register', sim.lastWrite(0x13) === 5);
    checkTrue('write_register_encm', await rejects(() => f.writeRegister(0x72, 1), RangeError));
    await f.setInternalRsense(true);
    checkTrue('internal_rsense_current', await rejects(() => f.setCurrent(0.5), RangeError));
    await f.setInternalRsense(false);
    await f.step(3, 0);
    checkTrue('step_pulses', step.highs() === 35 && dir.levels[dir.levels.length - 1] === true);
    await f.step(-2, 0);
    checkTrue('step_reverse', dir.levels[dir.levels.length - 1] === false);

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}
main();
