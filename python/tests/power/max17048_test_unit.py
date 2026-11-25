import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power import max17048 as mod
from periph.chips.power.max17048 import MAX17048Minimal, MAX17048Full

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


def near(a, b, tol=1e-9):
    return abs(a - b) < tol


mod.time.sleep = lambda s: None

connection = I2CConnectionMock()
gauge = MAX17048Full(connection)
check_true('init_no_bus_access', len(connection.writes) == 0)

# VCELL: 0xC350 >> 4 = 0xC35 = 3125 * 1.25 mV = 3.90625 V
connection.set_register(0x02, 0xC3, 0x50)
check_true('voltage', near(gauge.voltage(), 3.90625))
check_true('raw_voltage', gauge.raw_voltage() == 0xC350)
two_cell = MAX17048Minimal(connection, cells=2)
check_true('voltage_two_cells', near(two_cell.voltage(), 7.8125))

# SOC: 0x4B80 = 75.5 %
connection.set_register(0x04, 0x4B, 0x80)
check_true('percent', near(gauge.percent(), 75.5))
check_true('raw_soc', gauge.raw_soc() == 0x4B80)
connection.set_register(0x04, 0xFF, 0xFF)
check_true('percent_clamped', gauge.percent() == 100.0)

# CRATE signed: 0xFFEC = -20 -> -4.16 %/h; 0x0064 = 100 -> 20.8 %/h
connection.set_register(0x16, 0xFF, 0xEC)
check_true('charge_rate_negative', near(gauge.charge_rate(), -4.16))
connection.set_register(0x16, 0x00, 0x64)
check_true('charge_rate', near(gauge.charge_rate(), 20.8))

connection.set_register(0x08, 0x00, 0x12)
check_true('version', gauge.version() == 0x0012)
connection.set_register(0x18, 0x96, 0xAB)
check_true('chip_id', gauge.chip_id() == 0xAB)

# RCOMP via CONFIG (default 0x971C)
connection.set_register(0x0C, 0x97, 0x1C)
check_true('get_rcomp', gauge.get_rcomp() == 0x97)
connection.writes.clear()
gauge.set_rcomp(0x80)
check_true('set_rcomp_rmw', connection.writes[-1] == bytes([0x0C, 0x80, 0x1C]))
connection.set_register(0x0C, 0x97, 0x1C)

# compensate_temperature: 35 C -> 0x97 - 7.5 = 143.5 -> 144 (0x90); 5 C -> 151 + 75 = 226 (0xE2)
gauge.compensate_temperature(35.0)
check_true('compensate_hot', connection.writes[-1] == bytes([0x0C, 0x90, 0x1C]))
gauge.compensate_temperature(5.0)
check_true('compensate_cold', connection.writes[-1] == bytes([0x0C, 0xE2, 0x1C]))
connection.set_register(0x0C, 0x97, 0x1C)

# alert SOC: 10 % -> ATHD = 22 = 0x16
gauge.set_alert_soc(10)
check_true('set_alert_soc', connection.writes[-1] == bytes([0x0C, 0x97, 0x16]))
check_true('get_alert_soc', gauge.get_alert_soc() == 10)
gauge.set_soc_change_alert(True)
check_true('soc_change_on', connection.writes[-1] == bytes([0x0C, 0x97, 0x56]))
gauge.set_soc_change_alert(False)
check_true('soc_change_off', connection.writes[-1] == bytes([0x0C, 0x97, 0x16]))

# voltage alert: 3.3 V -> 165 = 0xA5, 4.25 V -> 212.5 -> 212 or 213 (round half even => 212)
gauge.set_voltage_alert(3.3, 4.2)
check_true('set_voltage_alert', connection.writes[-1] == bytes([0x14, 0xA5, 0xD2]))
lo, hi = gauge.get_voltage_alert()
check_true('get_voltage_alert', near(lo, 3.3) and near(hi, 4.2))
gauge.set_voltage_alert(0, 5.1)
check_true('voltage_alert_disabled', connection.writes[-1] == bytes([0x14, 0x00, 0xFF]))
try:
    gauge.set_voltage_alert(4.0, 3.0)
    check_true('voltage_alert_invalid', False)
except ValueError:
    check_true('voltage_alert_invalid', True)

# reset voltage: 3.0 V -> 75 << 9 = 0x9600; keeps DIS and ID bits
connection.set_register(0x18, 0x01, 0x5A)
gauge.set_reset_voltage(3.0)
check_true('set_reset_voltage', connection.writes[-1] == bytes([0x18, 0x97, 0x5A]))
check_true('get_reset_voltage', near(gauge.get_reset_voltage(), 3.0))
gauge.set_comparator_disabled(False)
check_true('comparator_enabled', connection.writes[-1] == bytes([0x18, 0x96, 0x5A]))
gauge.set_comparator_disabled(True)
check_true('comparator_disabled', connection.writes[-1] == bytes([0x18, 0x97, 0x5A]))

# STATUS ENVR
connection.set_register(0x1A, 0x01, 0x00)
gauge.enable_voltage_reset_alert(True)
check_true('envr_on', connection.writes[-1] == bytes([0x1A, 0x41, 0x00]))
gauge.enable_voltage_reset_alert(False)
check_true('envr_off', connection.writes[-1] == bytes([0x1A, 0x01, 0x00]))

# hibernate thresholds: 26.624 %/h -> 128 = 0x80, 0.06 V -> 48 = 0x30
gauge.set_hibernate_thresholds(26.624, 0.06)
check_true('set_hibernate', connection.writes[-1] == bytes([0x0A, 0x80, 0x30]))
hib, act = gauge.get_hibernate_thresholds()
check_true('get_hibernate', near(hib, 26.624) and near(act, 0.06))
gauge.disable_hibernate()
check_true('disable_hibernate', connection.writes[-1] == bytes([0x0A, 0x00, 0x00]))
gauge.force_hibernate()
check_true('force_hibernate', connection.writes[-1] == bytes([0x0A, 0xFF, 0xFF]))

# MODE: HibStat, EnSleep, QuickStart
connection.set_register(0x06, 0x10, 0x00)
check_true('is_hibernating_true', gauge.is_hibernating() is True)
connection.set_register(0x06, 0x00, 0x00)
check_true('is_hibernating_false', gauge.is_hibernating() is False)
gauge.enable_sleep(True)
check_true('enable_sleep', connection.writes[-1] == bytes([0x06, 0x20, 0x00]))
gauge.quick_start()
check_true('quick_start_keeps_ensleep', connection.writes[-1] == bytes([0x06, 0x60, 0x00]))
gauge.enable_sleep(False)
check_true('disable_sleep', connection.writes[-1] == bytes([0x06, 0x00, 0x00]))
gauge.quick_start()
check_true('quick_start', connection.writes[-1] == bytes([0x06, 0x40, 0x00]))

# sleep / wake
connection.set_register(0x0C, 0x97, 0x1C)
gauge.sleep()
check_true('sleep', connection.writes[-1] == bytes([0x0C, 0x97, 0x9C]))
gauge.wake()
check_true('wake', connection.writes[-1] == bytes([0x0C, 0x97, 0x1C]))

# reset tolerates a NACK
connection.fail_next_writes(1)
gauge.reset()
check_true('reset', connection.writes[-1] == bytes([0xFE, 0x54, 0x00]))

# raw CONFIG
connection.set_register(0x0C, 0x97, 0x1C)
check_true('read_config', gauge.read_config() == 0x971C)
gauge.write_config(0x9700)
check_true('write_config', connection.writes[-1] == bytes([0x0C, 0x97, 0x00]))

# STATUS handling: RI | HD set, ALRT set
connection.set_register(0x1A, 0x11, 0x00)
check_true('read_status', gauge.read_status() == 0x1100)
connection.set_register(0x0C, 0x97, 0x3C)
connection.writes.clear()
gauge.clear_status(0x0100)
w = [x for x in connection.writes if len(x) == 3]
check_true('clear_status_status', w[0] == bytes([0x1A, 0x10, 0x00]))
check_true('clear_status_alrt', w[1] == bytes([0x0C, 0x97, 0x1C]))

# poll_interrupt: reports and clears flags
connection.set_register(0x1A, 0x51, 0x00)  # ENVR | HD | RI
connection.set_register(0x0C, 0x97, 0x3C)
connection.writes.clear()
check_true('poll_interrupt_flags', gauge.poll_interrupt() == 0x1100)
w = [x for x in connection.writes if len(x) == 3]
check_true('poll_interrupt_clears_status', w[0] == bytes([0x1A, 0x40, 0x00]))
check_true('poll_interrupt_clears_alrt', w[1] == bytes([0x0C, 0x97, 0x1C]))
connection.set_register(0x1A, 0x40, 0x00)
connection.writes.clear()
check_true('poll_interrupt_none', gauge.poll_interrupt() == 0 and not [x for x in connection.writes if len(x) == 3])

# enable/disable interrupt sources
connection.set_register(0x0C, 0x97, 0x1C)
gauge.enable_interrupt(MAX17048Full.SOURCE_SOC_CHANGE)
check_true('enable_soc_change', connection.writes[-1] == bytes([0x0C, 0x97, 0x5C]))
gauge.disable_interrupt(MAX17048Full.SOURCE_SOC_CHANGE)
check_true('disable_soc_change', connection.writes[-1] == bytes([0x0C, 0x97, 0x1C]))
connection.set_register(0x14, 0xA5, 0xD2)
gauge.disable_interrupt(MAX17048Full.SOURCE_UNDERVOLTAGE)
check_true('disable_undervoltage', connection.writes[-1] == bytes([0x14, 0x00, 0xD2]))
gauge.disable_interrupt(MAX17048Full.SOURCE_OVERVOLTAGE)
check_true('disable_overvoltage', connection.writes[-1] == bytes([0x14, 0x00, 0xFF]))

# on_interrupt via polling callback
received = []
gauge._callback = received.append
connection.set_register(0x1A, 0x10, 0x00)
gauge._int_handler()
check_true('int_handler_callback', received == [0x1000])
gauge.off_interrupt()
check_true('off_interrupt', gauge._callback is None)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
