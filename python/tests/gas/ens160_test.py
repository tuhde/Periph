import sys
import time

from periph.transport.i2c_auto import I2CTransport
from periph.chips.gas.ens160 import ENS160Minimal, ENS160Full

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


transport = I2CTransport(0x52)

sensor = ENS160Minimal(transport)
check_true('init', True)

status = sensor.status()
check_true('status_valid_range', status in (0, 1, 2, 3))

print('Waiting for warm-up (may take up to 3 minutes)...')
for _ in range(180):
    if sensor.status() == 0:
        break
    time.sleep(1)
check_true('warmup_complete', sensor.status() == 0)

data = sensor.read_air_quality()
check_true('read_air_quality_keys', 'aqi' in data and 'tvoc_ppb' in data and 'eco2_ppm' in data)
check_true('aqi_range', 1 <= data['aqi'] <= 5)
check_true('tvoc_non_negative', data['tvoc_ppb'] >= 0)
check_true('eco2_minimum', data['eco2_ppm'] >= 400)

sensor_full = ENS160Full(transport)
check_true('full_init', True)

sensor_full.set_compensation(25.0, 50.0)
check_true('set_compensation', True)

tvoc = sensor_full.read_tvoc()
check_true('read_tvoc', tvoc >= 0)

eco2 = sensor_full.read_eco2()
check_true('read_eco2', eco2 >= 400)

aqi = sensor_full.read_aqi()
check_true('read_aqi', 1 <= aqi <= 5)

actuals = sensor_full.read_compensation_actuals()
check_true('read_compensation_actuals', 'temp_celsius' in actuals and 'rh_percent' in actuals)

fw = sensor_full.get_firmware_version()
check_true('get_firmware_version', len(fw) == 3 and all(isinstance(v, int) for v in fw))

sensor_full.sleep()
check_true('sleep', True)
time.sleep(0.1)
sensor_full.wake()
check_true('wake', True)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
