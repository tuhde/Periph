import _testconfig as cfg
from periph.transport.neopixel_circuitpython import NeoPixelTransport
from periph.chips.led.ws2812b import WS2812BMinimal, WS2812BFull

import busio
import time

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
transport = NeoPixelTransport(spi)

# --- WS2812BMinimal ---
strip = WS2812BMinimal(transport, 8)

strip.fill(255, 0, 0)
check_true('fill(255,0,0) accepted', True)

strip.off()
check_true('off() accepted', True)

# --- WS2812BFull ---
full = WS2812BFull(transport, 8)

check_eq('default brightness is 255', full.brightness, 255)

full.set_pixel(0, 255, 0, 0)
full.show()
check_true('set_pixel + show accepted', True)

full.set_pixels([(255, 0, 0), (0, 255, 0), (0, 0, 255)])
full.show()
check_true('set_pixels + show accepted', True)

full.brightness = 64
full.show()
check_true('show() with brightness=64 accepted', True)
full.brightness = 255

full.rotate(1)
full.show()
check_true('rotate + show accepted', True)

full.fill_hsv(0.0, 1.0, 1.0)
check_true('fill_hsv accepted', True)

full.off()
check_true('off() accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
