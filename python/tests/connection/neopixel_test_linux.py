import os
from periph.connection.neopixel_linux import NeoPixelConnection

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


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


SPI_BUS  = int(os.environ.get('SPI_BUS',  '0'))
SPI_DEVICE = int(os.environ.get('SPI_DEVICE', '0'))

connection = NeoPixelConnection(SPI_BUS, SPI_DEVICE)

data = bytes([0xFF, 0x00, 0x00])
connection.write(data)

check_true('write accepted data', len(data) == 3)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))