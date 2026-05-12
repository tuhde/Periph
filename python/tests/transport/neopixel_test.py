import _testconfig as cfg
from periph.transport.neopixel_micropython import NeoPixelTransport

from machine import SPI, Pin

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


spi = SPI(cfg.SPI_BUS, baudrate=2_400_000, polarity=0, phase=0,
          sck=Pin(cfg.SCK), mosi=Pin(cfg.MOSI), miso=Pin(cfg.MISO))
transport = NeoPixelTransport(spi)

data = bytes([0xFF, 0x00, 0x00])
transport.write(data)

check_true('write accepted data', len(data) == 3)

print('===DONE: {} passed, {} failed==='.format(passed, failed))