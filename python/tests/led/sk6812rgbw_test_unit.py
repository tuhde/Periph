import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.led.sk6812rgbw import SK6812RGBWFull

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


# SK6812RGBW is write-only, same reasoning as WS2812B (see
# ws2812b_test_unit.py) - the generic I2CConnectionMock works unchanged;
# only its .writes list is used. This chip additionally appends 24
# zero-bytes (~80us reset) after the pixel buffer on every transmit.

N = 3
RESET_BYTES = bytes(24)

connection = I2CConnectionMock()
sensor = SK6812RGBWFull(connection, N)
check_true('init', True)

# fill(): GRBW wire order, all N pixels, transmits immediately, with the
# 24-byte extended reset tail appended.
sensor.fill(0x11, 0x22, 0x33, 0x44)
check_true('fill_transmits', len(connection.writes) == 1)
check_true('fill_length', len(connection.writes[-1]) == N * 4 + 24)
check_true('fill_grbw_order', connection.writes[-1][0:4] == bytes([0x22, 0x11, 0x33, 0x44]))
check_true('fill_all_pixels', connection.writes[-1][:N * 4] == bytes([0x22, 0x11, 0x33, 0x44]) * N)
check_true('fill_reset_tail', connection.writes[-1][N * 4:] == RESET_BYTES)

# fill() white channel defaults to 0.
sensor.fill(0x10, 0x20, 0x30)
check_true('fill_white_defaults_zero', connection.writes[-1][0:4] == bytes([0x20, 0x10, 0x30, 0x00]))

# fill() clamps out-of-range channels.
sensor.fill(-10, 300, 128, 999)
check_true('fill_clamps', connection.writes[-1][0:4] == bytes([255, 0, 128, 255]))

# off(): equivalent to fill(0, 0, 0, 0).
sensor.off()
check_true('off', connection.writes[-1][:N * 4] == bytes(N * 4))
check_true('off_reset_tail', connection.writes[-1][N * 4:] == RESET_BYTES)

# set_pixel(): buffer-only, no transmit; white channel defaults to 0.
writes_before = len(connection.writes)
sensor.set_pixel(1, 0xAA, 0xBB, 0xCC, 0xDD)
check_true('set_pixel_no_transmit', len(connection.writes) == writes_before)
sensor.show()
check_true('set_pixel_then_show', connection.writes[-1][4:8] == bytes([0xBB, 0xAA, 0xCC, 0xDD]))
check_true('set_pixel_other_pixels_unchanged', connection.writes[-1][0:4] == bytes(4))

sensor.set_pixel(0, 0x01, 0x02, 0x03)
sensor.show()
check_true('set_pixel_white_defaults_zero', connection.writes[-1][0:4] == bytes([0x02, 0x01, 0x03, 0x00]))

# set_pixel() clamps index to [0, n-1].
sensor.set_pixel(99, 0x05, 0x06, 0x07, 0x08)
sensor.show()
check_true('set_pixel_clamps_index', connection.writes[-1][(N - 1) * 4:(N - 1) * 4 + 4] == bytes([0x06, 0x05, 0x07, 0x08]))

# set_pixels(): (r,g,b) or (r,g,b,w) tuples, extras beyond n ignored, white defaults to 0.
sensor.set_pixels([(0x10, 0x20, 0x30), (0x40, 0x50, 0x60, 0x70), (0x80, 0x90, 0xA0, 0xB0), (0xFF, 0xFF, 0xFF, 0xFF)])
sensor.show()
expected = bytes([
    0x20, 0x10, 0x30, 0x00,
    0x50, 0x40, 0x60, 0x70,
    0x90, 0x80, 0xA0, 0xB0,
])
check_true('set_pixels', connection.writes[-1][:N * 4] == expected)

# brightness scaling at show() time: sent = stored * brightness // 255.
sensor.brightness = 128
sensor.set_pixel(0, 200, 100, 50, 40)
sensor.set_pixel(1, 0, 0, 0, 0)
sensor.set_pixel(2, 0, 0, 0, 0)
sensor.show()
scaled = tuple(v * 128 // 255 for v in (100, 200, 50, 40))  # stored as G,R,B,W
check_true('brightness_scaling', connection.writes[-1][0:4] == bytes(scaled))
sensor.brightness = 255

# rotate(): shifts pixel buffer left by `steps` whole (4-byte) pixels, no transmit.
sensor.set_pixels([(1, 0, 0, 0), (2, 0, 0, 0), (3, 0, 0, 0)])
sensor.show()
before_rotate = len(connection.writes)
sensor.rotate(1)
check_true('rotate_no_transmit', len(connection.writes) == before_rotate)
sensor.show()
check_true('rotate_shifts_left', connection.writes[-1][1] == 2 and connection.writes[-1][1 + 2 * 4] == 1)

# fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0), white=0.
sensor.fill_hsv(0.0, 1.0, 1.0)
check_true('fill_hsv_red', connection.writes[-1][0:4] == bytes([0, 255, 0, 0]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
