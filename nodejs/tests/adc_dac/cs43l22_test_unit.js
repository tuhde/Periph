'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { CS43L22Minimal, CS43L22Full, clockingFor, OUT, FMT, BEEP, SRC, GAIN } = require('../../packages/periph/src/chips/adc_dac/cs43l22');

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

const hex = (w) => Array.from(w).join(',');

class Pin {
    constructor() { this.levels = []; }
    async set(high) { this.levels.push(high); }
}

async function make(pin = null, rev = 1) {
    const mock = new I2CConnectionMock();
    mock.setRegister(0x01, [0xE0 | rev]);
    mock.setRegister(0x32, [0x3B]);
    const chip = new CS43L22Full(mock, pin);
    await chip.init();
    return { mock, chip };
}

async function main() {
    // --- init ---
    const pin = new Pin();
    const { mock, chip } = await make(pin);
    const r = (n) => mock.registers.get(n) || 0;
    checkTrue('reset_pulse', pin.levels.length === 2 && pin.levels[0] === false && pin.levels[1] === true);
    checkTrue('init_power_down_first', hex(mock.writes[1]) === '2,1');
    checkTrue('init_defaults', [0x04, 0x05, 0x06, 0x0D, 0x20, 0x21].map(r).join() === '175,160,4,96,0,0');
    const req = mock.writes.filter((w) => w.length === 2 && [0x00, 0x47, 0x32].includes(w[0])).map(hex);
    checkTrue('init_required_sequence', req.join('|') === '0,153|71,128|50,187|50,59|0,0');
    checkTrue('init_not_powered_up', r(0x02) === 0x01);
    {
        const bad = new I2CConnectionMock();
        checkTrue('bad_id', await rejects(() => new CS43L22Minimal(bad).init()));
    }
    {
        const c = new I2CConnectionMock();
        new CS43L22Minimal(c);
        checkTrue('ctor_no_traffic', c.writes.length === 0);
    }

    // --- start / stop ---
    await chip.start();
    checkTrue('start_9e', r(0x02) === 0x9E);
    mock.setRegister(0x0E, [0x03]);
    mock.setRegister(0x0A, [0x0F]);
    await chip.stop();
    checkTrue('stop_9f', r(0x02) === 0x9F);
    checkTrue('stop_mutes', (r(0x0F) & 0xF0) === 0xF0);
    checkTrue('stop_clears_ramp_zc', (r(0x0E) & 3) === 0 && (r(0x0A) & 0x0F) === 0);

    // --- volume ---
    await chip.setVolume(0);
    checkTrue('vol_0', r(0x20) === 0 && r(0x21) === 0);
    await chip.setVolume(12);
    checkTrue('vol_12', r(0x20) === 0x18);
    await chip.setVolume(-102);
    checkTrue('vol_m102', r(0x20) === 0x34);
    await chip.setVolume(-500);
    checkTrue('vol_clamp_low', r(0x21) === 0x34);
    await chip.setVolume(-0.5);
    checkTrue('vol_m0_5', r(0x20) === 0xFF && (await chip.getVolume()) === -0.5);
    await chip.setVolumeChannels(-10, 3);
    checkTrue('vol_channels', r(0x20) === 0xEC && r(0x21) === 0x06);
    await chip.setHeadphoneVolume(-20);
    checkTrue('hp_vol_same', r(0x22) === 0xD8 && r(0x23) === 0xD8);
    await chip.setHeadphoneVolume(-96, -100);
    checkTrue('hp_vol_mute', r(0x22) === 0x40 && r(0x23) === 0x01);
    await chip.setSpeakerVolume(5, -1);
    checkTrue('spk_vol_clamp', r(0x24) === 0x00 && r(0x25) === 0xFE);
    await chip.setPcmVolume(-51.5, 12);
    checkTrue('pcm_vol', r(0x1A) === 0x19 && r(0x1B) === 0x18);
    await chip.mutePcm(true, false);
    checkTrue('pcm_mute', r(0x1A) === 0x99 && r(0x1B) === 0x18);
    await chip.setPcmVolume(0);
    checkTrue('pcm_vol_keeps_mute', r(0x1A) === 0x80);
    await chip.setPassthroughVolume(-60, 12);
    checkTrue('pass_vol', r(0x14) === 0x88 && r(0x15) === 0x18);

    // --- mute / outputs ---
    mock.setRegister(0x0F, [0x00]);
    await chip.mute(true);
    checkTrue('mute_hp', r(0x0F) === 0xC0);
    await chip.mute(false);
    checkTrue('unmute_hp', r(0x0F) === 0x00);
    await chip.muteOutputs(null, true);
    checkTrue('mute_spk_only', r(0x0F) === 0x30);
    await chip.muteOutputs(true, false);
    checkTrue('mute_hp_unmute_spk', r(0x0F) === 0xC0);
    await chip.setOutputs(OUT.ON, OUT.OFF);
    checkTrue('outputs', r(0x04) === 0xAF);
    await chip.setOutputs(0, 1);
    checkTrue('outputs_follow', r(0x04) === 0x05);
    checkTrue('outputs_invalid', await rejects(() => chip.setOutputs(4, 0)));
    await chip.setHeadphoneGain(GAIN.G_1_000);
    checkTrue('hp_gain', r(0x0D) === 0xC0 && (await chip.headphoneGain()) === 1.0);
    checkTrue('hp_gain_invalid', await rejects(() => chip.setHeadphoneGain(8)));
    await chip.setSpeakerMode(true, false, true);
    checkTrue('spk_mode', (r(0x0F) & 7) === 3);

    // --- clocking / interface ---
    await chip.setClocking();
    checkTrue('clocking_default', r(0x05) === 0xA0);
    await chip.setClocking({ auto: false, speed: 3, group32k: true, videoClk: true, ratio: 3, mclkDiv2: true });
    checkTrue('clocking_all', r(0x05) === 0x7F);
    checkTrue('clocking_invalid', await rejects(() => chip.setClocking({ speed: 4 })));
    const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
    checkTrue('table_12288_48k', eq(clockingFor(12288000, 48000), { auto: false, speed: 1, group32k: false, videoClk: false, ratio: 0, mclkDiv2: false }));
    checkTrue('table_12288_8k', clockingFor(12288000, 8000).speed === 3 && clockingFor(12288000, 8000).group32k);
    checkTrue('table_11289_44k', clockingFor(11289600, 44100).ratio === 0);
    checkTrue('table_12m_48k', clockingFor(12000000, 48000).ratio === 1);
    checkTrue('table_24m_44k_frac', eq(clockingFor(24000000, 44117.647), { auto: false, speed: 1, group32k: false, videoClk: false, ratio: 3, mclkDiv2: true }));
    checkTrue('table_27m_16k', eq(clockingFor(27000000, 16000), { auto: false, speed: 2, group32k: true, videoClk: true, ratio: 1, mclkDiv2: false }));
    checkTrue('table_16934_8018', clockingFor(16934400, 8018.18).ratio === 2);
    checkTrue('table_unsupported', await rejects(async () => clockingFor(12288000, 44100)));
    checkTrue('table_static', CS43L22Full.clockingFor(12288000, 96000).speed === 0);
    await chip.setInterface();
    checkTrue('iface_default', r(0x06) === 0x04 && r(0x07) === 0x00);
    await chip.setInterface({ master: true, fmt: FMT.RJ, wordLength: 2, invertSclk: true, sclkEqMclk: true, invertSwitch: true });
    checkTrue('iface_all', r(0x06) === 0xCA && r(0x07) === 0x48);
    await chip.setInterface({ fmt: FMT.LJ, wordLength: 1, dsp: true });
    checkTrue('iface_dsp', r(0x06) === 0x11);
    checkTrue('iface_dsp_needs_lj', await rejects(() => chip.setInterface({ dsp: true })));

    // --- tone / beep / limiter ---
    await chip.setTone(12, -10.5, 1, 2, true);
    checkTrue('tone', r(0x1F) === 0x0F && (r(0x1E) & 0x1F) === 0x0D);
    await chip.setTone(0, 0, 0, 0, false);
    checkTrue('tone_flat', r(0x1F) === 0x88 && (r(0x1E) & 1) === 0);
    await chip.setBeep(0xC, 2, 3, 6, BEEP.SINGLE);
    checkTrue('beep_regs', r(0x1C) === 0xC2 && r(0x1D) === 0x66 && (r(0x1E) >> 6) === 1);
    checkTrue('beep_active_refused', await rejects(() => chip.setBeep(1, 1, 1, 0, 1)));
    await chip.stopBeep();
    checkTrue('beep_stop', (r(0x1E) >> 6) === 0);
    await chip.setBeep(1, 1, 0, -56, 3, false);
    checkTrue('beep_vol_min', (r(0x1D) & 0x1F) === 0x07 && (r(0x1E) & 0xE0) === 0xE0);
    await chip.setBeep(1, 1, 0, 0, 0);
    checkTrue('beep_off_mode', (r(0x1E) >> 6) === 0);
    await chip.setBeep(1, 1, 0, 0, 2);
    checkTrue('beep_vol_0db', (r(0x1D) & 0x1F) === 0x03);
    await chip.stopBeep();
    checkTrue('beep_invalid', await rejects(() => chip.setBeep(16, 0, 0, 0, 1)));
    await chip.setLimiter(true, { maxDb: -6, cushionDb: -12, release: 0x20, attack: 5, limitAll: false, softRampDisable: true, zeroCrossDisable: true });
    checkTrue('limiter', r(0x27) === 0x53 && r(0x28) === 0xA0 && r(0x29) === 5);
    await chip.setLimiter(false, { maxDb: -29 });
    checkTrue('limiter_nearest', (r(0x27) >> 5) === 7 && r(0x28) === 0x7F);
    checkTrue('limiter_invalid', await rejects(() => chip.setLimiter(true, { release: 64 })));

    // --- ramp / zero-cross / dsp ---
    mock.setRegister(0x0E, [0x00]);
    mock.setRegister(0x0A, [0xA5]);
    await chip.setSoftRamp(true, true, false);
    checkTrue('soft_ramp', r(0x0E) === 0x02 && r(0x0A) === 0xA7);
    await chip.setZeroCross(true, false, true);
    checkTrue('zero_cross', r(0x0E) === 0x03 && r(0x0A) === 0xA6);
    await chip.setDeemphasis(true);
    checkTrue('deemph', (r(0x0E) & 4) !== 0);
    await chip.freeze(true);
    checkTrue('freeze_on', (r(0x0E) & 8) !== 0);
    await chip.freeze(false);
    checkTrue('freeze_off', (r(0x0E) & 8) === 0);
    await chip.setPcmSwap(SRC.SWAPPED, SRC.MIX);
    checkTrue('pcm_swap', r(0x26) === 0xD0);
    await chip.invertPcm(true, false);
    checkTrue('invert_pcm', (r(0x0D) & 0x0C) === 0x04);
    await chip.setPassthrough(true, 2, 4);
    checkTrue('passthrough', r(0x08) === 0x82 && r(0x09) === 0x88 && (r(0x0E) & 0xC0) === 0xC0);
    checkTrue('passthrough_invalid', await rejects(() => chip.setPassthrough(true, 0)));
    await chip.mutePassthrough(true, false);
    checkTrue('mute_passthrough', (r(0x0E) & 0x30) === 0x10);

    // --- battery / status ---
    await chip.setBatteryCompensation(true, 9, true);
    checkTrue('battery', r(0x2F) === 0xC9);
    mock.setRegister(0x30, [0x40]);
    checkTrue('vp_voltage', Math.abs((await chip.readVpVoltage(2.5)) - (64 * 2.5) / 63.3) < 1e-9);
    mock.setRegister(0x2E, [0x4C]);
    const st = await chip.readStatus();
    checkTrue('status', st.clockError && st.pcmAOverflow && st.pcmBOverflow && !st.dspAOverflow && !st.dspBOverflow);
    mock.setRegister(0x31, [0x28]);
    const sp = await chip.readSpeakerStatus();
    checkTrue('speaker_status', sp.speakerAShort && sp.spkrHpPin && !sp.speakerBShort);
    mock.setRegister(0x34, [0x5F]);
    await chip.setChargePumpFrequency(7);
    checkTrue('charge_pump', r(0x34) === 0x7F);
    checkTrue('charge_pump_invalid', await rejects(() => chip.setChargePumpFrequency(16)));

    // --- id / raw / reset ---
    const id = await chip.readId();
    checkTrue('read_id', id.chipId === 0x1C && id.revision === 1);
    checkTrue('read_register', (await chip.readRegister(0x01)) === 0xE1);
    checkTrue('read_register_reserved', await rejects(() => chip.readRegister(0x03)));
    await chip.writeRegister(0x20, 0x10);
    checkTrue('write_register', r(0x20) === 0x10);
    checkTrue('write_register_ro', await rejects(() => chip.writeRegister(0x2E, 0)));
    checkTrue('write_register_reserved', await rejects(() => chip.writeRegister(0x33, 0)));
    checkTrue('reset_no_pin', await rejects(async () => (await make()).chip.reset()));
    const second = await make(new Pin());
    await second.chip.start();
    await second.chip.reset();
    checkTrue('reset_reinit', second.mock.registers.get(0x02) === 0x01);

    console.log(`Passed: ${passed}  Failed: ${failed}`);
    if (failed) process.exit(1);
}

main();
