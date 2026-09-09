import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.led.ws2812b import WS2812BFull

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


# WS2812B is write-only (no registers, no reads) - the connection mock only
# ever needs to record what bytes were sent, so the same generic
# I2CConnectionMock used by every register-addressed chip works unchanged
# here too; only its .writes list is used.

N = 4
connection = I2CConnectionMock()
sensor = WS2812BFull(connection, N)
check_true('init', True)

# fill(): GRB wire order, all N pixels, transmits immediately.
sensor.fill(0x11, 0x22, 0x33)
check_true('fill_transmits', len(connection.writes) == 1)
check_true('fill_length', len(connection.writes[-1]) == N * 3)
check_true('fill_grb_order', connection.writes[-1][0:3] == bytes([0x22, 0x11, 0x33]))
check_true('fill_all_pixels', connection.writes[-1] == bytes([0x22, 0x11, 0x33]) * N)

# fill() clamps out-of-range channels.
sensor.fill(-10, 300, 128)
check_true('fill_clamps', connection.writes[-1][0:3] == bytes([255, 0, 128]))

# off(): equivalent to fill(0, 0, 0).
sensor.off()
check_true('off', connection.writes[-1] == bytes(N * 3))

# set_pixel(): buffer-only, no transmit.
writes_before = len(connection.writes)
sensor.set_pixel(1, 0xAA, 0xBB, 0xCC)
check_true('set_pixel_no_transmit', len(connection.writes) == writes_before)
sensor.show()
check_true('set_pixel_then_show', connection.writes[-1][3:6] == bytes([0xBB, 0xAA, 0xCC]))
check_true('set_pixel_other_pixels_unchanged', connection.writes[-1][0:3] == bytes(3))

# set_pixel() clamps index to [0, n-1].
sensor.set_pixel(99, 0x01, 0x02, 0x03)
sensor.show()
check_true('set_pixel_clamps_index', connection.writes[-1][(N - 1) * 3:(N - 1) * 3 + 3] == bytes([0x02, 0x01, 0x03]))

# set_pixels(): sequence of (r, g, b), extras beyond n ignored.
sensor.set_pixels([(0x10, 0x20, 0x30), (0x40, 0x50, 0x60), (0x70, 0x80, 0x90), (0xA0, 0xB0, 0xC0), (0xFF, 0xFF, 0xFF)])
sensor.show()
expected = bytes([0x20, 0x10, 0x30, 0x50, 0x40, 0x60, 0x80, 0x70, 0x90, 0xB0, 0xA0, 0xC0])
check_true('set_pixels', connection.writes[-1] == expected)

# brightness scaling at show() time: sent = stored * brightness // 255.
sensor.brightness = 128
sensor.set_pixel(0, 200, 100, 50)
sensor.set_pixel(1, 0, 0, 0)
sensor.set_pixel(2, 0, 0, 0)
sensor.set_pixel(3, 0, 0, 0)
sensor.show()
scaled_r = 200 * 128 // 255
scaled_g = 100 * 128 // 255
scaled_b = 50 * 128 // 255
check_true('brightness_scaling', connection.writes[-1][0:3] == bytes([scaled_g, scaled_r, scaled_b]))
sensor.brightness = 255

# rotate(): shifts pixel buffer left by `steps` whole pixels, no transmit.
sensor.set_pixels([(1, 0, 0), (2, 0, 0), (3, 0, 0), (4, 0, 0)])
sensor.show()
before_rotate = len(connection.writes)
sensor.rotate(1)
check_true('rotate_no_transmit', len(connection.writes) == before_rotate)
sensor.show()
check_true('rotate_shifts_left', connection.writes[-1][1] == 2 and connection.writes[-1][1 + 3 * 3] == 1)

# fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
sensor.fill_hsv(0.0, 1.0, 1.0)
check_true('fill_hsv_red', connection.writes[-1][0:3] == bytes([0, 255, 0]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
