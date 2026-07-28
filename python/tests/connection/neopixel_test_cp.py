import time
import _testconfig as cfg
from periph.connection.neopixel_circuitpython import NeoPixelConnection

import busio

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


spi = busio.SPI(cfg.SCK, MOSI=cfg.MOSI, MISO=cfg.MISO)
connection = NeoPixelConnection(spi)

data = bytes([0xFF, 0x00, 0x00])
connection.write(data)

check_true('write accepted data', len(data) == 3)

print('===DONE: {} passed, {} failed==='.format(passed, failed))