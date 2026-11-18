import os
import sys

from periph.connection.i2c_linux import I2CConnection
from periph.chips.power.max17048 import MAX17048Full
import time

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x36'), 16)

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


connection = I2CConnection(I2C_BUS, I2C_ADDR)
gauge = MAX17048Full(connection)

check_true('voltage in Li-ion range', 2.5 <= gauge.voltage() <= 4.6)
check_true('percent in range', 0.0 <= gauge.percent() <= 100.0)
check_true('version', (gauge.version() & 0xFFF0) == 0x0010)
check_true('raw_voltage matches voltage', abs((gauge.raw_voltage() >> 4) * 1.25e-3 - gauge.voltage()) < 0.05)
check_true('raw_soc readable', 0 <= gauge.raw_soc() <= 0xFFFF)
check_true('charge_rate readable', -1000.0 < gauge.charge_rate() < 1000.0)
check_true('chip_id readable', 0 <= gauge.chip_id() <= 0xFF)

gauge.set_rcomp(0x97)
check_eq('rcomp', gauge.get_rcomp(), 0x97)
gauge.compensate_temperature(20.0)
check_eq('compensate 20 C', gauge.get_rcomp(), 0x97)
gauge.set_alert_soc(10)
check_eq('alert_soc', gauge.get_alert_soc(), 10)
gauge.set_alert_soc(4)

gauge.set_voltage_alert(3.0, 4.2)
lo, hi = gauge.get_voltage_alert()
check_true('voltage_alert', abs(lo - 3.0) < 0.001 and abs(hi - 4.2) < 0.001)
gauge.set_voltage_alert(0, 5.1)

gauge.set_reset_voltage(2.8)
check_true('reset_voltage', abs(gauge.get_reset_voltage() - 2.8) < 0.001)
gauge.set_reset_voltage(3.0)

gauge.set_hibernate_thresholds(26.624, 0.06)
hib, act = gauge.get_hibernate_thresholds()
check_true('hibernate_thresholds', abs(hib - 26.624) < 0.001 and abs(act - 0.06) < 0.001)

gauge.clear_status(0x3F00)
check_eq('status flags cleared', gauge.read_status() & 0x3F00, 0)
check_true('poll_interrupt', gauge.poll_interrupt() == 0)

gauge.write_config(gauge.read_config())
check_true('config readable', (gauge.read_config() >> 8) == 0x97)

gauge.enable_sleep(True)
gauge.sleep()
time.sleep(0.01)
gauge.wake()
gauge.enable_sleep(False)
check_true('wake: voltage readable', gauge.voltage() > 0.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
