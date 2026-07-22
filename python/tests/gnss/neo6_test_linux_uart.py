import os
import sys

from periph.transport.uart_linux import UARTTransport
from periph.chips.gnss.neo6 import NEO6Minimal

UART_PORT = os.environ.get('UART_PORT', '/dev/ttyS0')
UART_BAUD = int(os.environ.get('UART_BAUD', '9600'))

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


# Requires a NEO-6 module wired to UART with a clear sky view. Achieving an
# actual fix needs an outdoor antenna and can take up to ~26 s (cold start);
# this test only requires that well-typed values come back, not a fix.
transport = UARTTransport(UART_PORT, baudrate=UART_BAUD, timeout_s=1.0)
gps = NEO6Minimal(transport)

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
