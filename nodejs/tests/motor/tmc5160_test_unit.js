'use strict';
const { TMC5160Minimal, TMC5160Full, crc8 } = require('../../packages/periph/src/chips/motor/tmc5160');

let passed = 0;
let failed = 0;
function ok(label, c) { if (c) { console.log('PASS', label); passed++; } else { console.log('FAIL', label); failed++; } }
async function rejects(fn, type) { try { await fn(); } catch (e) { return type ? e instanceof type : true; } return false; }
const vReg = (v) => Math.round(v * 256 * 16777216 / 12e6);
const aReg = (a) => Math.round(a * 256 * 2199023255552 / 144e12);

class Chip {
    constructor() { this.regs = new Map([[0x04, 0x30000000], [0x01, 1], [0x02, 0]]); this.writes = []; }
    last(reg) { const w = this.writes.filter((x) => x[0] === reg); return w.length ? w[w.length - 1][1] : undefined; }
    store(reg, v) { this.regs.set(reg, v >>> 0); this.writes.push([reg, v >>> 0]); }
}
class SpiSim extends Chip {
    constructor() { super(); this.pending = 0; this.mirrorBroken = false; this.frames = []; }
    _dg(frame) {
        const f = Buffer.concat([Buffer.from(frame), Buffer.alloc(5 - frame.length)]);
        this.frames.push(f);
        const reply = this.pending;
        const reg = f[0] & 0x7F;
        if (f[0] & 0x80) {
            const v = f.readUInt32BE(1);
            this.store(reg, v);
            this.pending = this.mirrorBroken ? 0 : v;
        } else {
            this.pending = this.regs.get(reg) ?? 0;
        }
        return reply;
    }
    async write(d) { if (d.length !== 5) throw new Error('bad frame'); this._dg(d); }
    async writeRead(d, n) { if (n !== 4) throw new Error('bad n'); const b = Buffer.alloc(4); b.writeUInt32BE(this._dg(d) >>> 0); return b; }
}
class UartSim extends Chip {
    constructor(echo = true) { super(); this.echo = echo; this.rx = []; this.datagrams = []; this.corrupt = false; }
    async write(d) {
        d = Buffer.from(d);
        this.datagrams.push(d);
        if (this.echo) this.rx.push(...d);
        if (d.length === 8 && (d[2] & 0x80)) {
            if (crc8(d.subarray(0, 7)) !== d[7]) throw new Error('bad crc');
            this.store(d[2] & 0x7F, d.readUInt32BE(3));
            this.regs.set(0x02, (this.regs.get(0x02) + 1) & 0xFF);
        } else if (d.length === 4) {
            const reg = d[2] & 0x7F;
            const body = Buffer.alloc(7);
            body[0] = 5; body[1] = 0xFF; body[2] = reg;
            body.writeUInt32BE((this.regs.get(reg) ?? 0) >>> 0, 3);
            const reply = Buffer.concat([body, Buffer.from([crc8(body)])]);
            if (this.corrupt) { reply[7] ^= 0xFF; this.corrupt = false; }
            this.rx.push(...reply);
        }
    }
    async read(n) { return Buffer.from(this.rx.splice(0, n)); }
    async writeRead(d, n) { await this.write(d); return this.read(n); }
    async flush() { this.rx = []; }
}
class Pin { constructor() { this.levels = []; } async set(h) { this.levels.push(!!h); } }

async function main() {
    ok('crc_write_gconf', crc8([5, 0, 0x80, 0, 0, 0, 4]) === 0xA9);
    ok('crc_read_ioin', crc8([5, 0, 4]) === 0xA8);

    let sim = new SpiSim();
    let m = new TMC5160Minimal(sim);
    await m.init();
    ok('spi_chopconf_frame', sim.frames.some((f) => f.equals(Buffer.from([0xEC, 0, 1, 0, 0xC3]))));
    ok('spi_init_regs', sim.last(0x01) === 7 && sim.last(0x11) === 10 && sim.last(0x00) === 4 && sim.last(0x13) === 500);
    ok('spi_init_ramp', sim.last(0x20) === 0 && sim.last(0x2B) === 10 && sim.last(0x25) === 0 && sim.last(0x23) === 0);
    ok('spi_default_speed_accel', sim.last(0x27) === 71583 && [0x24, 0x26, 0x28, 0x2A].every((r) => sim.last(r) === 1564));
    ok('spi_default_current', sim.last(0x0B) === 128 && ((sim.last(0x10) >>> 8) & 0x1F) === 9 && (sim.last(0x10) & 0x1F) === 3);
    await m.setCurrent(2.0);
    ok('set_current_2a', sim.last(0x0B) === 167 && ((sim.last(0x10) >>> 8) & 0x1F) === 31 && (sim.last(0x10) & 0x1F) === 10);
    await m.setCurrent(3.06);
    ok('scaler_256_is_0', sim.last(0x0B) === 0);
    ok('set_current_range', await rejects(() => m.setCurrent(3.5), RangeError) && await rejects(() => m.setCurrent(0.01), RangeError));
    await m.setSpeed(300);
    ok('set_speed', sim.last(0x27) === vReg(300) && await rejects(() => m.setSpeed(0), RangeError));
    await m.setAcceleration(800);
    ok('set_accel', sim.last(0x26) === 3127 && sim.last(0x2A) === 3127);

    sim.regs.set(0x21, 2560);
    await m.moveTo(25);
    ok('move_to', sim.last(0x2D) === 6400 && sim.last(0x20) === 0);
    await m.moveTo(-2);
    ok('move_to_negative', sim.last(0x2D) === ((-512) >>> 0));
    sim.regs.set(0x35, 1 << 9);
    await m.move(5);
    ok('move_relative', sim.last(0x2D) === 15 * 256);
    sim.regs.set(0x35, 0);
    ok('move_timeout', await rejects(() => m.waitUntilReached(0.01)));
    await m.run(150);
    ok('run_forward', sim.last(0x20) === 1 && sim.last(0x27) === vReg(150));
    await m.run(-150);
    ok('run_reverse', sim.last(0x20) === 2);
    sim.regs.set(0x35, 1 << 10);
    ok('wait_vzero', await m.waitUntilReached(0.1));
    await m.stop();
    ok('stop', sim.last(0x20) === 1 && sim.last(0x27) === 0);
    await m.moveTo(0);
    ok('speed_restored', sim.last(0x27) === vReg(300));
    sim.regs.set(0x21, (-1280) >>> 0);
    ok('position', (await m.position()) === -5);
    await m.setPosition(7);
    ok('set_position', sim.last(0x21) === 1792 && sim.last(0x2D) === 1792);
    sim.regs.set(0x22, 71583);
    ok('velocity', Math.abs((await m.velocity()) - 71583 * (6e6 / 8388608) / 256) < 1e-6);
    sim.regs.set(0x35, 1 << 9);
    ok('is_position_reached', await m.isPositionReached());
    sim.regs.set(0x6F, ((1 << 31) | (1 << 26) | (1 << 28) | (1 << 12) | (1 << 30)) >>> 0);
    ok('standstill_fault', (await m.isStandstill()) && (await m.readFault()) === (0x01 | 0x04 | 0x08 | 0x10));

    const en = new Pin();
    const m2 = new TMC5160Minimal(new SpiSim(), { enPin: en });
    await m2.init();
    ok('en_pin_high_at_init', en.levels.length === 1 && en.levels[0] === true);
    await m2.enable();
    ok('en_pin_low_on_enable', en.levels[en.levels.length - 1] === false);
    const m3 = new TMC5160Minimal(new SpiSim());
    await m3.init();
    await m3.disable();
    ok('disable_toff0', (m3._conn.last(0x6C) & 0xF) === 0);
    await m3.enable();
    ok('enable_toff3', (m3._conn.last(0x6C) & 0xF) === 3);

    for (const [label, fn] of [['bad_version', (s) => s.regs.set(0x04, 0)], ['sd_mode', (s) => s.regs.set(0x04, 0x30000040)],
        ['broken_mirror', (s) => { s.mirrorBroken = true; }]]) {
        const s = new SpiSim();
        fn(s);
        ok(label + '_raises', await rejects(() => new TMC5160Minimal(s).init()));
    }
    ok('bus_type_invalid', await rejects(async () => new TMC5160Minimal(new SpiSim(), { busType: 'i2c' }), RangeError));

    let u = new UartSim();
    let um = new TMC5160Minimal(u, { busType: 'uart', address: 1, multiNode: true });
    await um.init();
    ok('uart_address', u.datagrams.every((d) => d[1] === 1));
    ok('uart_slaveconf_first', u.writes[0][0] === 3 && u.writes[0][1] === (2 << 8));
    ok('uart_ifcnt', u.regs.get(0x02) === u.writes.length);
    const flaky = new UartSim();
    flaky.corrupt = true;
    await new TMC5160Minimal(flaky, { busType: 'uart' }).init();
    ok('uart_crc_retry', flaky.last(0x00) === 4);
    const ne = new UartSim(false);
    await new TMC5160Minimal(ne, { busType: 'uart', echo: false }).init();
    ok('uart_no_echo', ne.last(0x00) === 4);
    ok('uart_diag_rejected', await rejects(async () => new TMC5160Full(new UartSim(), { busType: 'uart', diag0Pin: { isHigh: () => true } }), RangeError));

    sim = new SpiSim();
    const f = new TMC5160Full(sim, { diag0Pin: { isHigh: () => true } });
    await f.init();
    await f.configureRamp(0, 1200, 200, 600, 500, 900, 1500, 10);
    ok('configure_ramp', sim.last(0x25) === vReg(200) && sim.last(0x2B) === vReg(10) && sim.last(0x24) === aReg(1200));
    ok('configure_ramp_errors', await rejects(() => f.configureRamp(0, 1, 0, 1, 1, 1, 0, 10), RangeError) &&
        await rejects(() => f.configureRamp(0, 1, 0, 1, 1, 1, 1, 0), RangeError) && await rejects(() => f.configureRamp(100, 1, 0, 1, 1, 1, 1, 10), RangeError));
    await f.setRampRaw(1, 2, 3, 4, 5, 6, 7, 8);
    ok('set_ramp_raw', sim.last(0x23) === 1 && sim.last(0x2B) === 8 && sim.last(0x27) === 5);
    await f.setZeroWait(0.5);
    ok('zero_wait', sim.last(0x2C) === Math.round(0.5 * 12e6 / 512));
    await f.setRampMode(3);
    ok('ramp_mode', sim.last(0x20) === 3 && await rejects(() => f.setRampMode(4), RangeError));
    await f.hold();
    await f.setTargetRaw(1234);
    ok('target_raw', sim.last(0x2D) === 1234);
    sim.regs.set(0x35, (1 << 6) | (1 << 9) | 1);
    const rs = await f.readRampStatus();
    ok('ramp_status', rs.event_stop_sg && rs.position_reached && rs.status_stop_l && !rs.vzero);
    await f.clearRampEvents();
    ok('clear_ramp_events', sim.last(0x35) === ((1 << 2) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 12)));
    await f.setPositionCompare(3);
    ok('x_compare', sim.last(0x05) === 768);
    await f.configureSwitches({ stopLeft: true, stopRight: true, leftActiveLow: true, swap: true });
    ok('switches', (sim.last(0x34) & 0x1F) === 0b10111);
    await f.configureLatch({ leftActive: true, rightInactive: true, latchEncoder: true });
    ok('latch_keeps_switches', sim.last(0x34) === (0b10111 | (1 << 5) | (1 << 8) | (1 << 9)));
    await f.configureSwitches({ softStop: true });
    ok('soft_stop_vs_sg', (sim.last(0x34) & (1 << 11)) !== 0 && await rejects(() => f.enableStallStop(true), RangeError));
    await f.configureSwitches({});
    await f.enableStallStop(true);
    ok('sg_stop', (sim.last(0x34) & (1 << 10)) !== 0 && await rejects(() => f.configureSwitches({ softStop: true }), RangeError));
    await f.enableStallStop(false);
    sim.regs.set(0x36, (-512) >>> 0);
    ok('latched_position', (await f.readLatchedPosition()) === -2);
    sim.regs.set(0x35, 3);
    const sw = await f.readSwitches();
    ok('read_switches', sw[0] && sw[1]);

    await f.setMicrosteps(16);
    ok('microsteps', ((sim.last(0x6C) >>> 24) & 0xF) === 4 && f._usc === 16 && await rejects(() => f.setMicrosteps(3), RangeError));
    await f.setMicrosteps(256);
    await f.setInterpolation(true);
    await f.setChopperMode(true);
    ok('intpol_spread', ((sim.last(0x6C) >>> 28) & 1) === 1 && (sim.last(0x00) & 4) === 0);
    await f.setStealthchopThreshold(300);
    await f.setHighVelocityThreshold(77);
    ok('thresholds', sim.last(0x13) === 300 && sim.last(0x15) === 77);
    await f.configureChopper(4, 1, 3, 2, 5);
    let c = sim.last(0x6C);
    ok('configure_chopper', (c & 0xF) === 4 && ((c >>> 15) & 3) === 1 && ((c >>> 4) & 7) === 3 && ((c >>> 7) & 0xF) === 2 && ((c >>> 20) & 0xF) === 5 && !((c >>> 14) & 1));
    await f.configureConstantOffTime(5, 2, 11, -3, true, 1);
    c = sim.last(0x6C);
    ok('configure_const_off', (c & 0xF) === 5 && ((c >>> 14) & 1) && ((c >>> 4) & 7) === 3 && ((c >>> 11) & 1) && ((c >>> 7) & 0xF) === 0 && ((c >>> 12) & 1) && ((c >>> 20) & 0xF) === 1);
    await f.configureHighVelocity(true, true);
    ok('high_velocity', ((sim.last(0x6C) >>> 18) & 3) === 3);
    await f.configureStealthchop(true, true, 40, 5, 1, 4, 12);
    let p = sim.last(0x70);
    ok('configure_stealthchop', (p & 0xFF) === 40 && ((p >>> 8) & 0xFF) === 5 && ((p >>> 16) & 3) === 1 && ((p >>> 18) & 3) === 3 && ((p >>> 24) & 0xF) === 4 && (p >>> 28) === 12);
    await f.setFreewheel(1);
    ok('freewheel', ((sim.last(0x70) >>> 20) & 3) === 1);
    await f.setShortProtection(false, true);
    ok('short_protection', ((sim.last(0x6C) >>> 30) & 1) === 1 && ((sim.last(0x6C) >>> 31) & 1) === 0);
    await f.setDoubleEdge(true);
    await f.setDirectionInverted(true);
    await f.setFastStandstill(true);
    await f.setRecalibrate(true);
    await f.setSmallHysteresis(true);
    await f.setEmergencyStop(true);
    const want = (1 << 4) | (1 << 1) | 1 | (1 << 14) | (1 << 15);
    ok('gconf_flags', ((sim.last(0x6C) >>> 29) & 1) === 1 && (sim.last(0x00) & want) === want);
    await f.configureDiag({ diag0StepOutput: true, diag1DirOutput: true, diag0Pushpull: true });
    const g = sim.last(0x00);
    ok('diag_bits', ((g >>> 7) & 1) && ((g >>> 8) & 1) && ((g >>> 12) & 1) && !((g >>> 13) & 1));
    await f.setStallThreshold(-5);
    await f.setStallguardFilter(true);
    await f.configureCoolstep(5, 2, 1, 1, 0);
    const cc = sim.last(0x6D);
    ok('coolconf', (cc & 0xF) === 5 && ((cc >>> 8) & 0xF) === 2 && ((cc >>> 24) & 1) && ((cc >>> 16) & 0x7F) === (-5 & 0x7F));
    ok('stall_range', await rejects(() => f.setStallThreshold(64), RangeError) && await rejects(() => f.configureCoolstep(16, 0, 0, 0, 0), RangeError));
    await f.setCoolstepThreshold(500);
    ok('tcoolthrs', sim.last(0x14) === 500);
    sim.regs.set(0x6F, ((1 << 24) | (17 << 16) | 0x2A | (1 << 14)) >>> 0);
    ok('stallguard', (await f.readStallguard()) === 0x2A && (await f.isStalled()));
    ok('read_diag', f.readDiag(0) === true && (() => { try { f.readDiag(1); } catch (e) { return true; } return false; })() &&
        (() => { try { f.readDiag(2); } catch (e) { return e instanceof RangeError; } return false; })());
    await f.setDcstep(1000, 200, 3);
    ok('dcstep', sim.last(0x33) === 1000 && sim.last(0x6E) === (200 | (3 << 16)));
    ok('coil_requires_direct', await rejects(() => f.setCoilCurrents(1, 1)));
    await f.setDirectMode(true);
    await f.setCoilCurrents(100, -100);
    ok('xdirect', sim.last(0x2D) === ((100 | ((-100 & 0x1FF) << 16)) >>> 0));
    await f.setDirectMode(false);
    await f.writeMicrostepTable([1, 2, 3, 4, 5, 6, 7, 8], 0x12345678, 0x00F70000);
    ok('mslut', sim.last(0x60) === 1 && sim.last(0x67) === 8 && sim.last(0x69) === 0x00F70000);
    await f.setGlobalScaler(256);
    ok('global_scaler', sim.last(0x0B) === 0 && await rejects(() => f.setGlobalScaler(20), RangeError));
    await f.setCurrentScale(20, 5, 3);
    ok('current_scale', sim.last(0x10) === (5 | (20 << 8) | (3 << 16)));
    await f.setPowerDownDelay(20);
    ok('tpowerdown', sim.last(0x11) === 20);
    await f.setHoldCurrent(0.1);
    ok('hold_current', (sim.last(0x10) & 0x1F) < 20);
    await f.configureGateDriver(2, 4, 1, 2, 1);
    ok('drv_conf', sim.last(0x0A) === (2 | (4 << 8) | (1 << 16) | (2 << 18) | (1 << 20)) && await rejects(() => f.configureGateDriver(25, 0, 0, 0, 0), RangeError));
    await f.configureShortDetection(6, 6, 1, 0);
    ok('short_conf', sim.last(0x09) === (6 | (6 << 8) | (1 << 16)) && await rejects(() => f.configureShortDetection(3, 6, 1, 0), RangeError));
    await f.configureEncoder(true, false, true, false, false, false, true, false, false, false, false);
    ok('encmode', sim.last(0x38) === (1 | 4 | 64));
    await f.setEncoderConstant(1.5);
    ok('enc_const_binary', sim.last(0x3A) === 0x00018000);
    await f.setEncoderConstant(-1.0);
    ok('enc_const_negative', sim.last(0x3A) === 0xFFFF0000);
    await f.configureEncoder(false, false, false, false, false, false, false, false, false, false, true);
    await f.setEncoderConstant(2.5);
    ok('enc_const_decimal', sim.last(0x3A) === ((2 << 16) | 5000));
    await f.setEncoderPosition(-3);
    sim.regs.set(0x3C, 77);
    sim.regs.set(0x3B, 2);
    const es = await f.readEncoderStatus();
    ok('encoder_reads', (await f.readEncoderPosition()) === -3 && (await f.readEncoderLatch()) === 77 && !es.n_event && es.deviation_warn);
    await f.setEncoderDeviation(5);
    await f.clearEncoderStatus();
    ok('encoder_dev_clear', sim.last(0x3D) === 5 && sim.last(0x3B) === 3);
    ok('uart_only_on_spi', await rejects(() => f.setSlaveAddress(1)) && await rejects(() => f.readIfcnt()) &&
        await rejects(() => f.setSendDelay(2)) && await rejects(() => f.setNaoPolarity(true)));

    sim.regs.set(0x6A, 513);
    sim.regs.set(0x6B, ((0x1FF << 16) | 0x100) >>> 0);
    sim.regs.set(0x12, 1234);
    sim.regs.set(0x71, ((0x1FE << 16) | 77) >>> 0);
    sim.regs.set(0x72, (9 << 16) | 33);
    sim.regs.set(0x0C, (0xFF << 8) | 0x05);
    sim.regs.set(0x6F, ((1 << 31) | (1 << 14) | (1 << 15) | (17 << 16) | (1 << 25) | (1 << 12) | 300) >>> 0);
    sim.regs.set(0x01, 3);
    sim.regs.set(0x04, 0x30000000 | 0x41 | 0x10);
    ok('microstep_position', (await f.microstepPosition()) === 513);
    const cur = await f.readCoilCurrents();
    ok('coil_currents', cur[0] === -1 && cur[1] === -256);
    ok('tstep', (await f.readTstep()) === 1234);
    const ps = await f.readPwmScale();
    ok('pwm_scale', ps[0] === 77 && ps[1] === -2);
    const pa = await f.readPwmAuto();
    ok('pwm_auto', pa[0] === 33 && pa[1] === 9);
    const off = await f.readOffset();
    ok('offset', off[0] === -1 && off[1] === 5);
    ok('current_scale_read', (await f.readCurrentScale()) === 17);
    const st = await f.readStatus();
    ok('status', st.stst && st.stealth && st.fsactive && st.ot && st.short_supply && st.cs_actual === 17 && st.sg_result === 300 && !st.otpw);
    const gs = await f.readGlobalStatus();
    ok('global_status', gs.reset && gs.drv_err && !gs.uv_cp && sim.regs.get(0x01) === 3);
    await f.clearGlobalStatus();
    ok('clear_global_status', sim.last(0x01) === 7);
    const io = await f.readIoin();
    ok('ioin', io.version === 0x30 && io.refl && io.drv_enn && io.sd_mode);
    ok('read_register', (await f.readRegister(0x6A)) === 513 && await rejects(() => f.readRegister(0x10), RangeError));
    await f.writeRegister(0x13, 5, true);
    ok('write_register', sim.last(0x13) === 5 && await rejects(() => f.writeRegister(0x06, 1), RangeError));

    u = new UartSim();
    const uf = new TMC5160Full(u, { busType: 'uart', address: 2 });
    await uf.init();
    await uf.setSlaveAddress(3);
    ok('uart_slave_address', u.last(0x03) === 3 && uf._address === 3);
    await uf.setSendDelay(2);
    ok('uart_send_delay', u.last(0x03) === (3 | (2 << 8)));
    await uf.setNaoPolarity(false);
    ok('uart_nao', u.last(0x04) === 0);
    await uf.writeRegister(0x13, 9, true);
    ok('uart_verified_write', u.last(0x13) === 9);
    ok('uart_ifcnt_read', (await uf.readIfcnt()) === u.regs.get(0x02));
    ok('uart_slave_range', await rejects(() => uf.setSlaveAddress(254), RangeError));

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}
main();
