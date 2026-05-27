import os
import time
from periph.transport.i2c_linux import I2CTransport
from periph.chips.gas.ens160 import ENS160Minimal, ENS160Full

passed = 0
failed = 0

def check(condition, name):
    global passed, failed
    if condition:
        print('PASS {}'.format(name))
        passed += 1
    else:
        print('FAIL {}'.format(name))
        failed += 1

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x52'), 16)

transport = I2CTransport(I2C_BUS, I2C_ADDR)

sensor = ENS160Minimal(transport)
check(True, 'init')

status = sensor.status()
check(status in (0, 1, 2, 3), 'status_valid_range')

print('Waiting for warm-up (may take up to 3 minutes)...')
timeout = 180
start = time.monotonic()
while sensor.status() != 0:
    if time.monotonic() - start > timeout:
        print('FAIL warmup_timeout')
        failed += 1
        break
    time.sleep(1.0)
else:
    check(True, 'warmup_complete')

data = sensor.read_air_quality()
check('aqi' in data and 'tvoc_ppb' in data and 'eco2_ppm' in data, 'read_air_quality_keys')
check(1 <= data['aqi'] <= 5, 'aqi_range')
check(data['tvoc_ppb'] >= 0, 'tvoc_non_negative')
check(data['eco2_ppm'] >= 400, 'eco2_minimum')

sensor_full = ENS160Full(transport)
check(True, 'full_init')

sensor_full.set_compensation(25.0, 50.0)
check(True, 'set_compensation')

tvoc = sensor_full.read_tvoc()
check(tvoc >= 0, 'read_tvoc')

eco2 = sensor_full.read_eco2()
check(eco2 >= 400, 'read_eco2')

aqi = sensor_full.read_aqi()
check(1 <= aqi <= 5, 'read_aqi')

actuals = sensor_full.read_compensation_actuals()
check('temp_celsius' in actuals and 'rh_percent' in actuals, 'read_compensation_actuals')

fw = sensor_full.get_firmware_version()
check(len(fw) == 3 and all(isinstance(v, int) for v in fw), 'get_firmware_version')

sensor_full.sleep()
check(True, 'sleep')
time.sleep(0.1)
sensor_full.wake()
check(True, 'wake')

transport.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
