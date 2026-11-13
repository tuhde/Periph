"""Unit test for the PCA9541 — runs without hardware using the I2C mock.

Verifies the initialization sequence, the datasheet's Table 12 write rule for
all 16 CONTROL read values, acquire / release / connect / disconnect, bus
recovery, interrupt masks and sources, the functional INT test, the
auto-increment read and interrupt delivery by observing the mock's registers
and writes.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
import periph.chips.other.pca9541 as m
from periph.chips.other.pca9541 import PCA9541Minimal, PCA9541Full

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


def data_writes(mock):
    return [w for w in mock.writes if len(w) > 1]


class SimMock(I2CConnectionMock):
    """Simulates how the chip recomputes the read-only N bits after a CONTROL write (this master's view)."""

    def write(self, data):
        prior = self.registers.get(0x01, 0)
        I2CConnectionMock.write(self, data)
        if len(data) == 2 and data[0] == 0x01:
            w = data[1]
            mybus = w & 0x01
            buson = (w >> 2) & 1
            nmybus_prior = (prior >> 1) & 1
            nbuson_prior = (prior >> 3) & 1
            control = (mybus == nmybus_prior)        # the master takes control when it writes MYBUS = NMYBUS_read
            on = (buson != nbuson_prior)             # BUSON = NOT NBUSON_read connects
            nmybus = mybus if control else 1 - mybus
            nbuson = 1 - buson if on else buson
            self.registers[0x01] = (w & 0xF5) | (nmybus << 1) | (nbuson << 3)


def fresh(control=0x04, ie=0x00, istat=0x00):
    mock = SimMock(registers={0x00: ie, 0x01: control, 0x02: istat,
                              0x10: ie, 0x11: control, 0x12: istat})
    return mock, PCA9541Full(mock)


# --- init: bus check + ISTAT read, nothing written
mock, chip = fresh()
check_true('init_writes_nothing', data_writes(mock) == [])
check_true('init_reads_control_and_istat', [w[0] for w in mock.writes] == [0x01, 0x02])

try:
    bad = I2CConnectionMock(registers={})
    bad.fail_next_writes(1)
    PCA9541Minimal(bad)
    check_true('init_raises_when_not_acknowledged', False)
except OSError:
    check_true('init_raises_when_not_acknowledged', True)

# --- Table 12: the byte acquire writes for each CONTROL read value (low nibble)
TABLE12 = {0x0: 0x4, 0x1: 0x4, 0x2: 0x5, 0x3: 0x5, 0x5: 0x4, 0x6: 0x5, 0x9: 0x0, 0xA: 0x1,
           0xC: 0x0, 0xD: 0x0, 0xE: 0x1, 0xF: 0x1}
ok = True
for read, want in TABLE12.items():
    mock, chip = fresh(control=read)
    chip.acquire(timeout_ms=0)
    ok = ok and data_writes(mock)[-1] == bytes([0x01, want])
check_true('acquire_follows_table12', ok)

# rows marked "no write required" (already has control, bus on): the write keeps the state
for read in (0x4, 0x7, 0x8, 0xB):
    mock, chip = fresh(control=read)
    check_true('acquire_keeps_control_%X' % read, chip.acquire(timeout_ms=0) is True)

# --- has_control: MYBUS == NMYBUS and BUSON != NBUSON
cases = {0x04: True, 0x0A: False, 0x00: False, 0x02: False, 0x07: True, 0x05: False}
check_true('has_control_values', all(fresh(control=c)[1].has_control() == v for c, v in cases.items()))

# --- acquire with bus_init waits for BUSINIT in ISTAT
mock, chip = fresh(control=0x0A, istat=0x02)
check_true('acquire_bus_init_ok', chip.acquire(bus_init=True) is True)
check_true('acquire_bus_init_sets_bit', data_writes(mock)[-1] == bytes([0x01, 0x11]))
mock, chip = fresh(control=0x0A, istat=0x00)
check_true('acquire_bus_init_times_out', chip.acquire(bus_init=True, timeout_ms=3) is False)

# --- release: MYBUS = NOT NMYBUS, bus stays on
mock, chip = fresh(control=0x04)
chip.release()
check_true('release_write', data_writes(mock)[-1] == bytes([0x01, 0x05]) and chip.has_control() is False)

# --- connect / disconnect keep ownership
mock, chip = fresh(control=0x0D)   # NBUSON = BUSON = 1 (bus off), MYBUS = 1
chip.connect()
check_true('connect_write', data_writes(mock)[-1] == bytes([0x01, 0x01]))
mock, chip = fresh(control=0x04)                  # bus on, has control
chip.disconnect()
check_true('disconnect_write', data_writes(mock)[-1] == bytes([0x01, 0x00]))
check_true('disconnect_connected_false', chip.is_connected() is False)
check_true('connect_makes_connected', (chip.connect(), chip.is_connected())[1] is True)

# --- get_control / status
mock, chip = fresh(control=0xD4)
check_true('get_control', chip.get_control() == 0xD4)
st = chip.status()
check_true('status_decode', st == {'has_control': True, 'connected': True, 'bus_init_requested': True,
                                   'own_test': True, 'other_test': True})
mock, chip = fresh(control=0x0A)
st = chip.status()
check_true('status_no_control', st['has_control'] is False and st['connected'] is True)

# --- recover_bus = acquire with bus_init
mock, chip = fresh(control=0x0A, istat=0x02)
check_true('recover_bus', chip.recover_bus() is True and data_writes(mock)[-1][1] & 0x10)

# --- interrupt mask
mock, chip = fresh()
chip.set_interrupt_mask(bus_lost=True, int_in=True)
check_true('set_interrupt_mask', mock.registers[0x00] == 0x09 and chip.get_interrupt_mask() == 0x09)
chip.set_interrupt_mask()
check_true('set_interrupt_mask_defaults_unmask', mock.registers[0x00] == 0x00)
chip.disable_interrupt(PCA9541Full.SOURCE_BUS_INIT)
check_true('disable_interrupt', mock.registers[0x00] == 0x02)
chip.disable_interrupt(PCA9541Full.SOURCE_INT_IN)
chip.enable_interrupt(PCA9541Full.SOURCE_BUS_INIT)
check_true('enable_interrupt_clears_one_bit', mock.registers[0x00] == 0x01)
n = len(data_writes(mock))
chip.enable_interrupt(PCA9541Full.SOURCE_TEST)
check_true('test_source_needs_no_enable', len(data_writes(mock)) == n)

# --- functional INT test keeps the bus bits
mock, chip = fresh(control=0x0C | 0x01)           # arbitrary state
bits = mock.registers[0x01] & 0x05
chip.test_interrupt(own=True)
check_true('test_interrupt_own', mock.registers[0x01] & 0xC5 == (bits | 0x40))
chip.test_interrupt(other=True)
check_true('test_interrupt_other', mock.registers[0x01] & 0xC0 == 0x80)
chip.test_interrupt(own=True, other=True)
check_true('test_interrupt_both', mock.registers[0x01] & 0xC0 == 0xC0)
chip.test_interrupt()
check_true('test_interrupt_clear', mock.registers[0x01] & 0xC0 == 0 and mock.registers[0x01] & 0x05 == bits)

# --- read_all is one auto-increment read of 0x10 for three bytes
mock, chip = fresh(control=0x04, ie=0x03, istat=0x08)
n = len(mock.writes)
check_true('read_all_values', chip.read_all() == (0x03, 0x04, 0x08))
check_true('read_all_auto_increment', len(mock.writes) == n + 1 and mock.writes[-1][0] == 0x10)

# --- poll_interrupt / callback
mock, chip = fresh(istat=0x0A)
check_true('poll_interrupt', chip.poll_interrupt() == 0x0A)
seen = []
chip._callback = lambda s: seen.append(s)
chip._int_handler()
mock.registers[0x02] = 0x00
chip._int_handler()
chip.off_interrupt()
check_true('callback_receives_status', seen == [0x0A] and chip._callback is None)

# --- minimal class has no Full-only methods
check_true('minimal_has_no_connect', not hasattr(fresh()[1].__class__.__bases__[0], 'connect'))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
