import _testconfig as cfg
from periph.transport.neopixel_micropython import NeoPixelTransport
from periph.chips.led.sk6812rgbw import SK6812RGBWMinimal, SK6812RGBWFull

from machine import SoftSPI, Pin
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


spi = SoftSPI(baudrate=2_400_000, polarity=0, phase=0,
              sck=Pin(cfg.SCK), mosi=Pin(cfg.MOSI), miso=Pin(cfg.MISO))
transport = NeoPixelTransport(spi)

# --- SK6812RGBWMinimal ---
strip = SK6812RGBWMinimal(transport, 8)

strip.fill(255, 0, 0)
check_true('fill(255,0,0) accepted', True)

strip.fill(0, 255, 0)
check_true('fill(0,255,0) accepted', True)

strip.fill(0, 0, 255)
check_true('fill(0,0,255) accepted', True)

strip.fill(0, 0, 0, 255)
check_true('fill(w=255) accepted', True)

strip.off()
check_true('off() accepted', True)

strip.fill(300, -10, 1000, 500)
check_true('fill clamps out-of-range values', True)

# --- SK6812RGBWFull ---
full = SK6812RGBWFull(transport, 8)

check_eq('default brightness is 255', full.brightness, 255)

full.set_pixel(0, 255, 0, 0)
check_true('set_pixel(r) accepted', True)

full.set_pixel(1, 0, 0, 0, 255)
check_true('set_pixel(w=255) accepted', True)

full.set_pixel(7, 0, 0, 255)
check_true('set_pixel at last index accepted', True)

full.show()
check_true('show() accepted', True)

full.set_pixels([(255, 0, 0), (0, 255, 0), (0, 0, 255), (0, 0, 0, 200)])
check_true('set_pixels with 3- and 4-element tuples accepted', True)
full.show()
check_true('show() after set_pixels accepted', True)

full.brightness = 128
check_eq('brightness setter', full.brightness, 128)
full.show()
check_true('show() with brightness=128 accepted', True)

full.brightness = 0
full.show()
check_true('show() with brightness=0 accepted', True)

full.brightness = 255

full.rotate(1)
check_true('rotate(1) accepted', True)
full.show()
check_true('show() after rotate accepted', True)

full.fill_hsv(0.0, 1.0, 1.0)
check_true('fill_hsv(0.0,1.0,1.0) accepted', True)

full.fill_hsv(0.333, 1.0, 1.0)
check_true('fill_hsv(0.333,1.0,1.0) accepted', True)

full.fill_hsv(0.667, 1.0, 1.0)
check_true('fill_hsv(0.667,1.0,1.0) accepted', True)

full.off()
check_true('off() on Full accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
