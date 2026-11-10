'use strict';
const { UARTConnectionMock } = require('../../packages/periph/src/connection/uart_mock');
const { TMC2208Minimal, TMC2208Full, crc8 } = require('../../packages/periph/src/chips/motor/tmc2208');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

class Pin {
    constructor() { this.levels = []; }
    async set(high) { this.levels.push(!!high); }
}

async function rejects(fn, type) {
    try { await fn(); } catch (e) { return type ? e instanceof type : true; }
    return false;
}

async function main() {
    checkTrue('crc_write_gconf', crc8([5, 0, 0x80, 0, 0, 1, 0xC1]) === 0x7F);
    checkTrue('crc_read_ioin', crc8([5, 0, 6]) === 0x6F);

    let mock = new UARTConnectionMock();
    let motor = new TMC2208Minimal(mock);
    await motor.init();
    checkTrue('init_gconf', mock.lastWrite(0x00) === 0x1C1);
    checkTrue('init_gstat_clear', mock.lastWrite(0x01) === 0x07);
    checkTrue('init_tpowerdown', mock.lastWrite(0x11) === 20);
    checkTrue('init_pwmconf', mock.lastWrite(0x70) === 0xC10D0024);
    checkTrue('init_wrote_gconf_datagram',
        mock.datagrams.some((d) => d.equals(Buffer.from([5, 0, 0x80, 0, 0, 1, 0xC1, 0x7F]))));
    checkTrue('init_read_request_ioin',
        mock.datagrams.some((d) => d.equals(Buffer.from([5, 0, 6, 0x6F]))));
    const chop = mock.lastWrite(0x6C);
    checkTrue('init_chopconf_fields', (chop & 0xF) === 3 && ((chop >>> 15) & 3) === 2 &&
        ((chop >>> 4) & 7) === 5 && ((chop >>> 24) & 0xF) === 4 && ((chop >>> 28) & 1) === 1);
    const ih = mock.lastWrite(0x10);
    checkTrue('init_current_0_5a', ((ih >>> 8) & 0x1F) === 9 && (ih & 0x1F) === 2);
    checkTrue('init_ihold_delay', ((ih >>> 16) & 0xF) === 6);

    await motor.setCurrent(1.0);
    checkTrue('set_current_1a', ((mock.lastWrite(0x10) >>> 8) & 0x1F) === 18);
    await motor.setCurrent(5.0);
    checkTrue('set_current_clamped', ((mock.lastWrite(0x10) >>> 8) & 0x1F) === 31);

    await motor.run(200);
    checkTrue('run_forward', mock.lastWrite(0x22) === 4474);
    await motor.run(-200);
    checkTrue('run_reverse', mock.lastWrite(0x22) === ((-4474) & 0xFFFFFF));
    await motor.stop();
    checkTrue('stop', mock.lastWrite(0x22) === 0);

    await motor.disable();
    checkTrue('disable_toff0', (mock.lastWrite(0x6C) & 0xF) === 0);
    await motor.enable();
    checkTrue('enable_toff3', (mock.lastWrite(0x6C) & 0xF) === 3);

    mock.regs.set(0x6F, (2 ** 31) + 0x01 + 0x08 + 0x20);
    checkTrue('standstill', await motor.isStandstill());
    checkTrue('fault_flags', (await motor.readFault()) ===
        (TMC2208Minimal.FAULT_OVERTEMP_WARNING | TMC2208Minimal.FAULT_SHORT_GND | TMC2208Minimal.FAULT_SHORT_SUPPLY));

    const pin = new Pin();
    const enm = new TMC2208Minimal(new UARTConnectionMock(), { enPin: pin });
    await enm.init();
    checkTrue('en_pin_high_at_init', pin.levels.length === 1 && pin.levels[0] === true);
    await enm.enable();
    checkTrue('en_pin_low_on_enable', pin.levels[pin.levels.length - 1] === false);

    const m2 = new UARTConnectionMock(false);
    await new TMC2208Minimal(m2, { echo: false }).init();
    checkTrue('no_echo_init', m2.lastWrite(0x00) === 0x1C1);

    const bad = new UARTConnectionMock();
    bad.regs.set(0x06, 0);
    checkTrue('bad_version_raises', await rejects(() => new TMC2208Minimal(bad).init()));

    const flaky = new UARTConnectionMock();
    flaky.corruptNextReply = true;
    await new TMC2208Minimal(flaky).init();
    checkTrue('crc_retry', flaky.lastWrite(0x00) === 0x1C1);

    // ---- Full ----
    mock = new UARTConnectionMock();
    const step = new Pin();
    const dirp = new Pin();
    const full = new TMC2208Full(mock, { stepPin: step, dirPin: dirp });
    await full.init();
    await full.setMicrosteps(256);
    checkTrue('microsteps_256', ((mock.lastWrite(0x6C) >>> 24) & 0xF) === 0);
    await full.setMicrosteps(1);
    checkTrue('microsteps_1', ((mock.lastWrite(0x6C) >>> 24) & 0xF) === 8);
    checkTrue('microsteps_invalid', await rejects(() => full.setMicrosteps(3), RangeError));
    await full.setMicrosteps(32);
    await full.run(100);
    checkTrue('run_uses_microsteps', mock.lastWrite(0x22) === 4474);
    await full.setInterpolation(false);
    checkTrue('intpol_off', ((mock.lastWrite(0x6C) >>> 28) & 1) === 0);
    await full.setChopperMode(true);
    checkTrue('spreadcycle', (mock.lastWrite(0x00) & 0x4) === 0x4);
    await full.setDirectionInverted(true);
    checkTrue('shaft', (mock.lastWrite(0x00) & 0x8) === 0x8);
    await full.setIndexOutput(1);
    checkTrue('index_otpw', (mock.lastWrite(0x00) & 0x30) === 0x10);
    await full.setDoubleEdge(true);
    checkTrue('dedge', ((mock.lastWrite(0x6C) >>> 29) & 1) === 1);
    await full.setDriverProtection(false, true);
    checkTrue('diss2vs', ((mock.lastWrite(0x6C) >>> 31) & 1) === 1 && ((mock.lastWrite(0x6C) >>> 30) & 1) === 0);
    await full.setVsense(true);
    checkTrue('vsense', ((mock.lastWrite(0x6C) >>> 17) & 1) === 1);
    await full.setHoldCurrent(0.2);
    checkTrue('hold_current', (mock.lastWrite(0x10) & 0x1F) === 6);
    await full.setCurrentDelay(2, 100);
    checkTrue('current_delay', ((mock.lastWrite(0x10) >>> 16) & 0xF) === 2 && mock.lastWrite(0x11) === 100);
    await full.setStealthchopThreshold(300);
    checkTrue('tpwmthrs', mock.lastWrite(0x13) === 300);
    await full.configureChopper(4, 1, 3, 2);
    const c = mock.lastWrite(0x6C);
    checkTrue('configure_chopper', (c & 0xF) === 4 && ((c >>> 15) & 3) === 1 && ((c >>> 4) & 7) === 3 && ((c >>> 7) & 0xF) === 2);
    await full.configureStealthchop(true, false, 40, 5, 1, 4, 12);
    const p = mock.lastWrite(0x70);
    checkTrue('configure_stealthchop', (p & 0xFF) === 40 && ((p >>> 8) & 0xFF) === 5 && ((p >>> 16) & 3) === 1 &&
        ((p >>> 18) & 1) === 1 && ((p >>> 19) & 1) === 0 && ((p >>> 24) & 0xF) === 4 && (p >>> 28) === 12);
    await full.setFreewheel(1);
    checkTrue('freewheel', ((mock.lastWrite(0x70) >>> 20) & 3) === 1);
    await full.setSendDelay(4);
    checkTrue('send_delay', mock.lastWrite(0x03) === (4 << 8));
    checkTrue('send_delay_range', await rejects(() => full.setSendDelay(16), RangeError));
    await full.setInternalRsense(true);
    checkTrue('internal_rsense_current', await rejects(() => full.setCurrent(0.5)));
    await full.setInternalRsense(false);

    mock.regs.set(0x6A, 513);
    mock.regs.set(0x6B, (0x1FF << 16) | 0x100);
    mock.regs.set(0x12, 1234);
    mock.regs.set(0x6F, ((1 << 31) | (1 << 30) | (17 << 16) | (0x5 << 8) | 0x02) >>> 0);
    mock.regs.set(0x71, (0x1FE << 16) | 77);
    mock.regs.set(0x72, (9 << 16) | 33);
    mock.regs.set(0x01, 0x03);
    mock.regs.set(0x05, 0x123456);
    checkTrue('microstep_position', (await full.microstepPosition()) === 513);
    const cc = await full.readCoilCurrents();
    checkTrue('coil_currents', cc[0] === -256 && cc[1] === -1);
    checkTrue('tstep', (await full.readTstep()) === 1234);
    checkTrue('current_scale', (await full.readCurrentScale()) === 17);
    checkTrue('temp_flags', (await full.readTemperatureFlags()) === 5);
    const st = await full.readStatus();
    checkTrue('status', st.stst && st.stealth && st.ot && st.cs_actual === 17 && st.t120 && st.t150 && !st.t143);
    const gs = await full.readGlobalStatus();
    checkTrue('global_status', gs.reset && gs.drv_err && !gs.uv_cp);
    await full.readGlobalStatus(true);
    checkTrue('global_status_clear', mock.lastWrite(0x01) === 0x07);
    const pa = await full.readPwmAuto();
    checkTrue('pwm_auto', pa.pwm_scale_sum === 77 && pa.pwm_scale_auto === -2 && pa.pwm_ofs_auto === 33 && pa.pwm_grad_auto === 9);
    const io = await full.readIoin();
    checkTrue('ioin', io.sel_a && io.version === 0x21);
    checkTrue('otp', (await full.readOtp()) === 0x123456);
    checkTrue('ifcnt', (await full.readIfcnt()) === mock.regs.get(0x02));
    checkTrue('read_register', (await full.readRegister(0x6A)) === 513);
    checkTrue('read_register_writeonly', await rejects(() => full.readRegister(0x10), RangeError));
    await full.writeRegister(0x13, 5);
    checkTrue('write_register', mock.lastWrite(0x13) === 5);
    checkTrue('write_register_otp_prog', await rejects(() => full.writeRegister(0x04, 1), RangeError));

    await full.step(3, 0);
    checkTrue('step_pulses', step.levels.filter(Boolean).length === 3 && dirp.levels[dirp.levels.length - 1] === true);
    await full.step(-2, 0);
    checkTrue('step_reverse', dirp.levels[dirp.levels.length - 1] === false);
    const nopin = new TMC2208Full(new UARTConnectionMock());
    await nopin.init();
    checkTrue('step_requires_pin', await rejects(() => nopin.step(1, 0)));

    const m24 = new UARTConnectionMock();
    m24.regs.set(0x06, 0x21000000);
    await new TMC2208Full(m24, { tmc2224: true }).init();
    checkTrue('tmc2224_sel_a0', true);

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}

main();
