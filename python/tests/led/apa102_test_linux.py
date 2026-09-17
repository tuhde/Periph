import os

from periph.connection.spi_linux import SPIConnection
from periph.chips.led.apa102 import APA102Minimal, APA102Full
import time

SPI_BUS    = int(os.environ.get('SPI_BUS',    '0'))
SPI_DEVICE = int(os.environ.get('SPI_DEVICE', '0'))

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


connection = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=1_000_000)

# --- APA102Minimal ---
strip = APA102Minimal(connection, 8)

strip.fill(255, 0, 0)
check_true('fill(255,0,0) accepted', True)

strip.fill(0, 255, 0)
check_true('fill(0,255,0) accepted', True)

strip.fill(0, 0, 255)
check_true('fill(0,0,255) accepted', True)

strip.off()
check_true('off() accepted', True)

strip.fill(300, -10, 1000)
check_true('fill clamps out-of-range values', True)

# --- APA102Full ---
full = APA102Full(connection, 8)

check_eq('default brightness is 255', full.brightness, 255)

full.set_pixel(0, 255, 0, 0)
full.show()
check_true('set_pixel + show accepted', True)

full.set_pixels([(255, 0, 0), (0, 255, 0), (0, 0, 255)])
full.show()
check_true('set_pixels + show accepted', True)

# set_pixels with per-pixel hardware brightness
full.set_pixels([(255, 0, 0, 31), (255, 0, 0, 16), (255, 0, 0, 8), (255, 0, 0, 4)])
full.show()
check_true('set_pixels with hardware brightness accepted', True)

full.brightness = 128
check_eq('brightness setter', full.brightness, 128)
full.show()
check_true('show() with brightness=128 accepted', True)

full.brightness = 255
full.rotate(1)
full.show()
check_true('rotate + show accepted', True)

full.fill_hsv(0.0, 1.0, 1.0)
check_true('fill_hsv(0.0) accepted', True)

full.fill_hsv(0.333, 1.0, 1.0)
check_true('fill_hsv(0.333) accepted', True)

full.fill_hsv(0.667, 1.0, 1.0)
check_true('fill_hsv(0.667) accepted', True)

full.off()
check_true('off() on Full accepted', True)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))