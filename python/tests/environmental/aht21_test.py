import sys
import time

from periph.transport.i2c_auto import I2CTransport
from periph.chips.environmental.aht21 import AHT21Full

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


transport = I2CTransport(0x38)
aht = AHT21Full(transport)

check_true('is_calibrated', aht.is_calibrated())
check_true('not busy at idle', not aht.is_busy())

r = aht.read()
check_true('temperature range', r['temperature_c'] >= -40.0 and r['temperature_c'] <= 120.0)
check_true('humidity range', r['humidity_pct'] >= 0.0 and r['humidity_pct'] <= 100.0)

t = aht.read_temperature()
check_true('read_temperature range', t >= -40.0 and t <= 120.0)

h = aht.read_humidity()
check_true('read_humidity range', h >= 0.0 and h <= 100.0)

rc = aht.read_with_crc()
check_true('crc_ok', rc['crc_ok'])
check_true('crc temperature range', rc['temperature_c'] >= -40.0 and rc['temperature_c'] <= 120.0)
check_true('crc humidity range', rc['humidity_pct'] >= 0.0 and rc['humidity_pct'] <= 100.0)

aht.soft_reset()
time.sleep(0.05)
check_true('calibrated after reset', aht.is_calibrated())

r2 = aht.read()
check_true('read after reset: temperature range', r2['temperature_c'] >= -40.0 and r2['temperature_c'] <= 120.0)
check_true('read after reset: humidity range', r2['humidity_pct'] >= 0.0 and r2['humidity_pct'] <= 100.0)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
