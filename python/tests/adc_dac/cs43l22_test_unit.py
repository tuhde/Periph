"""Unit test for the CS43L22 — runs without hardware using the I2C mock.

Verifies the reset/ID/initialization sequence, power-up and power-down order,
volume and gain encodings, bit-field read-modify-write, the clocking table,
beep/limiter/tone packing and input validation.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.cs43l22 import CS43L22Minimal, CS43L22Full, clocking_for

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


def raises(fn, exc=ValueError):
    try:
        fn()
    except exc:
        return True
    return False


class Pin:
    def __init__(self):
        self.levels = []

    def set(self, high):
        self.levels.append(high)


def make(cls=CS43L22Full, pin=None, rev=1):
    mock = I2CConnectionMock({0x01: 0xE0 | rev, 0x32: 0x3B})
    return mock, cls(mock, pin)


# --- init ---------------------------------------------------------------
pin = Pin()
mock, chip = make(pin=pin)
check_true('reset_pulse', pin.levels == [False, True])
w = mock.writes
check_true('init_power_down_first', w[1] == bytes([0x02, 0x01]))
check_true('init_defaults', [mock.registers[r] for r in (0x04, 0x05, 0x06, 0x0D, 0x20, 0x21)]
           == [0xAF, 0xA0, 0x04, 0x60, 0, 0])
check_true('init_required_sequence', w[-5:] == [bytes([0x00, 0x99]), bytes([0x47, 0x80]), bytes([0x32, 0xBB]),
                                              bytes([0x32, 0x3B]), bytes([0x00, 0x00])] or
           [x for x in w[-6:] if len(x) == 2] == [bytes([0x00, 0x99]), bytes([0x47, 0x80]), bytes([0x32, 0xBB]),
                                                  bytes([0x32, 0x3B]), bytes([0x00, 0x00])])
check_true('init_not_powered_up', mock.registers[0x02] == 0x01)
check_true('bad_id', raises(lambda: CS43L22Minimal(I2CConnectionMock({0x01: 0x00})), OSError))

# --- start / stop -------------------------------------------------------
chip.start()
check_true('start_9e', mock.registers[0x02] == 0x9E)
mock.set_register(0x0E, 0x03)
mock.set_register(0x0A, 0x0F)
chip.stop()
check_true('stop_9f', mock.registers[0x02] == 0x9F)
check_true('stop_mutes', mock.registers[0x0F] & 0xF0 == 0xF0)
check_true('stop_clears_ramp_zc', mock.registers[0x0E] & 0x03 == 0 and mock.registers[0x0A] & 0x0F == 0)

# --- volume -------------------------------------------------------------
chip.set_volume(0)
check_true('vol_0', mock.registers[0x20] == 0 and mock.registers[0x21] == 0)
chip.set_volume(12)
check_true('vol_12', mock.registers[0x20] == 0x18)
chip.set_volume(-102)
check_true('vol_m102', mock.registers[0x20] == 0x34)
chip.set_volume(-500)
check_true('vol_clamp_low', mock.registers[0x21] == 0x34)
chip.set_volume(-0.5)
check_true('vol_m0_5', mock.registers[0x20] == 0xFF and chip.get_volume() == -0.5)
chip.set_volume_channels(-10, 3)
check_true('vol_channels', mock.registers[0x20] == 0xEC and mock.registers[0x21] == 0x06)
chip.set_headphone_volume(-20)
check_true('hp_vol_same', mock.registers[0x22] == 0xD8 and mock.registers[0x23] == 0xD8)
chip.set_headphone_volume(-96, -100)
check_true('hp_vol_mute', mock.registers[0x22] == 0x40 and mock.registers[0x23] == 0x01)
chip.set_speaker_volume(5, -1)
check_true('spk_vol_clamp', mock.registers[0x24] == 0x00 and mock.registers[0x25] == 0xFE)
chip.set_pcm_volume(-51.5, 12)
check_true('pcm_vol', mock.registers[0x1A] == 0x19 and mock.registers[0x1B] == 0x18)
chip.mute_pcm(True, False)
check_true('pcm_mute', mock.registers[0x1A] == 0x99 and mock.registers[0x1B] == 0x18)
chip.set_pcm_volume(0)
check_true('pcm_vol_keeps_mute', mock.registers[0x1A] == 0x80)
chip.set_passthrough_volume(-60, 12)
check_true('pass_vol', mock.registers[0x14] == 0x88 and mock.registers[0x15] == 0x18)

# --- mute / outputs -----------------------------------------------------
mock.set_register(0x0F, 0x00)
chip.mute(True)
check_true('mute_hp', mock.registers[0x0F] == 0xC0)
chip.mute(False)
check_true('unmute_hp', mock.registers[0x0F] == 0x00)
chip.mute_outputs(None, True)
check_true('mute_spk_only', mock.registers[0x0F] == 0x30)
chip.mute_outputs(True, False)
check_true('mute_hp_unmute_spk', mock.registers[0x0F] == 0xC0)
chip.set_outputs(CS43L22Full.OUT_ON, CS43L22Full.OUT_OFF)
check_true('outputs', mock.registers[0x04] == 0xAF)
chip.set_outputs(0, 1)
check_true('outputs_follow', mock.registers[0x04] == 0x05)
check_true('outputs_invalid', raises(lambda: chip.set_outputs(4, 0)))
chip.set_headphone_gain(6)
check_true('hp_gain', mock.registers[0x0D] == 0xC0 and chip.headphone_gain() == 1.0)
check_true('hp_gain_invalid', raises(lambda: chip.set_headphone_gain(8)))
chip.set_speaker_mode(True, False, True)
check_true('spk_mode', mock.registers[0x0F] & 0x07 == 0x03)

# --- clocking / interface -----------------------------------------------
chip.set_clocking()
check_true('clocking_default', mock.registers[0x05] == 0xA0)
chip.set_clocking(False, 3, True, True, 3, True)
check_true('clocking_all', mock.registers[0x05] == 0x7F)
check_true('clocking_invalid', raises(lambda: chip.set_clocking(speed=4)))
check_true('table_12288_48k', clocking_for(12288000, 48000) == (False, 1, False, False, 0, False))
check_true('table_12288_8k', clocking_for(12288000, 8000) == (False, 3, True, False, 0, False))
check_true('table_11289_44k', clocking_for(11289600, 44100) == (False, 1, False, False, 0, False))
check_true('table_12m_48k', clocking_for(12000000, 48000) == (False, 1, False, False, 1, False))
check_true('table_24m_44k_frac', clocking_for(24000000, 44117.647) == (False, 1, False, False, 3, True))
check_true('table_27m_16k', clocking_for(27000000, 16000) == (False, 2, True, True, 1, False))
check_true('table_16934_8018', clocking_for(16934400, 8018.18)[4] == 2)
check_true('table_unsupported', raises(lambda: clocking_for(12288000, 44100)))
check_true('table_method', chip.clocking_for(12288000, 96000)[1] == 0)
chip.set_interface()
check_true('iface_default', mock.registers[0x06] == 0x04 and mock.registers[0x07] == 0x00)
chip.set_interface(True, CS43L22Full.FMT_RJ, 2, False, True, True, True)
check_true('iface_all', mock.registers[0x06] == 0xCA and mock.registers[0x07] == 0x48)
chip.set_interface(False, 0, 1, True)
check_true('iface_dsp', mock.registers[0x06] == 0x11)
check_true('iface_dsp_needs_lj', raises(lambda: chip.set_interface(dsp=True)))

# --- tone / beep / limiter ----------------------------------------------
chip.set_tone(12, -10.5, 1, 2, True)
check_true('tone', mock.registers[0x1F] == 0x0F and mock.registers[0x1E] & 0x1F == 0x0D)
chip.set_tone(0, 0, enabled=False)
check_true('tone_flat', mock.registers[0x1F] == 0x88 and mock.registers[0x1E] & 1 == 0)
chip.set_beep(0xC, 2, 3, 6, CS43L22Full.BEEP_SINGLE)
check_true('beep_regs', mock.registers[0x1C] == 0xC2 and mock.registers[0x1D] == 0x66 and mock.registers[0x1E] >> 6 == 1)
check_true('beep_active_refused', raises(lambda: chip.set_beep(1, 1, 1, 0, 1), RuntimeError))
chip.stop_beep()
check_true('beep_stop', mock.registers[0x1E] >> 6 == 0)
chip.set_beep(1, 1, 0, -56, 3, False)
check_true('beep_vol_min', mock.registers[0x1D] & 0x1F == 0x07 and mock.registers[0x1E] & 0xE0 == 0xE0)
chip.set_beep(1, 1, 0, 0, 0)
check_true('beep_off_mode', mock.registers[0x1E] >> 6 == 0)
chip.set_beep(1, 1, 0, 0, 2)
check_true('beep_vol_0db', mock.registers[0x1D] & 0x1F == 0x03)
chip.stop_beep()
check_true('beep_invalid', raises(lambda: chip.set_beep(16, 0, 0, 0, 1)))
chip.set_limiter(True, -6, -12, 0x20, 5, False, True, True)
check_true('limiter', mock.registers[0x27] == 0x53 and mock.registers[0x28] == 0xA0 and mock.registers[0x29] == 5)
chip.set_limiter(False, -29, 0)
check_true('limiter_nearest', mock.registers[0x27] >> 5 == 7 and mock.registers[0x28] == 0x7F)
check_true('limiter_invalid', raises(lambda: chip.set_limiter(True, release=64)))

# --- ramp / zero-cross / dsp ---------------------------------------------
mock.set_register(0x0E, 0x00)
mock.set_register(0x0A, 0xA5)
chip.set_soft_ramp(True, True, False)
check_true('soft_ramp', mock.registers[0x0E] == 0x02 and mock.registers[0x0A] == 0xA7)
chip.set_zero_cross(True, False, True)
check_true('zero_cross', mock.registers[0x0E] == 0x03 and mock.registers[0x0A] == 0xA6)
chip.set_deemphasis(True)
check_true('deemph', mock.registers[0x0E] & 0x04)
chip.freeze(True)
check_true('freeze_on', mock.registers[0x0E] & 0x08)
chip.freeze(False)
check_true('freeze_off', not mock.registers[0x0E] & 0x08)
chip.set_pcm_swap(3, 1)
check_true('pcm_swap', mock.registers[0x26] == 0xD0)
chip.invert_pcm(True, False)
check_true('invert_pcm', mock.registers[0x0D] & 0x0C == 0x04)
chip.set_passthrough(True, 2, 4)
check_true('passthrough', mock.registers[0x08] == 0x82 and mock.registers[0x09] == 0x88 and mock.registers[0x0E] & 0xC0 == 0xC0)
check_true('passthrough_invalid', raises(lambda: chip.set_passthrough(True, 0)))
chip.mute_passthrough(True, False)
check_true('mute_passthrough', mock.registers[0x0E] & 0x30 == 0x10)

# --- battery / status ----------------------------------------------------
chip.set_battery_compensation(True, 9, True)
check_true('battery', mock.registers[0x2F] == 0xC9)
mock.set_register(0x30, 0x40)
check_true('vp_voltage', abs(chip.read_vp_voltage(2.5) - 64 * 2.5 / 63.3) < 1e-9)
mock.set_register(0x2E, 0x4C)
st = chip.read_status()
check_true('status', st['clock_error'] and st['pcm_a_overflow'] and st['pcm_b_overflow']
           and not st['dsp_a_overflow'] and not st['dsp_b_overflow'])
mock.set_register(0x31, 0x28)
sp = chip.read_speaker_status()
check_true('speaker_status', sp['speaker_a_short'] and sp['spkr_hp_pin'] and not sp['speaker_b_short'])
mock.set_register(0x34, 0x5F)
chip.set_charge_pump_frequency(7)
check_true('charge_pump', mock.registers[0x34] == 0x7F)
check_true('charge_pump_invalid', raises(lambda: chip.set_charge_pump_frequency(16)))

# --- id / raw / reset ----------------------------------------------------
check_true('read_id', chip.read_id() == (0x1C, 1))
check_true('read_register', chip.read_register(0x01) == 0xE1)
check_true('read_register_reserved', raises(lambda: chip.read_register(0x03)))
chip.write_register(0x20, 0x10)
check_true('write_register', mock.registers[0x20] == 0x10)
check_true('write_register_ro', raises(lambda: chip.write_register(0x2E, 0)))
check_true('write_register_reserved', raises(lambda: chip.write_register(0x33, 0)))
check_true('reset_no_pin', raises(lambda: make()[1].reset(), RuntimeError))
mock2, chip2 = make(pin=Pin())
chip2.start()
chip2.reset()
check_true('reset_reinit', mock2.registers[0x02] == 0x01)

print('Passed: %d  Failed: %d' % (passed, failed))
if failed:
    raise SystemExit(1)
