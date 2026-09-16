"""ADE7953 unit test for Python/Linux — exercises the driver against
I2CConnectionMock, with no hardware required.

Run via ``python/test_linux.sh --level unit power/ade7953``.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.ade7953 import ADE7953Full, ADE7953Source, ADE7953CFSource


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
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


# Full-scale codes in the input registers -> expect driver-converted
# engineering units.
VOLTAGE_GAIN = 100.0
CURRENT_GAIN = 10.0

connection = I2CConnectionMock()
ade = ADE7953Full(connection, VOLTAGE_GAIN, CURRENT_GAIN)

# --- voltage ---
# ADC_FS_VOLTS=0.3535, ADC_FS_CODE=9032007 -> at PGA=1, gain=100, raw=9032007 -> 35.3553 V
connection.set_register(0x21C, 0x89, 0xD1, 0x47)   # VRMS = 9032007 raw
v_expected = 35.3553390593
check_eq('voltage full-scale', round(ade.voltage(), 4), round(v_expected, 4))

# --- current Channel A ---
connection.set_register(0x21A, 0x89, 0xD1, 0x47)   # IRMSA = 9032007 raw
i_expected = 3.53553390593
check_eq('current_a full-scale', round(ade.current(), 4), round(i_expected, 4))

# --- active_power ---
# POWER_FS_CODE = 4862401, ADC_FS_VOLTS^2 = 0.125 exactly.
connection.set_register(0x212, 0x4A, 0x31, 0xC1)   # AWATT = 4862401 raw
p_expected = (4862401 * 0.125 * 100 * 10) / 4862401
check_eq('active_power full-scale', round(ade.active_power(), 4), round(p_expected, 4))

# --- active_energy ---
connection.set_register(0x21E, 0x01, 0x00, 0x00)   # AENERGYA = 65536 raw
e_expected = 65536 * (0.125 * 100 * 10 * (1.0 / 206900.0)) / 3600.0
check_eq('active_energy scale', round(ade.active_energy(), 6), round(e_expected, 6))

# --- configure_no_load round-trip ---
ade.configure_no_load(active=0xABCDEF, reactive=0x123456, apparent=0x987654)
# The driver writes the 24-bit values MSB-first; the last 24-bit write
# should be the apparent threshold (0x987654 -> [0x98, 0x76, 0x54]).
last_write = connection.writes[-1]
check_eq('configure_no_load last byte MSB',    last_write[-3], 0x98)
check_eq('configure_no_load last byte middle', last_write[-2], 0x76)
check_eq('configure_no_load last byte LSB',    last_write[-1], 0x54)

# --- interrupt enable/disable round-trip ---
# IRQENA defaults to 0x100000 (Reset bit set, everything else zero).
# Preload the default so the OR-with-ZXV produces 0x108000.
connection.set_register(0x22C, 0x00, 0x10, 0x00, 0x00)
ade.enable_interrupt(ADE7953Source.ZXV)
ena_write = connection.writes[-1]
# The IRQENA register write payload is sent MSB-first:
# 0x00108000 -> [0x00, 0x10, 0x80, 0x00]
check_eq('enable ZXV: ENB byte3 MSB', ena_write[-4], 0x00)
check_eq('enable ZXV: ENB byte2',     ena_write[-3], 0x10)
check_eq('enable ZXV: ENB byte1',     ena_write[-2], 0x80)
check_eq('enable ZXV: ENB byte0 LSB', ena_write[-1], 0x00)

# --- disable_interrupt on Reset is a no-op (cannot disable) ---
writes_before = len(connection.writes)
ade.disable_interrupt(1 << 20)   # the Reset bit (constant _RESET)
check_eq('disable Reset is no-op', len(connection.writes), writes_before)

# --- configure_cf writes denominator twice ---
writes_before = len(connection.writes)
ade.configure_cf(1, ADE7953CFSource.ACTIVE_A, 0x1234)
# Expect: CFMODE write, then two identical CF1DEN writes -> 3 register writes.
cf_writes = connection.writes[writes_before:]
# The last two writes should be identical CF1DEN writes (denominator only).
check_eq('CF denominator written twice', cf_writes[-1], cf_writes[-2])

# --- phase calibration sign-magnitude ---
ade.set_phase_calibration('a', 1.117e-6)   # +1 LSB advance -> magnitude=1, sign=1
phcal_writes = connection.writes[-1]
check_eq('phase cal advance LSB', phcal_writes[-1], 0x01)
check_eq('phase cal advance MSB', phcal_writes[-2], 0x02)

# --- last_operation should round-trip a write ---
last_op = ade.last_operation()
check_true('last_op readable', last_op is not None)

# --- channel B calibration independence ---
ade.configure_channel_b(20.0)
check_eq('current_gain_b updated', ade._current_gain_b, 20.0)
i_b_expected = 9032007 * 0.353553 * 20 / 9032007
connection.set_register(0x21B, 0x89, 0xD1, 0x47)
check_eq('current_b full-scale', round(ade.current_b(), 4), round(i_b_expected, 4))

# --- power_factor / line_period ---
connection.set_register(0x10A, 0x40, 0x00)   # PFA = 0x4000 = 0.5
check_eq('power_factor +0.5', ade.power_factor(), 0.5)
connection.set_register(0x10E, 0xC3, 0x50)   # Period = 50000 raw
check_eq('line_period 50 Hz', round(ade.line_period(), 4), round((50000 + 1) / 223750.0, 4))

# --- software reset sets SWRST in CONFIG ---
writes_before = len(connection.writes)
ade.reset()
# The first write after writes_before is a read of CONFIG (write_read),
# the second is the write of CONFIG with SWRST (bit 7) set in the LSB.
cfg_write = connection.writes[writes_before + 1]
check_true('reset writes CONFIG', (cfg_write[-1] & 0x80) != 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)