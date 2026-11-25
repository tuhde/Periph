"""Unit test for the DRV2605 — runs without hardware using the I2C mock.

Verifies the init sequence, effect/sequence encoding, mode and bit handling,
voltage conversions, calibration flow, status decoding, and OTP guards by
observing what the driver writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.motor.drv2605 import DRV2605Minimal, DRV2605Full

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


class ChipMock(I2CConnectionMock):
    """Mock whose DEV_RESET bit self-clears like the real chip."""

    def write(self, data):
        I2CConnectionMock.write(self, data)
        if len(data) >= 2 and data[0] == 0x01:
            self.registers[0x01] &= 0x7F
        if len(data) >= 2 and data[0] == 0x0C:
            self.registers[0x0C] = 0  # GO self-clears when the process ends


def make(lra=False, cls=DRV2605Full):
    mock = ChipMock({0x00: 0x60})
    return mock, cls(mock, lra=lra)


def last_write(mock, reg):
    return [w[1:] for w in mock.writes if len(w) >= 2 and w[0] == reg][-1]


# --- init ---
mock, chip = make(False, DRV2605Minimal)
check_true('init_reset_first', mock.writes[0] == bytes([0x01, 0x80]))
check_true('init_leaves_standby', bytes([0x01, 0x00]) in mock.writes)
check_true('init_erm_feedback', last_write(mock, 0x1A) == bytes([0x36]))
check_true('init_erm_library_a', last_write(mock, 0x03) == bytes([0x01]))
mock, chip = make(True, DRV2605Minimal)
check_true('init_lra_feedback', last_write(mock, 0x1A) == bytes([0xB6]))
check_true('init_lra_library', last_write(mock, 0x03) == bytes([0x06]))
check_true('init_wrong_device_id', raises(lambda: DRV2605Minimal(ChipMock({0x00: 0xE0})), RuntimeError))

# --- minimal playback ---
mock, chip = make(False, DRV2605Minimal)
chip.play_effect(47)
check_true('play_effect_slots', last_write(mock, 0x04) == bytes([47, 0]))
check_true('play_effect_go', last_write(mock, 0x0C) == bytes([1]))
check_true('play_effect_rejects_0', raises(lambda: chip.play_effect(0)))
check_true('play_effect_rejects_124', raises(lambda: chip.play_effect(124)))
chip.stop()
check_true('stop_clears_go', last_write(mock, 0x0C) == bytes([0]))
mock.set_register(0x0C, 1)
check_true('is_playing_true', chip.is_playing())
mock.set_register(0x0C, 0)
check_true('is_playing_false', not chip.is_playing())
check_true('minimal_has_no_full_api', not hasattr(chip, 'play_sequence'))

# --- sequence ---
mock, chip = make()
chip.play_sequence([1, ('wait', 100), 10])
check_true('sequence_encoding', last_write(mock, 0x04) == bytes([1, 0x80 | 10, 10, 0]))
chip.play_sequence([1, 2, 3, 4, 5, 6, 7, 8])
check_true('sequence_eight_no_terminator', last_write(mock, 0x04) == bytes(range(1, 9)))
check_true('sequence_too_long', raises(lambda: chip.play_sequence([1] * 9)))
check_true('sequence_bad_wait', raises(lambda: chip.play_sequence([('wait', 2000)])))
mock.set_register(0x0C, 0)
check_true('wait_done_true', chip.wait_done(50))
mock.set_register(0x0C, 1)
check_true('wait_done_timeout', not chip.wait_done(20))

# --- mode / bits ---
mock, chip = make()
chip.set_mode(5)
check_true('set_mode_5', last_write(mock, 0x01) == bytes([0x05]))
chip.standby()
check_true('standby_bit', last_write(mock, 0x01) == bytes([0x45]))
chip.wake()
check_true('wake_bit', last_write(mock, 0x01) == bytes([0x05]))
chip.set_hi_z(True)
check_true('hi_z_set', last_write(mock, 0x03)[0] & 0x10 == 0x10)
chip.set_library(6)
check_true('library_preserves_hi_z', last_write(mock, 0x03)[0] == 0x16)
check_true('library_range', raises(lambda: chip.set_library(7)))
mock.set_register(0x1A, 0x36)
chip.set_actuator(True)
check_true('set_actuator_lra', last_write(mock, 0x1A)[0] == 0xB6)

# --- RTP ---
mock, chip = make()
chip.rtp_level(0.5)
check_true('rtp_level_mode', bytes([0x01, 0x05]) in mock.writes)
check_true('rtp_level_value', last_write(mock, 0x02)[0] == 64)
chip.rtp_write(200)
check_true('rtp_write_raw', last_write(mock, 0x02)[0] == 200)
chip.stop()
check_true('stop_leaves_rtp', last_write(mock, 0x02)[0] == 0 and last_write(mock, 0x01)[0] == 0x00)
check_true('rtp_level_range', raises(lambda: chip.rtp_level(1.5)))
chip.set_rtp_format(True)
check_true('rtp_format_unsigned', last_write(mock, 0x1D)[0] & 0x08 == 0x08)

# --- loop constraints ---
mock, chip = make()
mock.set_register(0x1D, 0x20)  # ERM open loop
check_true('unidirectional_rejected_open_loop', raises(lambda: chip.set_bidirectional(False)))
chip.set_open_loop(False)
check_true('erm_open_loop_clear', last_write(mock, 0x1D)[0] == 0x00)
chip.set_bidirectional(False)
check_true('unidirectional_closed_loop', last_write(mock, 0x1C)[0] & 0x80 == 0)

# --- voltage conversions ---
mock, chip = make()
mock.set_register(0x1A, 0x36)
chip.set_rated_voltage(volts=3.0)
check_true('rated_erm_3v', last_write(mock, 0x16)[0] == 141)
chip.set_rated_voltage(code=77)
check_true('rated_raw_code', last_write(mock, 0x16)[0] == 77)
check_true('rated_needs_one_arg', raises(lambda: chip.set_rated_voltage()))
mock.set_register(0x1D, 0x20)
chip.set_overdrive_clamp(volts=3.0)
check_true('clamp_erm_open_3v', last_write(mock, 0x17)[0] == 137)
mock.set_register(0x1D, 0x00)
check_true('clamp_erm_closed_raises', raises(lambda: chip.set_overdrive_clamp(volts=3.0)))
mock.set_register(0x1A, 0xB6)
mock.set_register(0x1C, 0x30)  # sample time 3
chip.set_rated_voltage(volts=2.0, lra_freq_hz=200.0)
check_true('rated_lra_2v_200hz', last_write(mock, 0x16)[0] == 81)
check_true('rated_lra_needs_freq', raises(lambda: chip.set_rated_voltage(volts=2.0)))
chip.set_drive_time(2.5)
check_true('drive_time_lra', last_write(mock, 0x1B)[0] & 0x1F == 20)
mock.set_register(0x1A, 0x36)
chip.set_drive_time(5.0)
check_true('drive_time_erm', last_write(mock, 0x1B)[0] & 0x1F == 20)

# --- feedback / advanced / offsets ---
mock, chip = make()
mock.set_register(0x1A, 0x80)
chip.set_feedback(brake_factor=2, loop_gain=2, bemf_gain=1)
check_true('feedback_preserves_type', last_write(mock, 0x1A)[0] == 0x80 | (2 << 4) | (2 << 2) | 1)
chip.set_time_offsets(overdrive_ms=10, sustain_pos_ms=-5, sustain_neg_ms=0, brake_ms=-640)
check_true('time_offsets_signed', last_write(mock, 0x0D) == bytes([2, 0xFF, 0, 0x80]))
check_true('time_offsets_range', raises(lambda: chip.set_time_offsets(brake_ms=700)))

# --- calibration ---
mock, chip = make()
mock.set_register(0x0C, 0)
mock.set_register(0x18, 0x0D, 0x6D)
mock.set_register(0x1A, 0x36)
ok, comp, bemf, gain = chip.calibrate(False, 3.0, 3.0)
check_true('calibrate_enters_mode7', bytes([0x01, 0x07]) in mock.writes)
check_true('calibrate_sets_inputs', last_write(mock, 0x16)[0] == 141 and last_write(mock, 0x17)[0] == 137)
check_true('calibrate_auto_cal_time', last_write(mock, 0x1E)[0] & 0x30 == 0x30)
check_true('calibrate_go', bytes([0x0C, 0x01]) in mock.writes)
check_true('calibrate_result', ok and comp == 0x0D and bemf == 0x6D and gain == 2)
check_true('calibrate_restores_mode0', last_write(mock, 0x01)[0] == 0x00)
mock.set_register(0x00, 0x68)
check_true('calibrate_failure_flag', not chip.calibrate(False, 3.0, 3.0)[0])
mock.set_register(0x00, 0x60)
check_true('calibrate_lra_needs_freq', raises(lambda: chip.calibrate(True, 2.0, 2.5)))
chip.calibrate(True, 2.0, 2.5, lra_freq_hz=200.0)
check_true('calibrate_lra_feedback', last_write(mock, 0x1A)[0] & 0x80 == 0x80)
chip.set_calibration(0x20, 0x80, 3)
check_true('set_calibration', last_write(mock, 0x18) == bytes([0x20, 0x80]) and last_write(mock, 0x1A)[0] & 0x03 == 3)
check_true('get_calibration', chip.get_calibration() == (0x20, 0x80, 3))

# --- diagnose ---
mock, chip = make()
mock.set_register(0x0C, 0)
check_true('diagnose_ok', chip.diagnose() and bytes([0x01, 0x06]) in mock.writes)
mock.set_register(0x00, 0x68)
check_true('diagnose_fail', not chip.diagnose())

# --- audio to vibe ---
mock, chip = make()
chip.set_audio_to_vibe(peak_time_ms=30, filter_hz=150, min_input_v=0.0, max_input_v=1.8, min_drive=0.0, max_drive=1.0)
check_true('atv_registers', last_write(mock, 0x11) == bytes([(2 << 2) | 2, 0, 255, 0, 255]))
check_true('atv_mode4', last_write(mock, 0x01)[0] == 0x04)
check_true('atv_ac_couple', last_write(mock, 0x1B)[0] & 0x20 == 0x20)
check_true('atv_bad_filter', raises(lambda: chip.set_audio_to_vibe(filter_hz=300)))

# --- status / readouts ---
mock, chip = make()
mock.set_register(0x00, 0x60 | 0x08 | 0x01)
check_true('read_status', chip.read_status() == (True, False, False, True))
check_true('read_device_id', chip.read_device_id() == 3)
mock.set_register(0x21, 255)
check_true('supply_voltage', abs(chip.read_supply_voltage() - 5.6) < 1e-6)
mock.set_register(0x22, 0)
check_true('lra_freq_none', chip.read_lra_frequency() is None)
mock.set_register(0x22, 52)
check_true('lra_freq', abs(chip.read_lra_frequency() - 195.3) < 0.2)

# --- OTP ---
mock, chip = make()
n = len(mock.writes)
check_true('otp_requires_confirm', raises(lambda: chip.program_otp()) and len(mock.writes) == n)
mock.set_register(0x1E, 0x04)
check_true('otp_status', chip.otp_programmed())
check_true('otp_already_programmed', raises(lambda: chip.program_otp(True), RuntimeError))

print('===DONE: %d passed, %d failed===' % (passed, failed))
if failed:
    raise SystemExit(1)
