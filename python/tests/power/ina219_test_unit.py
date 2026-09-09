import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.ina219 import INA219Full

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


def approx(a, b, eps=1e-9):
    return abs(a - b) < eps


connection = I2CConnectionMock()

# r_shunt=0.1, max_current=2.0 -> current_lsb=2.0/32768, cal=int(0.04096/(current_lsb*r_shunt)) & 0xFFFE = 0x1A36.
sensor = INA219Full(connection, r_shunt=0.1, max_current=2.0)
check_true('init_writes_calibration', connection.writes[-1] == bytes([0x05, 0x1A, 0x36]))

# Bus Voltage: raw=(1000<<3)|0b010 = 0x1F42 -> voltage=4.0V, CNVR=1, OVF=0.
connection.set_register(INA219Full._REG_BUS, 0x1F, 0x42)
check_true('voltage', approx(sensor.voltage(), 4.0))
check_true('conversion_ready_true', sensor.conversion_ready() is True)
check_true('overflow_false', sensor.overflow() is False)

# Bus Voltage: raw=(1000<<3)|0b001 = 0x1F41 -> CNVR=0, OVF=1.
connection.set_register(INA219Full._REG_BUS, 0x1F, 0x41)
check_true('overflow_true', sensor.overflow() is True)

# Shunt Voltage: raw=-500 (0xFE0C) -> -0.005 V.
connection.set_register(INA219Full._REG_SHUNT, 0xFE, 0x0C)
check_true('shunt_voltage', approx(sensor.shunt_voltage(), -0.005))

# Current: raw=1000 (0x03E8) -> 1000 * current_lsb.
connection.set_register(INA219Full._REG_CURRENT, 0x03, 0xE8)
check_true('current', approx(sensor.current(), 1000 * (2.0 / 32768)))

# Power: raw=2000 (0x07D0) -> 2000 * 20 * current_lsb.
connection.set_register(INA219Full._REG_POWER, 0x07, 0xD0)
check_true('power', approx(sensor.power(), 2000 * 20 * (2.0 / 32768)))

# configure(brng=0, pga=1, badc=0x0B, sadc=0x02, mode=5) -> config = 0x0D95;
# re-writes Calibration afterward.
sensor.configure(brng=0, pga=1, badc=0x0B, sadc=0x02, mode=5)
check_true('configure_writes_config', connection.writes[-2] == bytes([0x00, 0x0D, 0x95]))
check_true('configure_rewrites_cal', connection.writes[-1] == bytes([0x05, 0x1A, 0x36]))

# shutdown(): MODE forced to 0, other CONFIG bits preserved (0x0D95 -> 0x0D90).
sensor.shutdown()
check_true('shutdown', connection.writes[-1] == bytes([0x00, 0x0D, 0x90]))

# wake(): restores the previously configured mode (5) -> 0x0D95.
sensor.wake()
check_true('wake', connection.writes[-1] == bytes([0x00, 0x0D, 0x95]))

# trigger(): re-writes the current config unchanged.
sensor.trigger()
check_true('trigger', connection.writes[-1] == bytes([0x00, 0x0D, 0x95]))

# reset(): sets RST, re-writes Calibration. (This driver does not restore the
# last configure()'d Configuration afterward — see Implementation Notes.)
sensor.reset()
check_true('reset_writes_rst', connection.writes[-2] == bytes([0x00, 0x80, 0x00]))
check_true('reset_rewrites_cal', connection.writes[-1] == bytes([0x05, 0x1A, 0x36]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
