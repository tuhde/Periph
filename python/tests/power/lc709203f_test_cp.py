import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.power.lc709203f import LC709203FFull

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
gauge = LC709203FFull(connection)

check_true('voltage in Li-ion range', 2.5 <= gauge.voltage() <= 4.6)
check_true('percent in range', 0.0 <= gauge.percent() <= 100.0)
check_true('temperature near 25 C', 0.0 < gauge.temperature() < 60.0)
check_true('rsoc in range', 0 <= gauge.rsoc() <= 100)
check_true('ic_version readable', gauge.ic_version() >= 0)
check_true('profile_id readable', gauge.profile_id() > 0)
check_eq('get_profile', gauge.get_profile(), 1)

gauge.set_temperature(30.0)
check_true('set_temperature', abs(gauge.temperature() - 30.0) < 0.2)

gauge.set_apa(0x19)
check_eq('apa', gauge.get_apa(), 0x19)
gauge.set_apt(0x1E)
check_eq('apt', gauge.get_apt(), 0x1E)
gauge.set_current_direction(LC709203FFull.DIRECTION_AUTO)
check_eq('direction', gauge.get_current_direction(), 0)

gauge.set_alarm_rsoc(20)
check_eq('alarm_rsoc', gauge.get_alarm_rsoc(), 20)
gauge.set_alarm_voltage(3.5)
check_true('alarm_voltage', abs(gauge.get_alarm_voltage() - 3.5) < 0.001)
check_true('poll_interrupt', gauge.poll_interrupt() in (0, 1, 2, 3))

gauge.init_rsoc()
check_true('rsoc after init_rsoc', 0 <= gauge.rsoc() <= 100)

gauge.sleep()
time.sleep(0.001)
check_true('is_sleeping', gauge.is_sleeping())
gauge.wake()
check_true('wake: not sleeping', not gauge.is_sleeping())
check_true('wake: voltage readable', gauge.voltage() > 0.0)

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
