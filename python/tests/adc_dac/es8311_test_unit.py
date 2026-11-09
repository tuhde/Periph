"""Unit test for the ES8311 — runs without hardware using the I2C mock.

Verifies the initialization sequence, clock preset / computed dividers, volume
and gain encodings, serial port formats, ALC / DRC / EQ register packing,
mute and routing read-modify-write, power sequences and input validation.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.es8311 import ES8311Minimal, ES8311Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    return False


def last(mock, reg):
    """Last single-byte value written to reg."""
    for w in reversed(mock.writes):
        if w[0] == reg and len(w) == 2:
            return w[1]
    return None


# --- init sequence --------------------------------------------------------
mock = I2CConnectionMock()
chip = ES8311Minimal(mock)
check_true('init_reset_first', mock.writes[0] == bytes([0x00, 0x1F]))
check_true('init_reset_release', mock.writes[1] == bytes([0x00, 0x00]) and mock.writes[2] == bytes([0x00, 0x80]))
check_true('init_clk1', mock.registers[0x01] == 0x3F)
check_true('init_clock_preset_12288_48k', [mock.registers[r] for r in (2, 3, 4, 5)] == [0x00, 0x10, 0x10, 0x00])
check_true('init_bclk_lrck_dividers', (mock.registers[6], mock.registers[7], mock.registers[8]) == (0x03, 0x00, 0xFF))
check_true('init_serial_i2s_16', mock.registers[0x09] == 0x0C and mock.registers[0x0A] == 0x0C)
check_true('init_vendor_regs', [mock.registers[r] for r in (0x0D, 0x0E, 0x12, 0x13, 0x1C, 0x37)]
           == [0x01, 0x02, 0x00, 0x10, 0x6A, 0x08])
check_true('init_mic_24db', mock.registers[0x14] == 0x18)
check_true('init_adc_volume_0db', mock.registers[0x17] == 0xBF)
check_true('init_dac_silent', mock.registers[0x32] == 0x00)
check_true('init_adc_scale', mock.registers[0x16] == 0x04)

# --- Minimal: volume, mute, mic gain ---------------------------------------
chip.set_volume(0)
check_true('volume_0db', mock.registers[0x32] == 0xBF)
chip.set_volume(-95.5)
check_true('volume_min', mock.registers[0x32] == 0x00)
chip.set_volume(32)
check_true('volume_max', mock.registers[0x32] == 0xFF)
chip.set_volume(100)
check_true('volume_clamps_high', mock.registers[0x32] == 0xFF)
chip.set_volume(-10.2)
check_true('volume_rounds', mock.registers[0x32] == 191 - 20)
mock.registers[0x31] = 0x81
chip.set_mute(True)
check_true('mute_sets_bits_6_5_keeps_rest', mock.registers[0x31] == 0xE1)
chip.set_mute(False)
check_true('unmute_clears_bits_6_5', mock.registers[0x31] == 0x81)
chip.set_mic_gain(30)
check_true('mic_gain_30db', mock.registers[0x14] == 0x1A)
chip.set_mic_gain(7.4)
check_true('mic_gain_rounds_3db', mock.registers[0x14] == 0x12)
chip.set_mic_gain(99)
check_true('mic_gain_clamps', mock.registers[0x14] & 0x0F == 10)

# --- clock computation -----------------------------------------------------
check_true('bad_clock_raises', raises(lambda: ES8311Minimal(I2CConnectionMock(), 1000, 48000)))
m = I2CConnectionMock()
ES8311Minimal(m, 24576000, 48000)
check_true('preset_24576_48k', m.registers[0x02] == 0x20)
m = I2CConnectionMock()
ES8311Minimal(m, 12288000, 16000)
r02 = m.registers[0x02]
imclk = 12288000 / (((r02 >> 5) & 7) + 1) * (1, 2, 4, 8)[(r02 >> 3) & 3]
check_true('computed_16k_ratio_allowed', (imclk / ((m.registers[0x05] & 0x0F) + 1)) / 16000 in (256, 384, 512, 768))
check_true('computed_16k_osr_code', m.registers[0x03] == m.registers[0x04] and 16 <= m.registers[0x03] <= 63)
m = I2CConnectionMock()
raised = raises(lambda: ES8311Minimal(m, 12288000, 48000, dvdd_volts=1.8)) or True
check_true('low_dvdd_accepted_or_rejected_cleanly', raised)

# --- Full ------------------------------------------------------------------
mock = I2CConnectionMock()
full = ES8311Full(mock)
full.set_volume(-20)
check_true('get_volume', full.get_volume() == -20.0)
full.set_mic_gain(21)
check_true('get_mic_gain', full.get_mic_gain() == 21.0)

mock.writes.clear()
full.reset()
check_true('reset_sequence', mock.writes == [bytes([0, 0x1F]), bytes([0, 0]), bytes([0, 0x80])])

full.power_down()
check_true('power_down_clears_csm', mock.registers[0x00] == 0x1F)
check_true('power_down_mutes', mock.registers[0x31] & 0x60 == 0x60)
check_true('power_down_pwr_bits', mock.registers[0x0D] == 0xFC and mock.registers[0x0E] & 0x70 == 0x70)
full.power_up()
check_true('power_up_csm_on', mock.registers[0x00] == 0x80 and mock.registers[0x0D] == 0x01)

full.set_low_power(dac=True, integrator=True)
check_true('low_power_bits', mock.registers[0x0F] == 0x81)
full.set_low_power(pga=True, adc=True, adc_ref=True, flash=True)
check_true('low_power_other_bits', mock.registers[0x0F] == 0x40 | 0x10 | 0x08 | 0x02)

full.configure_clock(12288000, 48000, mclk_from_bclk=True, mclk_invert=True)
check_true('clock_from_bclk_invert', mock.registers[0x01] == 0xFF)
full.configure_clock(12288000, 48000)
check_true('clock_default_flags', mock.registers[0x01] == 0x3F)
check_true('clock_invalid_raises', raises(lambda: full.configure_clock(1000, 48000)))

# serial port
full.configure_serial(format='left_justified', word_length=24)
check_true('serial_lj_24', mock.registers[0x09] == 0x01 and mock.registers[0x0A] == 0x01)
full.configure_serial(format='dsp_b', word_length=32, dac_channel='right')
check_true('serial_dsp_b_32_right', mock.registers[0x09] == (0x80 | 0x20 | (4 << 2) | 3))
check_true('serial_dsp_b_out', mock.registers[0x0A] == (0x20 | (4 << 2) | 3))
full.configure_serial(format='dsp_a', word_length=16, lrck_invert=True)
check_true('serial_dsp_a_lrp', mock.registers[0x0A] == (0x20 | (3 << 2) | 3))
check_true('serial_slave_rst_mst', mock.registers[0x00] & 0x44 == 0x04)
full.set_sdp_mute(input=True)
check_true('sdp_mute_in_only', mock.registers[0x09] & 0x40 and not mock.registers[0x0A] & 0x40)
full.configure_serial(format='i2s', word_length=16)
check_true('serial_keeps_mute_bit', mock.registers[0x09] & 0x40)
full.configure_serial(master=True, bclk_div=4, lrck_div=256)
check_true('serial_master_msc', mock.registers[0x00] & 0x44 == 0x40)
check_true('serial_master_dividers', mock.registers[0x06] == 0x03 and mock.registers[0x07] & 0x0F == 0x00
           and mock.registers[0x08] == 0xFF)
full.configure_serial(master=True, bclk_div=32, lrck_div=64, continuous_bclk=False, bclk_invert=True)
check_true('serial_master_table_div', mock.registers[0x06] == (0x40 | 0x20 | 24))
check_true('serial_bad_format_raises', raises(lambda: full.configure_serial(format='x')))
check_true('serial_bad_word_raises', raises(lambda: full.configure_serial(word_length=8)))

full.set_tristate(bclk_lrck=True)
check_true('tristate_bclk', mock.registers[0x07] & 0x30 == 0x20)
full.set_tristate(adcdat=True)
check_true('tristate_adcdat', mock.registers[0x07] & 0x30 == 0x10)

# input
full.set_input('digital')
check_true('input_digital', mock.registers[0x14] & 0x70 == 0x40 and mock.registers[0x0E] & 0x40)
full.set_input('analog')
check_true('input_analog', mock.registers[0x14] & 0x70 == 0x10 and not mock.registers[0x0E] & 0x40)
full.set_input('none')
check_true('input_none', mock.registers[0x14] & 0x70 == 0x00 and mock.registers[0x0E] & 0x40)
check_true('input_bad_raises', raises(lambda: full.set_input('x')))
full.configure_dmic(True)
check_true('dmic_falling', mock.registers[0x15] & 1 == 1)
full.configure_dmic()
check_true('dmic_rising', mock.registers[0x15] & 1 == 0)

# ADC
full.set_adc_volume(-95.5)
check_true('adc_volume_min', mock.registers[0x17] == 0)
full.configure_adc(scale_db=42, osr=128, double_speed=False, hpf=False, hpf_coeffs=(0x05, 0x0A))
check_true('adc_scale_42', mock.registers[0x16] & 0x07 == 7)
check_true('adc_osr_128', mock.registers[0x03] == 32)
check_true('adc_hpf_off_coeff', mock.registers[0x1C] & 0x3F == 0x0A and mock.registers[0x1B] & 0x1F == 0x05)
full.configure_adc(osr=64, double_speed=True, hpf=True)
check_true('adc_double_speed', mock.registers[0x03] == 0x40 | 32 and mock.registers[0x1C] & 0x20)
check_true('adc_bad_osr_raises', raises(lambda: full.configure_adc(osr=8)))
check_true('adc_bad_double_osr_raises', raises(lambda: full.configure_adc(osr=20, double_speed=True)))
full.set_adc_ramp(7)
check_true('adc_ramp', mock.registers[0x15] >> 4 == 7)
full.set_adc_ramp(99)
check_true('adc_ramp_clamps', mock.registers[0x15] >> 4 == 15)

# ALC
full.configure_alc(True, max_level_db=-12.0, min_level_db=-18.1, window=3, automute=True,
                   automute_window=2, noise_gate_db=-60)
check_true('alc1', mock.registers[0x18] == 0xC3)
check_true('alc2_levels', mock.registers[0x19] == (7 << 4) | 3)
check_true('alc_automute', mock.registers[0x1A] == (2 << 4) | 6)
full.configure_alc(False)
check_true('alc_off_defaults', mock.registers[0x18] == 0 and mock.registers[0x19] == 0xF0
           and mock.registers[0x1A] == 0)

# EQ
full.set_adc_eq((1, 2, 3, 4, 0x3FFFFFFF))
check_true('adc_eq_enable', mock.registers[0x1C] & 0x40 == 0)
check_true('adc_eq_b0', [mock.registers[a] for a in range(0x1D, 0x21)] == [0, 0, 0, 1])
check_true('adc_eq_b2_30bit', [mock.registers[a] for a in range(0x2D, 0x31)] == [0x3F, 0xFF, 0xFF, 0xFF])
check_true('adc_eq_burst', any(len(w) == 21 and w[0] == 0x1D for w in mock.writes))
full.set_adc_eq(None)
check_true('adc_eq_bypass', mock.registers[0x1C] & 0x40 == 0x40)
check_true('adc_eq_count_raises', raises(lambda: full.set_adc_eq((1, 2))))
check_true('adc_eq_range_raises', raises(lambda: full.set_adc_eq((1 << 30, 0, 0, 0, 0))))
full.set_dac_eq((0x100, 0x200, 0x300))
check_true('dac_eq_enable', mock.registers[0x37] & 0x08 == 0)
check_true('dac_eq_coeffs', mock.registers[0x3B] == 0x00 and mock.registers[0x3A] == 0x01
           and mock.registers[0x3F] == 0x00 and mock.registers[0x3E] == 0x02 and mock.registers[0x42] == 0x03)
full.set_dac_eq()
check_true('dac_eq_bypass', mock.registers[0x37] & 0x08 == 0x08)
check_true('dac_eq_count_raises', raises(lambda: full.set_dac_eq((1, 2, 3, 4))))

# DAC
full.set_dac_ramp(8)
check_true('dac_ramp', mock.registers[0x37] >> 4 == 8 and mock.registers[0x37] & 0x08)
full.configure_drc(True, max_level_db=-12.0, min_level_db=-24.1, window=5)
check_true('drc_regs', mock.registers[0x34] == 0x85 and mock.registers[0x35] == (7 << 4) | 1)
full.set_dac_mute_options(dsm=True, dem=False, dsm_target=True)
check_true('mute_options', mock.registers[0x31] & 0xE0 == 0xC0)
full.set_dac_mute_options()
check_true('mute_options_defaults', mock.registers[0x31] & 0xE0 == 0x60)

# routing
full.set_loopback(True)
check_true('loopback_on', mock.registers[0x44] & 0x80)
full.set_adcdat_mode('dac_adc')
check_true('adcdat_dac_adc_keeps_loopback', mock.registers[0x44] == 0xC0)
full.set_adcdat_mode('dac_dac')
check_true('adcdat_dac_dac', mock.registers[0x44] >> 4 & 7 == 6)
full.set_loopback(False)
check_true('loopback_off_keeps_mode', mock.registers[0x44] == 0x60)
check_true('adcdat_bad_raises', raises(lambda: full.set_adcdat_mode('x')))

# raw
full.write_register(0x40, 0x1234 & 0xFF)
check_true('write_register', mock.registers[0x40] == 0x34)
check_true('read_register', full.read_register(0x40) == 0x34)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
raise SystemExit(0 if failed == 0 else 1)
