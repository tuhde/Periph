"""Unit test for the FXOS8700 — runs without hardware using the I2C mock.

Verifies the initialization sequence (including the tolerated NACK on the
reset write), the accelerometer / magnetometer conversions, the hybrid burst,
the STANDBY handling around configuration writes, offsets, detectors, FIFO,
interrupt routing and the self-test.
"""

import math

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.fxos8700 import (
    FXOS8700Minimal, FXOS8700Full, FXOS8700Source, I2C_ADDRESS)

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


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def check_close(label, got, expected, tolerance=1e-6):
    check_true('{} (got {}, expected {})'.format(label, got, expected),
               abs(got - expected) <= tolerance)


def raises(fn, exception=ValueError):
    try:
        fn()
    except exception:
        return True
    except Exception:
        return False
    return False


def make(registers=None):
    regs = {0x0D: 0xC7}
    regs.update(registers or {})
    mock = I2CConnectionMock(regs)
    return mock, FXOS8700Full(mock)


def last_write(mock, reg):
    value = None
    for w in mock.writes:
        if len(w) >= 2 and w[0] == reg:
            value = w[1:]
    return value


# --- Initialization -----------------------------------------------------------
mock, chip = make()
init_writes = [(w[0], w[1]) for w in mock.writes if len(w) == 2]
check_eq('init sequence', init_writes,
         [(0x2B, 0x40), (0x2A, 0x00), (0x5B, 0x1F), (0x5C, 0x20),
          (0x0E, 0x00), (0x2B, 0x00), (0x2A, 0x11)])
check_eq('default address', I2C_ADDRESS, 0x1E)

mock = I2CConnectionMock({0x0D: 0xC4})
FXOS8700Minimal(mock)
check_true('pre-production WHO_AM_I accepted', True)
check_true('wrong WHO_AM_I raises', raises(lambda: FXOS8700Minimal(I2CConnectionMock({0x0D: 0x1A}))))

mock = I2CConnectionMock({0x0D: 0xC7})
mock.fail_next_writes(1)
FXOS8700Minimal(mock)
check_true('NACK on reset write is tolerated', True)

# --- Measurements -------------------------------------------------------------
mock, chip = make({0x01: 0x40, 0x02: 0x00, 0x03: 0xC0, 0x04: 0x00, 0x05: 0x00, 0x06: 0x04,
                   0x33: 0x00, 0x34: 0x64, 0x35: 0xFF, 0x36: 0x9C, 0x37: 0x01, 0x38: 0xF4})
x, y, z = chip.read_accel()
check_close('accel x = +1 g', x, 1.0)
check_close('accel y = -1 g', y, -1.0)
check_close('accel z = 1/4096 g', z, 1 / 4096.0)
check_eq('raw accel', chip.read_raw_accel(), (4096, -4096, 1))
mx, my, mz = chip.read_mag()
check_close('mag x = 10 uT', mx, 10.0)
check_close('mag y = -10 uT', my, -10.0)
check_close('mag z = 50 uT', mz, 50.0)
check_eq('raw mag', chip.read_raw_mag(), (100, -100, 500))
# The mock has no auto-increment jump: the mag half of the burst sits at 0x07-0x0C.
mock.set_register(0x07, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)
(ax, ay, az), (hx, hy, hz) = chip.read_hybrid()
check_close('hybrid accel x', ax, 1.0)
check_close('hybrid mag z', hz, 50.0)
mock.registers[0x0B] = 0  # the burst overlay above clobbered SYSMOD in the mock
chip.set_range(4)
check_close('±4 g sensitivity', chip.read_accel()[0], 2.0)
chip.set_range(8)
check_close('±8 g sensitivity', chip.read_accel()[0], 4.0)
check_eq('XYZ_DATA_CFG fs=8g', last_write(mock, 0x0E), bytes([0x02]))
check_true('range 3 invalid', raises(lambda: chip.set_range(3)))
check_true('low-noise at 8 g raises', raises(lambda: chip.set_low_noise(True)))

mock.registers[0x51] = 0xFB
check_close('temperature', chip.read_temperature(), -5 * 0.96)
chip.set_mode(0)
check_true('temperature in accel-only raises', raises(chip.read_temperature))
check_true('hybrid read in accel-only raises', raises(chip.read_hybrid))
chip.set_mode(2)

# --- Standby handling -----------------------------------------------------------
mock, chip = make()
mock.writes.clear()
chip.set_data_rate(100)
writes = [(w[0], w[1]) for w in mock.writes if len(w) == 2]
check_eq('set_data_rate goes through STANDBY',
         writes, [(0x2A, 0x10), (0x2A, 0x18), (0x2A, 0x19)])
check_true('rate 77 invalid', raises(lambda: chip.set_data_rate(77)))
chip.set_data_rate(1.56)
check_eq('1.56 Hz selects dr=7', last_write(mock, 0x2A)[0] >> 3 & 7, 7)

mock, chip = make()
chip.set_low_noise(True)
check_eq('lnoise bit set', last_write(mock, 0x2A)[0] & 0x05, 0x05)
chip.set_resolution(8)
check_eq('f_read bit set', last_write(mock, 0x2A)[0] & 0x02, 0x02)
mock.registers.update({0x01: 0x40, 0x03: 0xC0, 0x05: 0x20})
check_close('8-bit accel x = 1 g', chip.read_accel()[0], 64 / 64.0)
chip.set_resolution(14)
chip.set_accel_oversampling(2, sleep_mode=3)
check_eq('mods/smods', last_write(mock, 0x2B)[0] & 0x1B, 0x1A)
chip.set_mag_oversampling(5, sleep_ratio=2)
check_eq('m_os', last_write(mock, 0x5B)[0] & 0x1C, 5 << 2)
check_eq('m_aslp_os', last_write(mock, 0x5D)[0] & 0x70, 2 << 4)
chip.set_high_pass_output(True, 2)
check_eq('hpf_out', last_write(mock, 0x0E)[0] & 0x10, 0x10)
check_eq('hp sel', last_write(mock, 0x0F)[0] & 0x03, 2)

# --- Offsets ------------------------------------------------------------------------
chip.set_accel_offset(0.06, -0.06, 1.0)
check_eq('accel offset registers', last_write(mock, 0x2F), bytes([30, 0xE2, 127]))
ox, oy, oz = chip.get_accel_offset()
check_close('accel offset x', ox, 0.06)
check_close('accel offset z clamped', oz, 0.254)
chip.set_mag_offset(60.0, -60.0, 0.0)
check_eq('mag offset registers', last_write(mock, 0x3F),
         bytes([0x04, 0xB0, 0xFB, 0x50, 0x00, 0x00]))
mx, my, mz = chip.get_mag_offset()
check_close('mag offset x', mx, 60.0)
check_close('mag offset y', my, -60.0)

# --- Magnetometer helpers ------------------------------------------------------------
class OneShotMock(I2CConnectionMock):
    """Clears M_CTRL_REG1.m_ost once read, like the hardware."""

    def write_read(self, data, n):
        out = super().write_read(data, n)
        if data[0] == 0x5B:
            self.registers[0x5B] &= ~0x20
        return out


mock = OneShotMock({0x0D: 0xC7})
chip = FXOS8700Full(mock)
chip.set_mag_auto_calibration(True)
check_eq('m_acal set', last_write(mock, 0x5B)[0] & 0x80, 0x80)
chip.set_mag_auto_calibration(False)
check_eq('m_acal cleared', last_write(mock, 0x5B)[0] & 0x80, 0)
chip.reset_mag_min_max()
check_eq('m_maxmin_rst', last_write(mock, 0x5C)[0] & 0x04, 0x04)
chip.trigger_mag_reset()
check_eq('m_rst', last_write(mock, 0x5B)[0] & 0x40, 0x40)
chip.set_mag_reset(2)
check_eq('m_rst_cnt', last_write(mock, 0x5C)[0] & 0x03, 2)
mock.registers.update({0x4B: 0xFF, 0x4C: 0x9C, 0x45: 0x00, 0x46: 0x64})
mn, mxv = chip.read_mag_min_max()
check_close('min x', mn[0], -10.0)
check_close('max x', mxv[0], 10.0)
check_eq('one-shot returns data', len(chip.measure_mag_once()), 3)

# --- Heading ---------------------------------------------------------------------------
mock, chip = make({0x01: 0x00, 0x02: 0x00, 0x03: 0x00, 0x04: 0x00, 0x05: 0x40, 0x06: 0x00,
                   0x33: 0x01, 0x34: 0xF4, 0x35: 0x00, 0x36: 0x00, 0x37: 0x00, 0x38: 0x00})
chip.set_mode(0)  # separate accel / mag reads (the mock has no hybrid pointer jump)
check_close('heading north (flat, +X field)', chip.heading(), 0.0, 1e-6)
check_close('heading with declination', chip.heading(10.0), 10.0, 1e-6)
mock.registers.update({0x33: 0x00, 0x34: 0x00, 0x35: 0xFE, 0x36: 0x0C})
check_close('heading east (field along -Y)', chip.heading(), 90.0, 1e-6)

# --- FIFO ---------------------------------------------------------------------------------
mock, chip = make({0x00: 0x42, 0x01: 0x40, 0x03: 0x00, 0x05: 0x00})
chip.set_fifo(1, 16)
check_eq('F_SETUP circular wm 16', last_write(mock, 0x09), bytes([0x50]))
chip.set_fifo(2, 8)
f_writes = [w[1] for w in mock.writes if len(w) == 2 and w[0] == 0x09]
check_eq('mode change goes through disabled', f_writes[-3:], [0x50, 0x00, 0x88])
check_eq('fifo level', chip.fifo_level(), 2)
check_eq('fifo status', chip.read_fifo_status(), (False, True, 2))
check_eq('read_fifo count', len(chip.read_fifo()), 2)
check_true('resolution with FIFO raises', raises(lambda: chip.set_resolution(8)))
chip.set_fifo_trigger(transient=True, motion=True)
check_eq('TRIG_CFG', last_write(mock, 0x0A), bytes([0x24]))
check_true('bad fifo mode raises', raises(lambda: chip.set_fifo(4)))

# --- Detectors ------------------------------------------------------------------------------
mock, chip = make()
chip.set_freefall_motion(True, axes=7, threshold_g=0.3, debounce_ms=60, latch=True)
check_eq('A_FFMT_CFG', last_write(mock, 0x15), bytes([0xF8 | 0x00]))
check_eq('A_FFMT_THS', last_write(mock, 0x17), bytes([5]))
check_eq('A_FFMT_COUNT (hybrid 100 Hz row, 10 ms)', last_write(mock, 0x18), bytes([6]))
chip.set_freefall_motion(False, axes=3, threshold_g=0.2, debounce_clear=True)
check_eq('freefall CFG', last_write(mock, 0x15), bytes([0x18]))
check_eq('freefall THS dbcntm', last_write(mock, 0x17), bytes([0x83]))
chip.set_transient(axes=6, threshold_g=0.5, debounce_ms=40, bypass_hpf=True, latch=True)
check_eq('TRANSIENT_CFG', last_write(mock, 0x1D), bytes([0x10 | 0x0C | 1]))
check_eq('TRANSIENT_THS', last_write(mock, 0x1F), bytes([8]))
check_eq('TRANSIENT_COUNT', last_write(mock, 0x20), bytes([4]))
chip.set_pulse(single=1, double=4, threshold_g=(0.5, 0.5, 1.0), time_limit_ms=30,
               latency_ms=100, window_ms=300, latch=True)
check_eq('PULSE_CFG', last_write(mock, 0x21), bytes([0x40 | 0x01 | 0x20]))
check_eq('PULSE thresholds', last_write(mock, 0x23), bytes([8, 8, 16]))
check_eq('PULSE_TMLT', last_write(mock, 0x26), bytes([3]))
check_eq('PULSE_LTCY', last_write(mock, 0x27), bytes([5]))
check_eq('PULSE_WIND', last_write(mock, 0x28), bytes([15]))
check_eq('HP_FILTER pulse bits', last_write(mock, 0x0F)[0] & 0x30, 0x10)
chip.set_pulse(single=1, lpf=False, hpf=False, time_limit_ms=30)
check_eq('pulse no-LPF TMLT', last_write(mock, 0x26), bytes([12]))
check_eq('pulse bypass HPF', last_write(mock, 0x0F)[0] & 0x30, 0x20)
chip.set_orientation(debounce_ms=100, threshold_deg=45, hysteresis=4, z_lock=4, back_front=2)
check_eq('PL_CFG', last_write(mock, 0x11), bytes([0xC0]))
check_eq('PL_COUNT', last_write(mock, 0x12), bytes([10]))
check_eq('PL_THS_REG', last_write(mock, 0x14), bytes([0x84]))
check_eq('PL_BF_ZCOMP', last_write(mock, 0x13), bytes([0x84]))
check_true('orientation overflow raises',
           raises(lambda: chip.set_orientation(threshold_deg=75, hysteresis=7)))
check_true('bad angle raises', raises(lambda: chip.set_orientation(threshold_deg=50)))
mock.registers[0x10] = 0x80 | 0x40 | (3 << 1) | 1
check_eq('read_orientation', chip.read_orientation(), (True, True, 3, True))

chip.set_auto_sleep(True, sleep_rate_hz=12.5, timeout_s=10,
                    wake_sources=FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_PULSE)
check_eq('aslp rate', last_write(mock, 0x2A)[0] >> 6, 1)
check_eq('ASLP_COUNT', last_write(mock, 0x29), bytes([31]))
check_eq('slpe', last_write(mock, 0x2B)[0] & 0x04, 0x04)
check_eq('wake bits', last_write(mock, 0x2C)[0] & 0x7C, 0x18)
check_true('bad wake source raises', raises(lambda: chip.set_auto_sleep(True, wake_sources=0x01)))
chip.set_fifo_gate(True)
check_eq('fifo gate', last_write(mock, 0x2C)[0] & 0x80, 0x80)
chip.set_interrupt_pins(active_high=True, open_drain=True)
check_eq('ipol / pp_od', last_write(mock, 0x2C)[0] & 0x03, 0x03)

# --- Interrupts ---------------------------------------------------------------------------------
mock, chip = make()
chip.enable_interrupt(FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_ORIENTATION, 1)
check_eq('CTRL_REG4 enabled', last_write(mock, 0x2D), bytes([0x14]))
check_eq('CTRL_REG5 routed INT1', last_write(mock, 0x2E), bytes([0x14]))
chip.enable_interrupt(FXOS8700Source.SOURCE_FF_MT, 2)
check_eq('CTRL_REG5 routed INT2', last_write(mock, 0x2E), bytes([0x10]))
chip.disable_interrupt(FXOS8700Source.SOURCE_ORIENTATION)
check_eq('CTRL_REG4 disabled', last_write(mock, 0x2D), bytes([0x04]))
check_true('bad pin raises', raises(lambda: chip.enable_interrupt(1, 3)))
mock.registers[0x0C] = 0x14
mock.writes.clear()
check_eq('poll_interrupt', chip.poll_interrupt(), 0x14)
read_regs = [w[0] for w in mock.writes]
check_true('poll reads source registers', 0x16 in read_regs and 0x10 in read_regs)
check_true('callback needs valid pin', raises(lambda: chip.on_interrupt(lambda s: None, pin=3)))

# --- Register escape hatch / misc ----------------------------------------------------------------
mock, chip = make()
chip.write_register(0x5F, bytes([1, 2]))
check_eq('write_register', last_write(mock, 0x5F), bytes([1, 2]))
check_eq('read_register', chip.read_register(0x5F, 2), bytes([1, 2]))
mock.registers[0x00] = 0x08
check_true('new_accel_data', chip.new_accel_data())
mock.registers[0x32] = 0x00
check_true('new_mag_data false', not chip.new_mag_data())
mock.registers[0x0B] = 0x02
check_eq('system mode', chip.read_system_mode(), 2)
chip.reset()
check_true('reset leaves hybrid read disabled', raises(chip.read_hybrid))


# --- Self-test ---------------------------------------------------------------------------------------
class SelfTestMock(I2CConnectionMock):
    """Returns shifted accelerometer data while CTRL_REG2.st is set."""

    def __init__(self, delta, **kw):
        super().__init__(**kw)
        self.delta = delta

    def write_read(self, data, n):
        out = bytearray(super().write_read(data, n))
        if data[0] == 0x01 and (self.registers.get(0x2B, 0) & 0x80):
            for axis, d in enumerate(self.delta):
                raw = (d << 2) & 0xFFFF
                out[2 * axis] = raw >> 8
                out[2 * axis + 1] = raw & 0xFF
        return bytes(out)


mock = SelfTestMock((200, 300, 1300), registers={0x0D: 0xC7})
chip = FXOS8700Full(mock)
ok, dx, dy, dz = chip.self_test()
check_true('self-test passes', ok)
check_eq('self-test deltas', (dx, dy, dz), (200, 300, 1300))
check_eq('self-test restores CTRL_REG1', last_write(mock, 0x2A), bytes([0x11]))
mock = SelfTestMock((10, 10, 10), registers={0x0D: 0xC7})
check_true('self-test fails on weak response', not FXOS8700Full(mock).self_test()[0])

print('{} passed, {} failed'.format(passed, failed))
if failed:
    raise SystemExit(1)
