import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ads7828 import ADS7828Minimal, ADS7828Full

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


def close(a, b):
    return abs(a - b) < 1e-9


# Internal reference (default): init sends the lone reference-on command 0x08.
connection = I2CConnectionMock()
adc = ADS7828Full(connection)
check_true('init_enables_internal_reference', connection.writes == [bytes([0x08])])
check_true('init_internal_vref', adc.get_reference() == 2.5 and adc.uses_internal_reference())

# read_raw(0): SE, C=0, PD=11 -> 0x8C. Result bytes 0x0A 0xBC -> 0xABC.
connection.set_register(0x8C, 0x0A, 0xBC)
check_true('read_raw_ch0', adc.read_raw(0) == 0xABC)
check_true('read_raw_ch0_command', connection.writes[-1] == bytes([0x8C]))

# Interleaved channel encoding: CH1 -> 0xCC, CH2 -> 0x9C, CH7 -> 0xFC.
connection.set_register(0xCC, 0x0F, 0xFF)
check_true('read_raw_ch1', adc.read_raw(1) == 0xFFF)
check_true('read_raw_ch1_command', connection.writes[-1] == bytes([0xCC]))
adc.read_raw(2)
check_true('read_raw_ch2_command', connection.writes[-1] == bytes([0x9C]))
adc.read_raw(7)
check_true('read_raw_ch7_command', connection.writes[-1] == bytes([0xFC]))

# Upper nibble of byte0 is masked off.
connection.set_register(0x8C, 0xF1, 0x23)
check_true('read_raw_masks_high_nibble', adc.read_raw(0) == 0x123)

# Out-of-range channels clamp to [0, 7].
adc.read_raw(99)
check_true('read_raw_clamps_high', connection.writes[-1] == bytes([0xFC]))
adc.read_raw(-3)
check_true('read_raw_clamps_low', connection.writes[-1] == bytes([0x8C]))

# read_voltage = raw / 4096 * vref.
connection.set_register(0x8C, 0x08, 0x00)
check_true('read_voltage', close(adc.read_voltage(0), 0.5 * 2.5))

# read_all returns eight values, one per channel.
check_true('read_all_length', len(adc.read_all()) == 8)

# Differential: pair 0 normal -> 0x0C; pair 3 inverted -> 0x7C (C = 4|3 = 7).
connection.set_register(0x0C, 0x04, 0x00)
check_true('read_differential_raw', adc.read_differential_raw(0) == 0x400)
check_true('read_differential_command', connection.writes[-1] == bytes([0x0C]))
adc.read_differential_raw(3, True)
check_true('read_differential_inverted_command', connection.writes[-1] == bytes([0x7C]))
check_true('read_differential_voltage', close(adc.read_differential(0), 0x400 / 4096.0 * 2.5))

# Signed differential = normal - inverted.
connection.set_register(0x0C, 0x00, 0x00)
connection.set_register(0x4C, 0x02, 0x00)
check_true('read_signed_differential_negative', close(adc.read_signed_differential(0), -0x200 / 4096.0 * 2.5))
connection.set_register(0x0C, 0x02, 0x00)
connection.set_register(0x4C, 0x00, 0x00)
check_true('read_signed_differential_positive', close(adc.read_signed_differential(0), 0x200 / 4096.0 * 2.5))

# lsb and read_scan.
check_true('lsb', close(adc.lsb(), 2.5 / 4096))
connection.set_register(0x8C, 0x00, 0x01)
connection.set_register(0xCC, 0x00, 0x02)
check_true('read_scan', adc.read_scan([0, 1]) == [1, 2])

# set_power_mode: internal reference rejects PD1 = 0; accepts 2.
try:
    adc.set_power_mode(1)
    check_true('set_power_mode_rejects_pd1_clear', False)
except ValueError:
    check_true('set_power_mode_rejects_pd1_clear', True)
try:
    adc.set_power_mode(7)
    check_true('set_power_mode_rejects_out_of_range', False)
except ValueError:
    check_true('set_power_mode_rejects_out_of_range', True)
adc.set_power_mode(ADS7828Full.REF_ON_ADC_OFF)
adc.read_raw(0)
check_true('set_power_mode_applied', connection.writes[-1] == bytes([0x88]))
adc.set_power_mode(ADS7828Full.REF_ON_ADC_ON)

# power_down: lone 0x00; next read re-enables the reference first.
adc.power_down()
check_true('power_down_command', connection.writes[-1] == bytes([0x00]))
adc.read_raw(0)
check_true('read_after_power_down_reenables_reference', connection.writes[-2] == bytes([0x08]))

# External reference: no init traffic, PD=00, scaling by vref.
connection = I2CConnectionMock()
adc = ADS7828Full(connection, 5.0)
check_true('external_no_init_traffic', connection.writes == [])
check_true('external_reference', adc.get_reference() == 5.0 and not adc.uses_internal_reference())
connection.set_register(0x80, 0x08, 0x00)
check_true('external_read_voltage', close(adc.read_voltage(0), 2.5))
check_true('external_command_pd0', connection.writes[-1] == bytes([0x80]))
adc.set_power_mode(ADS7828Full.POWER_DOWN_BETWEEN)
check_true('external_allows_pd0', True)

# set_reference switches modes at run time.
adc.set_reference(None)
check_true('set_reference_internal_enables', connection.writes[-1] == bytes([0x08]) and adc.get_reference() == 2.5)
adc.set_reference(3.3)
check_true('set_reference_external', adc.get_reference() == 3.3 and not adc.uses_internal_reference())
adc.read_raw(0)
check_true('set_reference_external_pd0', connection.writes[-1] == bytes([0x80]))

# Minimal class stands alone.
m = ADS7828Minimal(I2CConnectionMock())
check_true('minimal_class', m.read_raw(0) == 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
