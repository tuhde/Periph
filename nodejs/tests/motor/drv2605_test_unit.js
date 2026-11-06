'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { DRV2605Minimal, DRV2605Full } = require('../../packages/periph/src/chips/motor/drv2605');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (JSON.stringify(got) === JSON.stringify(expected)) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${JSON.stringify(got)}, expected ${JSON.stringify(expected)}`); failed++; }
}

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

// Mock whose DEV_RESET and GO bits self-clear like the real chip.
class ChipMock extends I2CConnectionMock {
    constructor() { super(); this.setRegister(0x00, [0x60]); }
    async write(data) {
        await super.write(data);
        const buf = Buffer.from(data);
        if (buf.length >= 2 && buf[0] === 0x01) this.registers.set(0x01, buf[1] & 0x7F);
        if (buf.length >= 2 && buf[0] === 0x0C) this.registers.set(0x0C, 0);
    }
}

function lastWrite(conn, reg) {
    const w = conn.writes.filter((b) => b.length >= 2 && b[0] === reg);
    return w.length ? Array.from(w[w.length - 1].subarray(1)) : undefined;
}
const lastByte = (conn, reg) => { const v = lastWrite(conn, reg); return v ? v[0] : undefined; };
const wrote = (conn, reg, value) => conn.writes.some((b) => b.length === 2 && b[0] === reg && b[1] === value);

async function make(lra = false, Cls = DRV2605Full) {
    const conn = new ChipMock();
    const chip = new Cls(conn, lra);
    await chip.init();
    return { conn, chip };
}

async function main() {
    // -- init --
    {
        const { conn } = await make(false, DRV2605Minimal);
        checkEq('init_reset_first', Array.from(conn.writes[0]), [0x01, 0x80]);
        checkTrue('init_leaves_standby', wrote(conn, 0x01, 0x00));
        checkEq('init_erm_feedback', lastByte(conn, 0x1A), 0x36);
        checkEq('init_erm_library_a', lastByte(conn, 0x03), 0x01);
    }
    {
        const { conn } = await make(true, DRV2605Minimal);
        checkEq('init_lra_feedback', lastByte(conn, 0x1A), 0xB6);
        checkEq('init_lra_library', lastByte(conn, 0x03), 0x06);
    }
    {
        const conn = new ChipMock();
        conn.setRegister(0x00, [0xE0]);
        checkTrue('init_wrong_device_id', await rejects(() => new DRV2605Minimal(conn).init()));
    }

    // -- minimal playback --
    {
        const { conn, chip } = await make(false, DRV2605Minimal);
        await chip.playEffect(47);
        checkEq('play_effect_slots', lastWrite(conn, 0x04), [47, 0]);
        checkEq('play_effect_go', lastByte(conn, 0x0C), 1);
        checkTrue('play_effect_rejects_0', await rejects(() => chip.playEffect(0)));
        checkTrue('play_effect_rejects_124', await rejects(() => chip.playEffect(124)));
        await chip.stop();
        checkEq('stop_clears_go', lastByte(conn, 0x0C), 0);
        conn.setRegister(0x0C, [1]);
        checkTrue('is_playing_true', await chip.isPlaying());
        conn.setRegister(0x0C, [0]);
        checkTrue('is_playing_false', !(await chip.isPlaying()));
        checkTrue('minimal_has_no_full_api', chip.playSequence === undefined);
    }

    // -- sequence --
    {
        const { conn, chip } = await make();
        await chip.playSequence([1, ['wait', 100], 10]);
        checkEq('sequence_encoding', lastWrite(conn, 0x04), [1, 0x8A, 10, 0]);
        await chip.playSequence([1, 2, 3, 4, 5, 6, 7, 8]);
        checkEq('sequence_eight_no_terminator', lastWrite(conn, 0x04), [1, 2, 3, 4, 5, 6, 7, 8]);
        checkTrue('sequence_too_long', await rejects(() => chip.playSequence(new Array(9).fill(1))));
        checkTrue('sequence_bad_wait', await rejects(() => chip.playSequence([['wait', 2000]])));
        conn.setRegister(0x0C, [0]);
        checkTrue('wait_done_true', await chip.waitDone(50));
        conn.setRegister(0x0C, [1]);
        checkTrue('wait_done_timeout', !(await chip.waitDone(20)));
    }

    // -- mode / bits --
    {
        const { conn, chip } = await make();
        await chip.setMode(5);
        checkEq('set_mode_5', lastByte(conn, 0x01), 0x05);
        await chip.standby();
        checkEq('standby_bit', lastByte(conn, 0x01), 0x45);
        await chip.wake();
        checkEq('wake_bit', lastByte(conn, 0x01), 0x05);
        await chip.setHiZ(true);
        checkEq('hi_z_set', lastByte(conn, 0x03) & 0x10, 0x10);
        await chip.setLibrary(6);
        checkEq('library_preserves_hi_z', lastByte(conn, 0x03), 0x16);
        checkTrue('library_range', await rejects(() => chip.setLibrary(7)));
        conn.setRegister(0x1A, [0x36]);
        await chip.setActuator(true);
        checkEq('set_actuator_lra', lastByte(conn, 0x1A), 0xB6);
    }

    // -- RTP --
    {
        const { conn, chip } = await make();
        await chip.rtpLevel(0.5);
        checkTrue('rtp_level_mode', wrote(conn, 0x01, 0x05));
        checkEq('rtp_level_value', lastByte(conn, 0x02), 64);
        await chip.rtpWrite(200);
        checkEq('rtp_write_raw', lastByte(conn, 0x02), 200);
        await chip.stop();
        checkTrue('stop_leaves_rtp', lastByte(conn, 0x02) === 0 && lastByte(conn, 0x01) === 0x00);
        checkTrue('rtp_level_range', await rejects(() => chip.rtpLevel(1.5)));
        await chip.setRtpFormat(true);
        checkEq('rtp_format_unsigned', lastByte(conn, 0x1D) & 0x08, 0x08);
    }

    // -- loop constraints --
    {
        const { conn, chip } = await make();
        conn.setRegister(0x1D, [0x20]);
        checkTrue('unidirectional_rejected_open_loop', await rejects(() => chip.setBidirectional(false)));
        await chip.setOpenLoop(false);
        checkEq('erm_open_loop_clear', lastByte(conn, 0x1D), 0x00);
        await chip.setBidirectional(false);
        checkEq('unidirectional_closed_loop', lastByte(conn, 0x1C) & 0x80, 0);
    }

    // -- voltage conversions --
    {
        const { conn, chip } = await make();
        conn.setRegister(0x1A, [0x36]);
        await chip.setRatedVoltage({ volts: 3.0 });
        checkEq('rated_erm_3v', lastByte(conn, 0x16), 141);
        await chip.setRatedVoltage({ code: 77 });
        checkEq('rated_raw_code', lastByte(conn, 0x16), 77);
        checkTrue('rated_needs_one_arg', await rejects(() => chip.setRatedVoltage({})));
        conn.setRegister(0x1D, [0x20]);
        await chip.setOverdriveClamp({ volts: 3.0 });
        checkEq('clamp_erm_open_3v', lastByte(conn, 0x17), 137);
        conn.setRegister(0x1D, [0x00]);
        checkTrue('clamp_erm_closed_raises', await rejects(() => chip.setOverdriveClamp({ volts: 3.0 })));
        conn.setRegister(0x1A, [0xB6]);
        conn.setRegister(0x1C, [0x30]);
        await chip.setRatedVoltage({ volts: 2.0, lraFreqHz: 200 });
        checkEq('rated_lra_2v_200hz', lastByte(conn, 0x16), 81);
        checkTrue('rated_lra_needs_freq', await rejects(() => chip.setRatedVoltage({ volts: 2.0 })));
        await chip.setDriveTime(2.5);
        checkEq('drive_time_lra', lastByte(conn, 0x1B) & 0x1F, 20);
        conn.setRegister(0x1A, [0x36]);
        await chip.setDriveTime(5.0);
        checkEq('drive_time_erm', lastByte(conn, 0x1B) & 0x1F, 20);
    }

    // -- feedback / offsets --
    {
        const { conn, chip } = await make();
        conn.setRegister(0x1A, [0x80]);
        await chip.setFeedback(2, 2, 1);
        checkEq('feedback_preserves_type', lastByte(conn, 0x1A), 0x80 | (2 << 4) | (2 << 2) | 1);
        await chip.setTimeOffsets({ overdriveMs: 10, sustainPosMs: -5, sustainNegMs: 0, brakeMs: -640 });
        checkEq('time_offsets_signed', lastWrite(conn, 0x0D), [2, 0xFF, 0, 0x80]);
        checkTrue('time_offsets_range', await rejects(() => chip.setTimeOffsets({ brakeMs: 700 })));
    }

    // -- calibration --
    {
        const { conn, chip } = await make();
        conn.setRegister(0x18, [0x0D, 0x6D]);
        conn.setRegister(0x1A, [0x36]);
        const r = await chip.calibrate(false, 3.0, 3.0);
        checkTrue('calibrate_enters_mode7', wrote(conn, 0x01, 0x07));
        checkTrue('calibrate_sets_inputs', lastByte(conn, 0x16) === 141 && lastByte(conn, 0x17) === 137);
        checkEq('calibrate_auto_cal_time', lastByte(conn, 0x1E) & 0x30, 0x30);
        checkTrue('calibrate_go', wrote(conn, 0x0C, 0x01));
        checkEq('calibrate_result', r, { ok: true, comp: 0x0D, bemf: 0x6D, bemfGain: 2 });
        checkEq('calibrate_restores_mode0', lastByte(conn, 0x01), 0x00);
        conn.setRegister(0x00, [0x68]);
        checkTrue('calibrate_failure_flag', !(await chip.calibrate(false, 3.0, 3.0)).ok);
        conn.setRegister(0x00, [0x60]);
        checkTrue('calibrate_lra_needs_freq', await rejects(() => chip.calibrate(true, 2.0, 2.5)));
        await chip.calibrate(true, 2.0, 2.5, 200);
        checkEq('calibrate_lra_feedback', lastByte(conn, 0x1A) & 0x80, 0x80);
        await chip.setCalibration(0x20, 0x80, 3);
        checkTrue('set_calibration', JSON.stringify(lastWrite(conn, 0x18)) === '[32,128]' && (lastByte(conn, 0x1A) & 0x03) === 3);
        checkEq('get_calibration', await chip.getCalibration(), { comp: 0x20, bemf: 0x80, bemfGain: 3 });
    }

    // -- diagnose --
    {
        const { conn, chip } = await make();
        conn.setRegister(0x0C, [0]);
        checkTrue('diagnose_ok', (await chip.diagnose()) && wrote(conn, 0x01, 0x06));
        conn.setRegister(0x00, [0x68]);
        checkTrue('diagnose_fail', !(await chip.diagnose()));
    }

    // -- audio to vibe --
    {
        const { conn, chip } = await make();
        await chip.setAudioToVibe({ peakTimeMs: 30, filterHz: 150, minInputV: 0, maxInputV: 1.8, minDrive: 0, maxDrive: 1 });
        checkEq('atv_registers', lastWrite(conn, 0x11), [(2 << 2) | 2, 0, 255, 0, 255]);
        checkEq('atv_mode4', lastByte(conn, 0x01), 0x04);
        checkEq('atv_ac_couple', lastByte(conn, 0x1B) & 0x20, 0x20);
        checkTrue('atv_bad_filter', await rejects(() => chip.setAudioToVibe({ filterHz: 300 })));
    }

    // -- status / readouts --
    {
        const { conn, chip } = await make();
        conn.setRegister(0x00, [0x60 | 0x08 | 0x01]);
        checkEq('read_status', await chip.readStatus(), { diagFailed: true, fbTimeout: false, overTemp: false, overCurrent: true });
        checkEq('read_device_id', await chip.readDeviceId(), 3);
        conn.setRegister(0x21, [255]);
        checkTrue('supply_voltage', Math.abs((await chip.readSupplyVoltage()) - 5.6) < 1e-9);
        conn.setRegister(0x22, [0]);
        checkEq('lra_freq_none', await chip.readLraFrequency(), null);
        conn.setRegister(0x22, [52]);
        checkTrue('lra_freq', Math.abs((await chip.readLraFrequency()) - 195.3) < 0.2);
    }

    // -- OTP --
    {
        const { conn, chip } = await make();
        const n = conn.writes.length;
        checkTrue('otp_requires_confirm', (await rejects(() => chip.programOtp())) && conn.writes.length === n);
        conn.setRegister(0x1E, [0x04]);
        checkTrue('otp_status', await chip.otpProgrammed());
        checkTrue('otp_already_programmed', await rejects(() => chip.programOtp(true)));
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
