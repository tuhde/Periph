import sys
import time

from periph.transport.i2c_auto import I2CTransport
from periph.chips.gas.ens160 import ENS160Full

try:
    import os
    _ADDR = int(os.environ.get('I2C_ADDR', '0x52'), 16)
except AttributeError:
    _ADDR = 0x52

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


transport = I2CTransport(_ADDR)

# Use ENS160Full throughout — it inherits all ENS160Minimal methods.
# Creating a second driver instance on the same transport would re-run
# __init__ (IDLE → STANDARD), resetting the warm-up timer.
sensor = ENS160Full(transport)
check_true('init', True)

status = sensor.status()
check_true('status_valid_range', status in (0, 1, 2, 3))

print('Waiting for warm-up (may take up to 3 minutes)...')
# Poll validity at NEWDAT time — the same moment read_air_quality() checks it —
# so the warmup condition matches what read_air_quality() will actually see.
warmup_ok = False
for _ in range(240):
    try:
        status_byte = sensor._wait_for_new_data(timeout_ms=2000)
        if (status_byte >> 2) & 0x03 == 0:
            warmup_ok = True
            break
    except Exception:
        pass
check_true('warmup_complete', warmup_ok)

if warmup_ok:
    data = sensor.read_air_quality()
    check_true('read_air_quality_keys', 'aqi' in data and 'tvoc_ppb' in data and 'eco2_ppm' in data)
    check_true('aqi_range', 1 <= data['aqi'] <= 5)
    check_true('tvoc_non_negative', data['tvoc_ppb'] >= 0)
    check_true('eco2_minimum', data['eco2_ppm'] >= 400)

sensor.set_compensation(25.0, 50.0)
check_true('set_compensation', True)

tvoc = sensor.read_tvoc()
check_true('read_tvoc', tvoc >= 0)

eco2 = sensor.read_eco2()
check_true('read_eco2', eco2 >= 400)

aqi = sensor.read_aqi()
check_true('read_aqi', 1 <= aqi <= 5)

actuals = sensor.read_compensation_actuals()
check_true('read_compensation_actuals', 'temp_celsius' in actuals and 'rh_percent' in actuals)

fw = sensor.get_firmware_version()
check_true('get_firmware_version', len(fw) == 3 and all(isinstance(v, int) for v in fw))

sensor.sleep()
check_true('sleep', True)
time.sleep(0.1)
sensor.wake()
check_true('wake', True)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
