'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ES8311Minimal, ES8311Full } = require('../../packages/periph/src/chips/adc_dac/es8311');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function raises(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

function reg(m, r) { return m.registers.get(r) || 0; }

async function make(Cls, ...args) {
    const m = new I2CConnectionMock();
    const chip = new Cls(m, ...args);
    await chip._ready;
    return [m, chip];
}

async function main() {
    // --- init sequence ---------------------------------------------------
    let [m, chip] = await make(ES8311Minimal);
    checkTrue('init_reset_first', m.writes[0].equals(Buffer.from([0x00, 0x1F])));
    checkTrue('init_reset_release', m.writes[1].equals(Buffer.from([0x00, 0x00])) && m.writes[2].equals(Buffer.from([0x00, 0x80])));
    checkTrue('init_clk1', reg(m, 0x01) === 0x3F);
    checkTrue('init_clock_preset_12288_48k', [2, 3, 4, 5].map((r) => reg(m, r)).join() === '0,16,16,0');
    checkTrue('init_bclk_lrck_dividers', reg(m, 6) === 0x03 && reg(m, 7) === 0x00 && reg(m, 8) === 0xFF);
    checkTrue('init_serial_i2s_16', reg(m, 0x09) === 0x0C && reg(m, 0x0A) === 0x0C);
    checkTrue('init_vendor_regs', [0x0D, 0x0E, 0x12, 0x13, 0x1C, 0x37].map((r) => reg(m, r)).join() === '1,2,0,16,106,8');
    checkTrue('init_mic_24db', reg(m, 0x14) === 0x18);
    checkTrue('init_adc_volume_0db', reg(m, 0x17) === 0xBF);
    checkTrue('init_dac_silent', reg(m, 0x32) === 0x00);
    checkTrue('init_adc_scale', reg(m, 0x16) === 0x04);

    await chip.setVolume(0);
    checkTrue('volume_0db', reg(m, 0x32) === 0xBF);
    await chip.setVolume(-95.5);
    checkTrue('volume_min', reg(m, 0x32) === 0x00);
    await chip.setVolume(32);
    checkTrue('volume_max', reg(m, 0x32) === 0xFF);
    await chip.setVolume(100);
    checkTrue('volume_clamps_high', reg(m, 0x32) === 0xFF);
    await chip.setVolume(-10.2);
    checkTrue('volume_rounds', reg(m, 0x32) === 191 - 20);
    m.setRegister(0x31, [0x81]);
    await chip.setMute(true);
    checkTrue('mute_sets_bits_6_5_keeps_rest', reg(m, 0x31) === 0xE1);
    await chip.setMute(false);
    checkTrue('unmute_clears_bits_6_5', reg(m, 0x31) === 0x81);
    await chip.setMicGain(30);
    checkTrue('mic_gain_30db', reg(m, 0x14) === 0x1A);
    await chip.setMicGain(7.4);
    checkTrue('mic_gain_rounds_3db', reg(m, 0x14) === 0x12);
    await chip.setMicGain(99);
    checkTrue('mic_gain_clamps', (reg(m, 0x14) & 0x0F) === 10);

    // --- clock computation -----------------------------------------------
    checkTrue('bad_clock_throws', (() => { try { new ES8311Minimal(new I2CConnectionMock(), 1000, 48000); } catch (e) { return e instanceof RangeError; } return false; })());
    [m, chip] = await make(ES8311Minimal, 24576000, 48000);
    checkTrue('preset_24576_48k', reg(m, 0x02) === 0x20);
    [m, chip] = await make(ES8311Minimal, 12288000, 16000);
    const r02 = reg(m, 0x02);
    const ratio = 12288000 / (((r02 >> 5) & 7) + 1) * [1, 2, 4, 8][(r02 >> 3) & 3] / ((reg(m, 0x05) & 0x0F) + 1) / 16000;
    checkTrue('computed_16k_ratio_allowed', [256, 384, 512, 768].includes(ratio));
    checkTrue('computed_16k_osr_code', reg(m, 0x03) === reg(m, 0x04) && reg(m, 0x03) >= 16 && reg(m, 0x03) <= 63);

    // --- Full --------------------------------------------------------------
    [m, chip] = await make(ES8311Full);
    const full = chip;
    await full.setVolume(-20);
    checkTrue('get_volume', (await full.getVolume()) === -20.0);
    await full.setMicGain(21);
    checkTrue('get_mic_gain', (await full.getMicGain()) === 21.0);

    let n = m.writes.length;
    await full.reset();
    checkTrue('reset_sequence', m.writes.length === n + 3 && m.writes[n].equals(Buffer.from([0, 0x1F])) &&
        m.writes[n + 1].equals(Buffer.from([0, 0])) && m.writes[n + 2].equals(Buffer.from([0, 0x80])));

    await full.powerDown();
    checkTrue('power_down_clears_csm', reg(m, 0x00) === 0x1F);
    checkTrue('power_down_mutes', (reg(m, 0x31) & 0x60) === 0x60);
    checkTrue('power_down_pwr_bits', reg(m, 0x0D) === 0xFC && (reg(m, 0x0E) & 0x70) === 0x70);
    await full.powerUp();
    checkTrue('power_up_csm_on', reg(m, 0x00) === 0x80 && reg(m, 0x0D) === 0x01);

    await full.setLowPower({ dac: true, integrator: true });
    checkTrue('low_power_bits', reg(m, 0x0F) === 0x81);
    await full.setLowPower({ pga: true, adc: true, adcRef: true, flash: true });
    checkTrue('low_power_other_bits', reg(m, 0x0F) === (0x40 | 0x10 | 0x08 | 0x02));

    await full.configureClock(12288000, 48000, true, true);
    checkTrue('clock_from_bclk_invert', reg(m, 0x01) === 0xFF);
    await full.configureClock(12288000, 48000);
    checkTrue('clock_default_flags', reg(m, 0x01) === 0x3F);
    checkTrue('clock_invalid_throws', await raises(() => full.configureClock(1000, 48000)));

    await full.configureSerial({ format: 'left_justified', wordLength: 24 });
    checkTrue('serial_lj_24', reg(m, 0x09) === 0x01 && reg(m, 0x0A) === 0x01);
    await full.configureSerial({ format: 'dsp_b', wordLength: 32, dacChannel: 'right' });
    checkTrue('serial_dsp_b_32_right', reg(m, 0x09) === (0x80 | 0x20 | (4 << 2) | 3));
    checkTrue('serial_dsp_b_out', reg(m, 0x0A) === (0x20 | (4 << 2) | 3));
    await full.configureSerial({ format: 'dsp_a', wordLength: 16, lrckInvert: true });
    checkTrue('serial_dsp_a_lrp', reg(m, 0x0A) === (0x20 | (3 << 2) | 3));
    checkTrue('serial_slave_rst_mst', (reg(m, 0x00) & 0x44) === 0x04);
    await full.setSdpMute(true, false);
    checkTrue('sdp_mute_in_only', (reg(m, 0x09) & 0x40) && !(reg(m, 0x0A) & 0x40));
    await full.configureSerial({ format: 'i2s', wordLength: 16 });
    checkTrue('serial_keeps_mute_bit', (reg(m, 0x09) & 0x40) !== 0);
    await full.configureSerial({ master: true, bclkDiv: 4, lrckDiv: 256 });
    checkTrue('serial_master_msc', (reg(m, 0x00) & 0x44) === 0x40);
    checkTrue('serial_master_dividers', reg(m, 0x06) === 0x03 && (reg(m, 0x07) & 0x0F) === 0x00 && reg(m, 0x08) === 0xFF);
    await full.configureSerial({ master: true, bclkDiv: 32, lrckDiv: 64, continuousBclk: false, bclkInvert: true });
    checkTrue('serial_master_table_div', reg(m, 0x06) === (0x40 | 0x20 | 24));
    checkTrue('serial_bad_format_throws', await raises(() => full.configureSerial({ format: 'x' })));
    checkTrue('serial_bad_word_throws', await raises(() => full.configureSerial({ wordLength: 8 })));

    await full.setTristate(true, false);
    checkTrue('tristate_bclk', (reg(m, 0x07) & 0x30) === 0x20);
    await full.setTristate(false, true);
    checkTrue('tristate_adcdat', (reg(m, 0x07) & 0x30) === 0x10);

    await full.setInput('digital');
    checkTrue('input_digital', (reg(m, 0x14) & 0x70) === 0x40 && (reg(m, 0x0E) & 0x40) !== 0);
    await full.setInput('analog');
    checkTrue('input_analog', (reg(m, 0x14) & 0x70) === 0x10 && (reg(m, 0x0E) & 0x40) === 0);
    await full.setInput('none');
    checkTrue('input_none', (reg(m, 0x14) & 0x70) === 0x00 && (reg(m, 0x0E) & 0x40) !== 0);
    checkTrue('input_bad_throws', await raises(() => full.setInput('x')));
    await full.configureDmic(true);
    checkTrue('dmic_falling', (reg(m, 0x15) & 1) === 1);
    await full.configureDmic();
    checkTrue('dmic_rising', (reg(m, 0x15) & 1) === 0);

    await full.setAdcVolume(-95.5);
    checkTrue('adc_volume_min', reg(m, 0x17) === 0);
    await full.configureAdc({ scaleDb: 42, osr: 128, doubleSpeed: false, hpf: false, hpfCoeffs: [0x05, 0x0A] });
    checkTrue('adc_scale_42', (reg(m, 0x16) & 0x07) === 7);
    checkTrue('adc_osr_128', reg(m, 0x03) === 32);
    checkTrue('adc_hpf_off_coeff', (reg(m, 0x1C) & 0x3F) === 0x0A && (reg(m, 0x1B) & 0x1F) === 0x05);
    await full.configureAdc({ osr: 64, doubleSpeed: true, hpf: true });
    checkTrue('adc_double_speed', reg(m, 0x03) === (0x40 | 32) && (reg(m, 0x1C) & 0x20) !== 0);
    checkTrue('adc_bad_osr_throws', await raises(() => full.configureAdc({ osr: 8 })));
    checkTrue('adc_bad_double_osr_throws', await raises(() => full.configureAdc({ osr: 20, doubleSpeed: true })));
    await full.setAdcRamp(7);
    checkTrue('adc_ramp', (reg(m, 0x15) >> 4) === 7);
    await full.setAdcRamp(99);
    checkTrue('adc_ramp_clamps', (reg(m, 0x15) >> 4) === 15);

    await full.configureAlc(true, { maxLevelDb: -12.0, minLevelDb: -18.1, window: 3, automute: true, automuteWindow: 2, noiseGateDb: -60 });
    checkTrue('alc1', reg(m, 0x18) === 0xC3);
    checkTrue('alc2_levels', reg(m, 0x19) === ((7 << 4) | 3));
    checkTrue('alc_automute', reg(m, 0x1A) === ((2 << 4) | 6));
    await full.configureAlc(false);
    checkTrue('alc_off_defaults', reg(m, 0x18) === 0 && reg(m, 0x19) === 0xF0 && reg(m, 0x1A) === 0);

    await full.setAdcEq([1, 2, 3, 4, 0x3FFFFFFF]);
    checkTrue('adc_eq_enable', (reg(m, 0x1C) & 0x40) === 0);
    checkTrue('adc_eq_b0', [0x1D, 0x1E, 0x1F, 0x20].map((a) => reg(m, a)).join() === '0,0,0,1');
    checkTrue('adc_eq_b2_30bit', [0x2D, 0x2E, 0x2F, 0x30].map((a) => reg(m, a)).join() === '63,255,255,255');
    checkTrue('adc_eq_burst', m.writes.some((w) => w.length === 21 && w[0] === 0x1D));
    await full.setAdcEq(null);
    checkTrue('adc_eq_bypass', (reg(m, 0x1C) & 0x40) === 0x40);
    checkTrue('adc_eq_count_throws', await raises(() => full.setAdcEq([1, 2])));
    checkTrue('adc_eq_range_throws', await raises(() => full.setAdcEq([2 ** 30, 0, 0, 0, 0])));
    await full.setDacEq([0x100, 0x200, 0x300]);
    checkTrue('dac_eq_enable', (reg(m, 0x37) & 0x08) === 0);
    checkTrue('dac_eq_coeffs', reg(m, 0x3B) === 0x00 && reg(m, 0x3A) === 0x01 && reg(m, 0x3F) === 0x00 && reg(m, 0x3E) === 0x02 && reg(m, 0x42) === 0x03);
    await full.setDacEq();
    checkTrue('dac_eq_bypass', (reg(m, 0x37) & 0x08) === 0x08);
    checkTrue('dac_eq_count_throws', await raises(() => full.setDacEq([1, 2, 3, 4])));

    await full.setDacRamp(8);
    checkTrue('dac_ramp', (reg(m, 0x37) >> 4) === 8 && (reg(m, 0x37) & 0x08) !== 0);
    await full.configureDrc(true, { maxLevelDb: -12.0, minLevelDb: -24.1, window: 5 });
    checkTrue('drc_regs', reg(m, 0x34) === 0x85 && reg(m, 0x35) === ((7 << 4) | 1));
    await full.setDacMuteOptions(true, false, true);
    checkTrue('mute_options', (reg(m, 0x31) & 0xE0) === 0xC0);
    await full.setDacMuteOptions();
    checkTrue('mute_options_defaults', (reg(m, 0x31) & 0xE0) === 0x60);

    await full.setLoopback(true);
    checkTrue('loopback_on', (reg(m, 0x44) & 0x80) !== 0);
    await full.setAdcdatMode('dac_adc');
    checkTrue('adcdat_dac_adc_keeps_loopback', reg(m, 0x44) === 0xC0);
    await full.setAdcdatMode('dac_dac');
    checkTrue('adcdat_dac_dac', ((reg(m, 0x44) >> 4) & 7) === 6);
    await full.setLoopback(false);
    checkTrue('loopback_off_keeps_mode', reg(m, 0x44) === 0x60);
    checkTrue('adcdat_bad_throws', await raises(() => full.setAdcdatMode('x')));

    await full.writeRegister(0x40, 0x34);
    checkTrue('raw_register', reg(m, 0x40) === 0x34 && (await full.readRegister(0x40)) === 0x34);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main();
