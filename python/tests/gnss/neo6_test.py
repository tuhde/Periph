import _testconfig as cfg
from machine import UART, Pin
from periph.transport.uart_micropython import UARTTransport
from periph.chips.gnss.neo6 import NEO6Minimal

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
uart = UART(cfg.UART_ID, baudrate=9600, tx=Pin(cfg.UART_TX), rx=Pin(cfg.UART_RX), timeout=1000)
transport = UARTTransport(uart, baudrate=9600)
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

print('===DONE: {} passed, {} failed==='.format(passed, failed))
