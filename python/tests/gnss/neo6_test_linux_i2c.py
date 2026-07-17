import os
import sys

from periph.transport.i2c_linux import I2CTransport
from periph.chips.gnss.neo6 import NEO6Minimal

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x42'), 16)

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


# Requires a NEO-6 module wired to I2C (DDC) with a clear sky view. Achieving
# an actual fix needs an outdoor antenna and can take up to ~26 s (cold
# start); this test only requires that well-typed values come back.
transport = I2CTransport(I2C_BUS, I2C_ADDR)
gps = NEO6Minimal(transport, bus_type='i2c')

check_true('fix() starts at 0', gps.fix() == 0)
check_true('latitude() starts at None', gps.latitude() is None)

for _ in range(3000):
    gps.update()

check_true('fix() is a valid quality code', gps.fix() in (0, 1, 2))
check_true('satellites() is a non-negative int', gps.satellites() >= 0)
if gps.fix() > 0:
    check_true('latitude() in range once fixed', -90.0 <= gps.latitude() <= 90.0)
    check_true('longitude() in range once fixed', -180.0 <= gps.longitude() <= 180.0)
    check_true('altitude() is populated once fixed', gps.altitude() is not None)
else:
    print('note: no fix acquired during the test window (needs sky view)')

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
