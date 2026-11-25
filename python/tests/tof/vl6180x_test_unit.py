"""Unit test for the VL6180X — runs without hardware using the I2C mock.

Verifies the initialization sequence (private settings only while fresh out
of reset, recommended settings), single-shot ranging and error codes, ALS
and lux conversion, continuous modes, gain / integration / scaler,
thresholds with the grouped-parameter-hold protocol, offset, crosstalk,
VHV recalibration, address change, identification, history and interrupts.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.tof.vl6180x import (
    VL6180XMinimal, VL6180XFull, SOURCE_RANGE, SOURCE_ALS, SOURCE_ERROR,
    MODE_LEVEL_LOW, MODE_OUT_OF_WINDOW, MODE_NEW_SAMPLE_READY)

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    check_eq(label, bool(condition), True)


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


class Sim(I2CConnectionMock):
    """VL6180X simulator: starting a measurement makes a result pending,
    writing SYSTEM__INTERRUPT_CLEAR drops it."""

    def __init__(self, fresh=1):
        super().__init__(reg_bytes=2)
        self.set_register(0x000, 0xB4, 0x01, 0x03)
        self.set_register(0x016, fresh)
        self.set_register(0x062, 42)
        self.set_register(0x050, 0x01, 0x90)   # ALS 400 counts
        self.set_register(0x04D, 0x00)

    def write(self, data):
        super().write(data)
        data = bytes(data)
        if len(data) > 2:
            reg = int.from_bytes(data[:2], 'big')
            value = data[2]
            if reg == 0x018 and value & 1:
                self.registers[0x04F] = (self.registers.get(0x04F, 0) & ~0x07) | 4
            elif reg == 0x038 and value & 1:
                self.registers[0x04F] = (self.registers.get(0x04F, 0) & ~0x38) | (4 << 3)
            elif reg == 0x015:
                self.registers[0x04F] = 0
            elif reg == 0x02E:
                self.registers[0x02E] = 0


def writes_of(sim, reg):
    return [w[2:] for w in sim.writes if len(w) > 2 and int.from_bytes(w[:2], 'big') == reg]


# --- Initialization ---
sim = Sim()
chip = VL6180XMinimal(sim)
check_eq('private 0x0207 written', writes_of(sim, 0x0207), [b'\x01'])
check_eq('private 0x00DB written', writes_of(sim, 0x00DB), [b'\xCE'])
check_eq('fresh flag cleared', writes_of(sim, 0x016), [b'\x00'])
check_eq('GPIO1 mode', writes_of(sim, 0x011), [b'\x10'])
check_eq('averaging period', writes_of(sim, 0x10A), [b'\x30'])
check_eq('ALS gain 1', writes_of(sim, 0x03F), [b'\x46'])
check_eq('VHV repeat rate', writes_of(sim, 0x031), [b'\xFF'])
check_eq('integration 100 ms', writes_of(sim, 0x040), [b'\x00\x63'])
check_eq('interrupt config', writes_of(sim, 0x014), [b'\x24'])
check_eq('range period', writes_of(sim, 0x01B), [b'\x09'])

sim2 = Sim(fresh=0)
VL6180XMinimal(sim2)
check_eq('no private settings when not fresh', writes_of(sim2, 0x0207), [])
check_eq('recommended settings still applied', writes_of(sim2, 0x014), [b'\x24'])

# --- Single-shot range ---
check_eq('read_range', chip.read_range(), 42)
check_true('range start written', b'\x01' in writes_of(sim, 0x018))
check_eq('interrupt cleared after read', writes_of(sim, 0x015)[-1], b'\x07')
sim.set_register(0x04D, 0x70)
check_true('range error raises', raises(chip.read_range, RuntimeError))
sim.set_register(0x04D, 0x00)

# --- Full ---
sim = Sim()
full = VL6180XFull(sim)
sim.set_register(0x04D, 0x60)
check_eq('read_range_status', full.read_range_status(), (42, 6))
sim.set_register(0x04D, 0x00)

check_eq('read_als_raw', full.read_als_raw(), 400)
check_true('lux gain 1', abs(full.read_lux() - 0.32 * 400 / 1.01) < 1e-9)
full.set_als_gain(0)
check_eq('gain written with 0x40', writes_of(sim, 0x03F)[-1], b'\x40')
full.set_als_integration_time(50)
check_eq('integration code', writes_of(sim, 0x040)[-1], b'\x00\x31')
check_true('lux gain 20, 50 ms', abs(full.read_lux() - 0.32 * (400 / 20.0) * 2) < 1e-9)
hold = writes_of(sim, 0x017)
check_eq('grouped hold toggled', hold[-2:], [b'\x01', b'\x00'])
check_true('bad gain', raises(lambda: full.set_als_gain(8), ValueError))
check_true('bad integration', raises(lambda: full.set_als_integration_time(513), ValueError))
full.set_als_scaler(4)
check_eq('scaler', writes_of(sim, 0x120)[-1], b'\x04')
check_true('bad scaler', raises(lambda: full.set_als_scaler(17), ValueError))

full.start_continuous_range(50)
check_eq('continuous period code', writes_of(sim, 0x01B)[-1], b'\x04')
check_eq('continuous start', writes_of(sim, 0x018)[-1], b'\x03')
sim.registers[0x04F] = 4
check_eq('read_range_continuous', full.read_range_continuous(), 42)
full.stop_continuous_range()
check_eq('stop', writes_of(sim, 0x018)[-1], b'\x01')
check_true('bad period', raises(lambda: full.start_continuous_range(5), ValueError))
check_true('bad period high', raises(lambda: full.start_continuous_range(2560), ValueError))

full.start_continuous_als(100)
check_eq('ALS period code', writes_of(sim, 0x03E)[-1], b'\x09')
check_eq('ALS continuous start', writes_of(sim, 0x038)[-1], b'\x03')
full.stop_continuous_als()
check_eq('ALS stop', writes_of(sim, 0x038)[-1], b'\x01')
full.set_interleaved(True)
check_eq('interleaved on', writes_of(sim, 0x2A3)[-1], b'\x01')
full.set_interleaved(False)
check_eq('interleaved off', writes_of(sim, 0x2A3)[-1], b'\x00')

full.set_range_thresholds(30, 80)
check_eq('range high', writes_of(sim, 0x019)[-1], b'\x50')
check_eq('range low', writes_of(sim, 0x01A)[-1], b'\x1E')
check_true('bad range thresholds', raises(lambda: full.set_range_thresholds(90, 80), ValueError))
full.set_als_thresholds(100, 5000)
check_eq('ALS high', writes_of(sim, 0x03A)[-1], b'\x13\x88')
check_eq('ALS low', writes_of(sim, 0x03C)[-1], b'\x00\x64')
full.set_max_convergence_time(20)
check_eq('convergence', writes_of(sim, 0x01C)[-1], b'\x14')
check_true('bad convergence', raises(lambda: full.set_max_convergence_time(64), ValueError))
full.set_averaging_period(100)
check_eq('averaging', writes_of(sim, 0x10A)[-1], b'\x64')

full.set_range_offset(-5)
check_eq('offset written', writes_of(sim, 0x024)[-1], b'\xFB')
check_eq('offset read', full.get_range_offset(), -5)
check_true('bad offset', raises(lambda: full.set_range_offset(128), ValueError))
full.set_crosstalk_rate(0.5)
check_eq('crosstalk 9.7', writes_of(sim, 0x01E)[-1], b'\x00\x40')
check_true('bad crosstalk', raises(lambda: full.set_crosstalk_rate(-1.0), ValueError))
full.recalibrate_vhv()
check_eq('vhv started', writes_of(sim, 0x02E)[-1], b'\x01')

full.set_address(0x30)
check_eq('address', writes_of(sim, 0x212)[-1], b'\x30')
check_true('bad address', raises(lambda: full.set_address(0x80), ValueError))
check_eq('model id', full.model_id(), 0xB4)
check_eq('revision', full.revision(), (1, 3))
sim.set_register(0x052, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0)
check_eq('history', full.read_history(), [1, 2, 3, 4, 5, 6, 7, 256])

# --- Interrupts ---
sim.registers[0x014] = 0x24
full.enable_interrupt(SOURCE_RANGE, MODE_OUT_OF_WINDOW)
check_eq('range mode', writes_of(sim, 0x014)[-1], b'\x23')
full.enable_interrupt(SOURCE_ALS, MODE_LEVEL_LOW)
check_eq('ALS mode', writes_of(sim, 0x014)[-1], b'\x0B')
full.disable_interrupt(SOURCE_RANGE)
check_eq('range disabled', writes_of(sim, 0x014)[-1], b'\x08')
check_true('bad source', raises(lambda: full.enable_interrupt(SOURCE_ERROR, 1), ValueError))
check_true('bad mode', raises(lambda: full.enable_interrupt(SOURCE_RANGE, 5), ValueError))

sim.registers[0x04F] = 0x04
check_eq('poll range', full.poll_interrupt(), SOURCE_RANGE)
check_eq('poll cleared', sim.registers[0x04F], 0)
sim.registers[0x04F] = 0x24
check_eq('poll range+ALS', full.poll_interrupt(), SOURCE_RANGE | SOURCE_ALS)
sim.registers[0x04F] = 0x40
check_eq('poll error', full.poll_interrupt(), SOURCE_ERROR)
check_eq('poll none', full.poll_interrupt(), 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
raise SystemExit(0 if failed == 0 else 1)
