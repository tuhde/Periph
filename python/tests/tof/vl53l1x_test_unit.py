"""Unit test for the VL53L1X — runs without hardware using the I2C mock.

Verifies the boot poll and sensor-ID check, the initialization sequence
(ULD default configuration block, 2V8 / active-low overrides, settling
ranging, VHV setup), single-shot ranging, result-block decoding and status
mapping, timed continuous ranging with the inter-measurement period, timing
budget and distance-mode tables, signal/sigma thresholds, ROI, offset and
crosstalk encoding, both calibration helpers, the temperature update,
thresholds, address change, identification, and the interrupt API.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.tof.vl53l1x import (
    VL53L1XMinimal, VL53L1XFull, DISTANCE_MODE_SHORT, DISTANCE_MODE_LONG,
    SOURCE_NEW_SAMPLE_READY, SOURCE_OUT_OF_WINDOW, SOURCE_LEVEL_LOW, SOURCE_IN_WINDOW)

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


class VL53L1XSim(I2CConnectionMock):
    """VL53L1X simulator with explicit 16-bit register indices.

    The shared mock guesses the index width from the bytes, which misreads
    indices 0x0000-0x007F; this subclass always takes two index bytes.
    GPIO__TIO_HV_STATUS (0x0031) is computed: bit 0 is 0 (active-low line
    asserted) while a result is pending. Starting a single-shot or timed
    ranging makes a result pending; the interrupt clear drops it unless
    timed ranging is running.
    """

    def __init__(self):
        super().__init__()
        self.pending = False
        self.ranging = False
        self.log = []
        self.set16(0x00E5, 0x01)
        self.set16(0x010F, 0xEA, 0xCC, 0x10)
        self.set16(0x013E, 0x91)
        self.set16(0x00DE, 0x00, 0x50)
        # Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient,
        # 250 mm, 5.0 MCPS signal.
        block = [0] * 17
        block[0] = 9
        block[3], block[4] = 0x0A, 0x00
        block[7], block[8] = 0x00, 0x40
        block[13], block[14] = 0x00, 0xFA
        block[15], block[16] = 0x02, 0x80
        self.set16(0x0089, *block)

    def set16(self, reg, *values):
        for i, value in enumerate(values):
            self.registers[reg + i] = value

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        self.log.append(data)
        reg = (data[0] << 8) | data[1]
        for i, value in enumerate(data[2:]):
            self.registers[reg + i] = value
        if reg == 0x0087 and len(data) == 3:
            if data[2] in (0x10, 0x40):
                self.pending = True
                self.ranging = data[2] == 0x40
            else:
                self.ranging = False
        elif reg == 0x0086 and data[2] == 0x01:
            self.pending = self.ranging

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        reg = (data[0] << 8) | data[1]
        out = []
        for i in range(n):
            if reg + i == 0x0031:
                out.append(0x00 if self.pending else 0x01)
            else:
                out.append(self.registers.get(reg + i, 0))
        return bytes(out)

    def reg16(self, reg):
        return (self.registers.get(reg, 0) << 8) | self.registers.get(reg + 1, 0)

    def writes_to(self, reg):
        return [w[2:] for w in self.log if len(w) >= 3 and ((w[0] << 8) | w[1]) == reg]


# --- Identity check -------------------------------------------------------
bad = VL53L1XSim()
bad.registers[0x0110] = 0xCD
check_true('init_rejects_wrong_sensor_id', raises(lambda: VL53L1XMinimal(bad)))

unbooted = VL53L1XSim()
unbooted.registers[0x00E5] = 0x00
check_true('init_boot_timeout', raises(lambda: VL53L1XMinimal(unbooted), OSError))

# --- Initialization -------------------------------------------------------
mock = VL53L1XSim()
sensor = VL53L1XMinimal(mock)
check_true('init_16bit_index', mock.log[0][:2] == bytes([0x00, 0x2D]))
config_writes = [w for w in mock.log if 0x2D <= ((w[0] << 8) | w[1]) <= 0x87 and len(w) == 3]
check_true('init_config_block_byte_by_byte', [(w[0] << 8) | w[1] for w in config_writes[:91]]
           == list(range(0x2D, 0x88)))
check_true('init_config_values', mock.writes_to(0x0046)[0] == bytes([0x20])
           and mock.writes_to(0x005E)[0] == bytes([0x01]) and mock.writes_to(0x0081)[0] == bytes([0x9B]))
check_true('init_2v8_mode', mock.registers[0x002E] == 0x01 and mock.registers[0x002F] == 0x01)
check_true('init_gpio_active_low', mock.registers[0x0030] == 0x11)
check_true('init_settling_ranging', mock.writes_to(0x0087)[-2:] == [bytes([0x40]), bytes([0x00])])
check_true('init_vhv_bounds', mock.registers[0x0008] == 0x09 and mock.registers[0x000B] == 0x00)
check_true('init_leaves_idle', not mock.ranging)

# --- Single-shot ranging --------------------------------------------------
mock.log.clear()
d = sensor.distance()
check_true('distance_mm', d == 250)
check_true('range_valid', sensor.range_valid())
check_true('distance_sequence', mock.log[0] == bytes([0x00, 0x86, 0x01])
           and mock.log[1] == bytes([0x00, 0x87, 0x10]))
check_true('distance_clears_interrupt', mock.log[-1] == bytes([0x00, 0x86, 0x01]))
check_true('distance_burst_read', bytes([0x00, 0x89]) in mock.writes)

mock.registers[0x0089] = 4  # raw 4 -> signal fail (2)
check_true('distance_invalid_raw', sensor.distance() == 250 and not sensor.range_valid())
mock.registers[0x0089] = 0x1F  # raw 31 -> 255
sensor.distance()
check_true('status_out_of_table', sensor._range_status == 255)

# --- Full: measurement record ---------------------------------------------
mock = VL53L1XSim()
full = VL53L1XFull(mock)
check_true('full_is_minimal', isinstance(full, VL53L1XMinimal))
check_true('minimal_has_no_full_api', not hasattr(VL53L1XMinimal, 'start_continuous'))
full.distance()
m = full.read_measurement()
check_true('measurement_distance', m['distance_mm'] == 250)
check_true('measurement_status', m['range_status'] == 0 and full.range_status() == 0)
check_true('measurement_signal_rate', m['signal_rate_mcps'] == 5.0)
check_true('measurement_ambient_rate', m['ambient_rate_mcps'] == 0.5)
check_true('measurement_spads', m['effective_spad_count'] == 10.0)

# --- Timing budget and distance mode --------------------------------------
check_true('default_budget_100ms', full.timing_budget() == 100000)
check_true('default_mode_long', full.distance_mode() == DISTANCE_MODE_LONG)
full.set_timing_budget(33000)
check_true('budget_long_33', mock.reg16(0x005E) == 0x0060 and mock.reg16(0x0061) == 0x006E)
check_true('budget_roundtrip', full.timing_budget() == 33000)
check_true('budget_rejects_15_long', raises(lambda: full.set_timing_budget(15000)))
check_true('budget_rejects_other', raises(lambda: full.set_timing_budget(40000)))
full.set_distance_mode(DISTANCE_MODE_SHORT)
check_true('mode_short_regs', mock.registers[0x004B] == 0x14 and mock.registers[0x0060] == 0x07
           and mock.registers[0x0063] == 0x05 and mock.registers[0x0069] == 0x38
           and mock.reg16(0x0078) == 0x0705 and mock.reg16(0x007A) == 0x0606)
check_true('mode_short_keeps_budget', mock.reg16(0x005E) == 0x00D6 and full.timing_budget() == 33000)
check_true('mode_short_read', full.distance_mode() == DISTANCE_MODE_SHORT)
full.set_timing_budget(15000)
check_true('budget_short_15', mock.reg16(0x005E) == 0x001D and mock.reg16(0x0061) == 0x0027)
check_true('mode_long_rejects_15ms', raises(lambda: full.set_distance_mode(DISTANCE_MODE_LONG)))
full.set_timing_budget(100000)
full.set_distance_mode(DISTANCE_MODE_LONG)
check_true('mode_long_regs', mock.registers[0x004B] == 0x0A and mock.reg16(0x0078) == 0x0F0D
           and mock.reg16(0x005E) == 0x01CC and mock.reg16(0x0061) == 0x01EA)
check_true('mode_rejects_unknown', raises(lambda: full.set_distance_mode('medium')))

# --- Inter-measurement and continuous ranging -----------------------------
full.set_inter_measurement(200)
check_true('inter_measurement_encode', (mock.reg16(0x006C) << 16 | mock.reg16(0x006E))
           == (0x50 * 200 * 1075) // 1000)
check_true('inter_measurement_roundtrip', full.inter_measurement() == 200)
check_true('inter_measurement_rejects_zero', raises(lambda: full.set_inter_measurement(0)))
mock.log.clear()
full.start_continuous()
check_true('continuous_period_is_budget', full.inter_measurement() == 100)
check_true('continuous_start', mock.log[-1] == bytes([0x00, 0x87, 0x40]))
check_true('data_ready', full.data_ready())
check_true('read_continuous', full.read_continuous() == 250)
check_true('continuous_stays_ready', full.data_ready())
full.stop_continuous()
check_true('stop_continuous', mock.log[-1] == bytes([0x00, 0x87, 0x00]))
full.start_continuous(50)
check_true('continuous_period_clamped', full.inter_measurement() == 100)
full.stop_continuous()
full.start_continuous(500)
check_true('continuous_period_kept', full.inter_measurement() == 500)
full.stop_continuous()
check_true('continuous_rejects_negative', raises(lambda: full.start_continuous(-1)))

# --- Signal / sigma -------------------------------------------------------
check_true('signal_rate_default', full.signal_rate_limit() == 1.0)
full.set_signal_rate_limit(0.25)
check_true('signal_rate_encode', mock.reg16(0x0066) == 32)
check_true('signal_rate_rejects_negative', raises(lambda: full.set_signal_rate_limit(-1)))
check_true('sigma_default', full.sigma_threshold() == 90)
full.set_sigma_threshold(45)
check_true('sigma_encode', mock.reg16(0x0064) == 180 and full.sigma_threshold() == 45)
check_true('sigma_rejects_range', raises(lambda: full.set_sigma_threshold(16384)))

# --- ROI ------------------------------------------------------------------
check_true('roi_default', full.roi() == (16, 16) and full.roi_center() == 199)
full.set_roi_center(167)
full.set_roi(8, 8)
check_true('roi_small_keeps_center', mock.registers[0x0080] == 0x77 and full.roi_center() == 167)
full.set_roi(8, 16)
check_true('roi_large_recenters', full.roi() == (8, 16) and full.roi_center() == 199)
check_true('roi_rejects_small', raises(lambda: full.set_roi(3, 8)))
check_true('roi_center_rejects_range', raises(lambda: full.set_roi_center(256)))
check_true('optical_center', full.optical_center() == 0x91)

# --- Offset and crosstalk -------------------------------------------------
full.set_offset(-10.25)
check_true('offset_encode', mock.reg16(0x001E) == ((-41) & 0x1FFF)
           and mock.reg16(0x0020) == 0 and mock.reg16(0x0022) == 0)
check_true('offset_decode', full.offset() == -10.25)
full.set_offset(700.5)
check_true('offset_positive', full.offset() == 700.5)
check_true('offset_rejects_range', raises(lambda: full.set_offset(1024.0)))
full.set_crosstalk_compensation(0.01)
check_true('crosstalk_encode', mock.reg16(0x0016) == 5120
           and mock.reg16(0x0018) == 0 and mock.reg16(0x001A) == 0)
check_true('crosstalk_decode', full.crosstalk_compensation() == 0.01)
check_true('crosstalk_rejects_range', raises(lambda: full.set_crosstalk_compensation(0.128)))

# --- Calibration ----------------------------------------------------------
off = full.calibrate_offset(260)
check_true('calibrate_offset_value', off == 10.0 and full.offset() == 10.0)
check_true('calibrate_offset_stops', not mock.ranging)
xt = full.calibrate_crosstalk(500)
# 5.0 MCPS * (1 - 250/500) / 10 SPADs = 0.25 -> clamped to 0.127
check_true('calibrate_crosstalk_clamped', xt == 0.127)
mock.set16(0x0098, 0x00, 0x20)  # 0.25 MCPS
xt = full.calibrate_crosstalk(500)
check_true('calibrate_crosstalk_value', abs(xt - 0.0125) < 1e-9
           and mock.reg16(0x0016) == int(0.0125 * 512000 + 0.5))
check_true('calibrate_crosstalk_rejects_zero', raises(lambda: full.calibrate_crosstalk(0)))

# --- Temperature update ---------------------------------------------------
mock.log.clear()
full.recalibrate()
check_true('recalibrate_sequence', mock.writes_to(0x0008) == [bytes([0x81]), bytes([0x09])]
           and mock.writes_to(0x000B) == [bytes([0x92]), bytes([0x00])]
           and mock.writes_to(0x0087) == [bytes([0x40]), bytes([0x00])])

# --- Thresholds, address, identification ----------------------------------
full.set_interrupt_thresholds(100, 801)
check_true('thresholds_encode', mock.reg16(0x0074) == 100 and mock.reg16(0x0072) == 801)
check_true('thresholds_decode', full.interrupt_thresholds() == (100, 801))
check_true('thresholds_reject_order', raises(lambda: full.set_interrupt_thresholds(500, 100)))
full.set_address(0x30)
check_true('set_address', mock.registers[0x0001] == 0x30)
check_true('address_rejects_range', raises(lambda: full.set_address(0x78)))
check_true('model_id', full.model_id() == 0xEA)
check_true('module_type', full.module_type() == 0xCC)
check_true('revision_id', full.revision_id() == 0x10)

# --- Interrupt API --------------------------------------------------------
full.enable_interrupt(SOURCE_OUT_OF_WINDOW)
check_true('enable_out_of_window', mock.registers[0x0046] == 0x02)
full.enable_interrupt(SOURCE_IN_WINDOW)
check_true('enable_in_window', mock.registers[0x0046] == 0x03)
full.disable_interrupt(SOURCE_LEVEL_LOW)
check_true('disable_inactive_source_ignored', mock.registers[0x0046] == 0x03)
full.disable_interrupt(SOURCE_IN_WINDOW)
check_true('disable_reverts_to_new_sample', mock.registers[0x0046] == 0x20)
full.disable_interrupt(SOURCE_NEW_SAMPLE_READY)
check_true('disable_new_sample_noop', mock.registers[0x0046] == 0x20)
mock.pending = False
check_true('poll_interrupt_none', full.poll_interrupt() == 0)
full.enable_interrupt(SOURCE_OUT_OF_WINDOW)
mock.pending = True
check_true('poll_interrupt_value', full.poll_interrupt() == SOURCE_OUT_OF_WINDOW)
check_true('poll_interrupt_clears', not mock.pending)
full.enable_interrupt(SOURCE_NEW_SAMPLE_READY)
mock.pending = True



class FakePin:
    def __init__(self):
        self.handler = None

    def on_edge(self, handler, trigger=None):
        self.handler = handler

    def off_edge(self, handler):
        self.handler = None


pin = FakePin()
got = []
full.on_interrupt(got.append, pin)
pin.handler()
full.off_interrupt()
check_true('on_interrupt_callback', got == [SOURCE_NEW_SAMPLE_READY] and pin.handler is None)
check_true('enable_rejects_range', raises(lambda: full.enable_interrupt(6)))

print('Passed: {}, Failed: {}'.format(passed, failed))
print('===DONE===')
