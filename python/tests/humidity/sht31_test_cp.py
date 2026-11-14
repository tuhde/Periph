import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.humidity.sht31 import SHT31Full

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
sensor = SHT31Full(connection)

t, rh = sensor.read()
check_true('temperature range', -40.0 < t < 125.0)
check_true('humidity range', 0.0 <= rh <= 100.0)
check_true('read_temperature range', -40.0 < sensor.read_temperature() < 125.0)
check_true('read_humidity range', 0.0 <= sensor.read_humidity() <= 100.0)
raw_t, raw_rh = sensor.read_raw()
check_true('read_raw range', 0 <= raw_t <= 65535 and 0 <= raw_rh <= 65535)

for level in ('low', 'medium', 'high'):
    sensor.set_repeatability(level)
    t, rh = sensor.read()
    check_true('repeatability ' + level, -40.0 < t < 125.0 and 0.0 <= rh <= 100.0)

status = sensor.read_status()
check_true('status readable', 0 <= status <= 0xFFFF)
sensor.clear_status()
check_true('status bit 4 cleared', not (sensor.read_status() & 0x0010))

sensor.start_periodic(2, 'high')
time.sleep(1.2)
res = sensor.fetch()
check_true('periodic fetch', res is not None and -40.0 < res[0] < 125.0)
sensor.stop_periodic()

sensor.set_heater(True)
check_true('heater bit set', bool(sensor.read_status() & 0x2000))
sensor.set_heater(False)
check_true('heater bit cleared', not (sensor.read_status() & 0x2000))

dp = sensor.dew_point()
check_true('dew point plausible', -60.0 < dp < 60.0)
sensor.soft_reset()
check_true('read after soft reset', 0.0 <= sensor.read()[1] <= 100.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
