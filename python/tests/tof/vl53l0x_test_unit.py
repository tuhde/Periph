"""Unit test for the VL53L0X — runs without hardware using the I2C mock.

Verifies the model-ID check, the initialization sequence (2V8 I/O, stop
variable, reference-SPAD selection, tuning table, GPIO1 config, sequence
config, reference calibrations), single-shot ranging with the stop-variable
preamble, result-block decoding, continuous/timed ranging, the timing-budget
round trip, signal-rate limit, VCSEL periods, profiles, offset and crosstalk
encoding, thresholds, address change, and the interrupt API.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.tof.vl53l0x import (
    VL53L0XMinimal, VL53L0XFull, PRE_RANGE, FINAL_RANGE,
    SOURCE_NEW_SAMPLE_READY, SOURCE_OUT_OF_WINDOW, SOURCE_LEVEL_LOW)

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


class VL53L0XSim(I2CConnectionMock):
    """Page-aware VL53L0X simulator on top of the byte-slot mock.

    Registers written while 0xFF != 0 go to a separate per-page store, so the
    private-bank tuning writes don't clobber page-0 registers. Starting a
    ranging (or calibration) raises RESULT_INTERRUPT_STATUS; the interrupt
    clear drops it unless continuous mode is active. The SPAD-info handshake
    (page 7, 0x83) completes immediately.
    """

    def __init__(self):
        super().__init__()
        self.page = 0
        self.pages = {}
        self.continuous = False
        self.log = []
        self.set_register(0xC0, 0xEE, 0xAA, 0x10)
        self.set_register(0x89, 0x00)
        self.set_register(0x60, 0x00)
        self.set_register(0x84, 0x11)
        self.set_register(0xB0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        self.set_register(0xF8, 0x00, 0x10)
        # Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
        self.set_register(0x14, 11 << 3, 0x00, 0x0A, 0x00, 0x00, 0x00,
                          0x02, 0x80, 0x00, 0x40, 0x00, 0xFA)
        self.pages[(1, 0x91)] = 0x3C
        self.pages[(7, 0x92)] = 0x85

    def write(self, data):
        data = bytes(data)
        reg = data[0]
        if reg == 0xFF:
            self.page = data[1]
        self.log.append((self.page, data))
        if self.page != 0 and reg != 0xFF:
            self.writes.append(data)
            for i, value in enumerate(data[1:]):
                self.pages[(self.page, reg + i)] = value
            if self.page == 7 and reg == 0x83 and data[1] == 0x00:
                self.pages[(7, 0x83)] = 0x01
            return
        super().write(data)
        if reg == 0x00 and len(data) == 2:
            value = data[1]
            if value & 0x06:
                self.continuous = True
                self.registers[0x13] = 0x04
            elif value & 0x01:
                if self.continuous:
                    self.continuous = False
                else:
                    self.registers[0x13] = 0x04
            self.registers[0x00] = 0x00
        elif reg == 0x0B and data[1] == 0x01 and not self.continuous:
            self.registers[0x13] = 0x00

    def write_read(self, data, n):
        if self.page != 0:
            self.writes.append(bytes(data))
            return bytes(self.pages.get((self.page, data[0] + i), 0) for i in range(n))
        return super().write_read(data, n)


def page0_writes(mock, reg):
    return [w for p, w in mock.log if p == 0 and len(w) >= 2 and w[0] == reg]


def reg16(mock, reg):
    return (mock.registers.get(reg, 0) << 8) | mock.registers.get(reg + 1, 0)


# --- Identity check -------------------------------------------------------
bad = VL53L0XSim()
bad.registers[0xC0] = 0xEF
check_true('init_rejects_wrong_model_id', raises(lambda: VL53L0XMinimal(bad)))

# --- Initialization -------------------------------------------------------
mock = VL53L0XSim()
sensor = VL53L0XMinimal(mock)
check_true('init_2v8_mode', mock.registers[0x89] & 0x01 == 0x01)
check_true('init_i2c_standard_mode', mock.registers[0x88] == 0x00)
check_true('init_stop_variable', sensor._stop_variable == 0x3C)
check_true('init_signal_checks_disabled', page0_writes(mock, 0x60)[0] == bytes([0x60, 0x12]))
check_true('init_signal_rate_limit', reg16(mock, 0x44) == 0x0020)
check_true('init_spad_map_aperture_5', [mock.registers[0xB0 + i] for i in range(6)]
           == [0x00, 0xF0, 0x01, 0x00, 0x00, 0x00])
check_true('init_ref_en_start_select', mock.registers[0xB6] == 0xB4)
check_true('init_dynamic_spad_page1', mock.pages[(1, 0x4E)] == 0x2C and mock.pages[(1, 0x4F)] == 0x00)
check_true('init_tuning_page0', mock.registers[0x46] == 0x25 and mock.registers[0x70] == 0x04)
check_true('init_tuning_page1', mock.pages[(1, 0x46)] == 0x05)
check_true('init_gpio_new_sample', mock.registers[0x0A] == 0x04)
check_true('init_gpio_active_low', mock.registers[0x84] == 0x01)
check_true('init_sequence_config', mock.registers[0x01] == 0xE8)
starts = [w[1] for w in page0_writes(mock, 0x00)]
check_true('init_vhv_then_phase_calibration', starts[-4:] == [0x41, 0x00, 0x01, 0x00])
check_true('init_returns_to_page0', mock.page == 0)
seq_writes = [w[1] for w in page0_writes(mock, 0x01)]
check_true('init_sequence_order', seq_writes[-4:] == [0xE8, 0x01, 0x02, 0xE8])

# --- Single-shot ranging --------------------------------------------------
mock.writes.clear()
d = sensor.distance()
check_true('distance_mm', d == 250)
check_true('range_valid', sensor.range_valid())
pre = mock.writes[:7]
check_true('distance_stop_variable_preamble', pre == [
    bytes([0x80, 0x01]), bytes([0xFF, 0x01]), bytes([0x00, 0x00]), bytes([0x91, 0x3C]),
    bytes([0x00, 0x01]), bytes([0xFF, 0x00]), bytes([0x80, 0x00])])
check_true('distance_start', mock.writes[7] == bytes([0x00, 0x01]))
check_true('distance_clears_interrupt', mock.writes[-1] == bytes([0x0B, 0x01]))

mock.set_register(0x14, 4 << 3)
mock.set_register(0x1E, 0x1F, 0xFF)
check_true('distance_out_of_range_raw', sensor.distance() == 8191)
check_true('range_invalid', not sensor.range_valid())

# --- Full: measurement record ---------------------------------------------
mock = VL53L0XSim()
full = VL53L0XFull(mock)
check_true('full_is_minimal', isinstance(full, VL53L0XMinimal))
check_true('minimal_has_no_full_api', not hasattr(VL53L0XMinimal, 'start_continuous'))
full.distance()
m = full.read_measurement()
check_true('measurement_distance', m['distance_mm'] == 250)
check_true('measurement_status', m['range_status'] == 11 and full.range_status() == 11)
check_true('measurement_signal_rate', m['signal_rate_mcps'] == 5.0)
check_true('measurement_ambient_rate', m['ambient_rate_mcps'] == 0.5)
check_true('measurement_spads', m['effective_spad_count'] == 10.0)

# --- Continuous ranging ---------------------------------------------------
mock.writes.clear()
full.start_continuous()
check_true('continuous_back_to_back', mock.writes[-1] == bytes([0x00, 0x02]))
check_true('continuous_stop_variable', bytes([0x91, 0x3C]) in mock.writes)
check_true('data_ready', full.data_ready())
check_true('read_continuous', full.read_continuous() == 250)
full.stop_continuous()
check_true('stop_continuous_sequence', mock.writes[-6:] == [
    bytes([0x00, 0x01]), bytes([0xFF, 0x01]), bytes([0x00, 0x00]), bytes([0x91, 0x00]),
    bytes([0x00, 0x01]), bytes([0xFF, 0x00])])
full.start_continuous(100)
check_true('timed_period', [mock.registers[0x04 + i] for i in range(4)] == [0x00, 0x00, 0x06, 0x40])
check_true('timed_start', mock.writes[-1] == bytes([0x00, 0x04]))
full.stop_continuous()

# --- Timing budget --------------------------------------------------------
budget = full.timing_budget()
check_true('default_budget_about_33ms', 32000 <= budget <= 34000)
full.set_timing_budget(50000)
check_true('budget_roundtrip', abs(full.timing_budget() - 50000) < 50)
check_true('budget_rejects_below_min', raises(lambda: full.set_timing_budget(19999)))

# --- Signal rate ----------------------------------------------------------
full.set_signal_rate_limit(0.1)
check_true('signal_rate_encode', reg16(mock, 0x44) == 13)
check_true('signal_rate_decode', full.signal_rate_limit() == 13 / 128)
check_true('signal_rate_rejects_negative', raises(lambda: full.set_signal_rate_limit(-1)))

# --- VCSEL periods --------------------------------------------------------
check_true('vcsel_pre_default', full.vcsel_pulse_period(PRE_RANGE) == 14)
check_true('vcsel_final_default', full.vcsel_pulse_period(FINAL_RANGE) == 10)
full.set_vcsel_pulse_period(PRE_RANGE, 18)
check_true('vcsel_pre_18', full.vcsel_pulse_period(PRE_RANGE) == 18
           and mock.registers[0x57] == 0x50 and mock.registers[0x56] == 0x08)
full.set_vcsel_pulse_period(FINAL_RANGE, 14)
check_true('vcsel_final_14', full.vcsel_pulse_period(FINAL_RANGE) == 14
           and mock.registers[0x48] == 0x48 and mock.registers[0x32] == 0x03
           and mock.registers[0x30] == 0x07 and mock.pages[(1, 0x30)] == 0x20)
check_true('vcsel_keeps_budget', abs(full.timing_budget() - 50000) < 300)
check_true('vcsel_restores_sequence', mock.registers[0x01] == 0xE8)
check_true('vcsel_rejects_odd', raises(lambda: full.set_vcsel_pulse_period(PRE_RANGE, 13)))
check_true('vcsel_rejects_type', raises(lambda: full.set_vcsel_pulse_period('mid', 10)))

full.set_profile('high_speed')
check_true('profile_high_speed', full.vcsel_pulse_period(PRE_RANGE) == 14
           and full.vcsel_pulse_period(FINAL_RANGE) == 10
           and abs(full.timing_budget() - 20000) < 50 and reg16(mock, 0x44) == 32)
full.set_profile('long_range')
check_true('profile_long_range', full.vcsel_pulse_period(PRE_RANGE) == 18
           and full.vcsel_pulse_period(FINAL_RANGE) == 14 and reg16(mock, 0x44) == 13)
check_true('profile_rejects_unknown', raises(lambda: full.set_profile('turbo')))

# --- Offset and crosstalk -------------------------------------------------
full.set_offset(-10.25)
check_true('offset_encode', reg16(mock, 0x28) == ((-41) & 0x0FFF))
check_true('offset_decode', full.offset() == -10.25)
full.set_offset(12.5)
check_true('offset_positive', full.offset() == 12.5)
check_true('offset_rejects_range', raises(lambda: full.set_offset(512.0)))
full.set_crosstalk_compensation(0.5)
check_true('crosstalk_encode', reg16(mock, 0x20) == 4096)
full.set_crosstalk_compensation(0)
check_true('crosstalk_off', reg16(mock, 0x20) == 0)
check_true('crosstalk_rejects_range', raises(lambda: full.set_crosstalk_compensation(8.0)))

# --- Recalibrate ----------------------------------------------------------
mock.writes.clear()
full.recalibrate()
check_true('recalibrate_vhv_and_phase', bytes([0x00, 0x41]) in mock.writes
           and bytes([0x01, 0x02]) in mock.writes and mock.registers[0x01] == 0xE8)

# --- Thresholds, address, identification ----------------------------------
full.set_interrupt_thresholds(100, 801)
check_true('thresholds_encode', reg16(mock, 0x0E) == 50 and reg16(mock, 0x0C) == 400)
check_true('thresholds_decode', full.interrupt_thresholds() == (100, 800))
check_true('thresholds_reject_order', raises(lambda: full.set_interrupt_thresholds(500, 100)))
full.set_address(0x30)
check_true('set_address', mock.registers[0x8A] == 0x30)
check_true('address_rejects_range', raises(lambda: full.set_address(0x78)))
check_true('model_id', full.model_id() == 0xEE)
check_true('revision_id', full.revision_id() == 0x10)

# --- Interrupt API --------------------------------------------------------
full.enable_interrupt(SOURCE_OUT_OF_WINDOW)
check_true('enable_interrupt', mock.registers[0x0A] == 0x03)
full.disable_interrupt(SOURCE_LEVEL_LOW)
check_true('disable_inactive_source_ignored', mock.registers[0x0A] == 0x03)
full.disable_interrupt(SOURCE_OUT_OF_WINDOW)
check_true('disable_active_source', mock.registers[0x0A] == 0x00)
full.enable_interrupt(SOURCE_NEW_SAMPLE_READY)
mock.registers[0x13] = 0x03 | 0x08
check_true('poll_interrupt_value', full.poll_interrupt() == SOURCE_OUT_OF_WINDOW)
check_true('poll_interrupt_clears', mock.registers[0x13] == 0x00)
check_true('poll_interrupt_none', full.poll_interrupt() == 0)
check_true('enable_rejects_range', raises(lambda: full.enable_interrupt(5)))

print('Passed: {}, Failed: {}'.format(passed, failed))
print('===DONE===')
