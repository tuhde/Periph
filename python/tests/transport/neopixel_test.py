import _testconfig as cfg
from machine import SoftSPI, Pin
from periph.transport.neopixel_micropython import NeoPixelTransport

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


spi = SoftSPI(baudrate=2_400_000, polarity=0, phase=0,
              sck=Pin(cfg.SCK), mosi=Pin(cfg.MOSI), miso=Pin(cfg.MISO))
transport = NeoPixelTransport(spi)

transport.write(bytes([0x00, 0x00, 0x00]))
check_true('write_no_error', True)

transport.write(bytes([0xFF, 0xFF, 0xFF]))
check_true('write_bright_no_error', True)

transport.write(bytes([0x00, 0xFF, 0x00]))
check_true('write_green_no_error', True)

transport.write(bytes([0x10, 0x20, 0x30, 0x40]))
check_true('write_4bytes_no_error', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))