import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.led.apa102 import APA102Full

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

# APA102 is write-only (no registers, no reads) and uses raw synchronous SPI.
# The SPIConnectionMock records every write() call; only its .writes list is used.

N = 4
connection = SPIConnectionMock()
sensor = APA102Full(connection, N)
check_true('init', True)

# Expected frame structure:
# start_frame = bytes(4)                    # 0x00 × 4
# pixel data: N × 4 bytes [0xE0|brightness, B, G, R]
# end_frame = bytes([0xFF] * max(4, (N + 15) // 16))
end_bytes = max(4, (N + 15) // 16)
FRAME_LEN = 4 + N * 4 + end_bytes

# fill(): BGR wire order with brightness byte, all N pixels, transmits immediately.
sensor.fill(0x11, 0x22, 0x33)
check_true('fill_transmits', len(connection.writes) == 1)
check_true('fill_length', len(connection.writes[-1]) == FRAME_LEN)

# Check start frame (4 zero bytes)
start_frame = bytes(4)
check_true('fill_start_frame', connection.writes[-1][0:4] == start_frame)

# Check pixel data: [0xE0|31, B, G, R] = [0xFF, 0x33, 0x22, 0x11] per pixel
expected_pixel = bytes([0xFF, 0x33, 0x22, 0x11])
pixel_data = connection.writes[-1][4:4 + N * 4]
check_true('fill_pixel_order', pixel_data == expected_pixel * N)

# Check end frame (all 0xFF)
end_frame = bytes([0xFF] * end_bytes)
check_true('fill_end_frame', connection.writes[-1][-end_bytes:] == end_frame)

# fill() clamps out-of-range channels.
sensor.fill(-10, 300, 128)
pixel_data = connection.writes[-1][4:4 + N * 4]
expected_clamped = bytes([0xFF, 255, 0, 128])  # [brightness|31, B=255(clamped), G=0(clamped), R=128]
check_true('fill_clamps', pixel_data == expected_clamped * N)

# off(): equivalent to fill(0, 0, 0).
sensor.off()
pixel_data = connection.writes[-1][4:4 + N * 4]
expected_off = bytes([0xFF, 0, 0, 0])  # brightness=31, B=0, G=0, R=0
check_true('off', pixel_data == expected_off * N)

# set_pixel(): buffer-only, no transmit.
writes_before = len(connection.writes)
sensor.set_pixel(1, 0xAA, 0xBB, 0xCC)
check_true('set_pixel_no_transmit', len(connection.writes) == writes_before)
sensor.show()
# Pixel 1 should be [0xE0|31, B=0xCC, G=0xBB, R=0xAA]
pixel_data = connection.writes[-1][4:4 + N * 4]
check_true('set_pixel_then_show', pixel_data[4:8] == bytes([0xFF, 0xCC, 0xBB, 0xAA]))
check_true('set_pixel_other_pixels_zero', pixel_data[0:4] == bytes([0xFF, 0, 0, 0]))

# set_pixel() clamps index to [0, n-1].
sensor.set_pixel(99, 0x01, 0x02, 0x03)
sensor.show()
pixel_data = connection.writes[-1][4:4 + N * 4]
check_true('set_pixel_clamps_index', pixel_data[(N - 1) * 4:(N - 1) * 4 + 4] == bytes([0xFF, 0x03, 0x02, 0x01]))

# set_pixel() with custom pixel_brightness.
sensor.set_pixel(0, 0x10, 0x20, 0x30, 16)  # brightness=16
sensor.show()
pixel_data = connection.writes[-1][4:4 + N * 4]
check_true('set_pixel_hardware_brightness', pixel_data[0:4] == bytes([0xE0 | 16, 0x30, 0x20, 0x10]))

# set_pixels(): sequence of (r, g, b) or (r, g, b, brightness), extras beyond n ignored.
sensor.set_pixels([(0x10, 0x20, 0x30), (0x40, 0x50, 0x60), (0x70, 0x80, 0x90), (0xA0, 0xB0, 0xC0), (0xFF, 0xFF, 0xFF)])
sensor.show()
pixel_data = connection.writes[-1][4:4 + N * 4]
expected = bytes([
    0xFF, 0x30, 0x20, 0x10,  # pixel 0: brightness=31, B=0x30, G=0x20, R=0x10
    0xFF, 0x60, 0x50, 0x40,  # pixel 1
    0xFF, 0x90, 0x80, 0x70,  # pixel 2
    0xFF, 0xC0, 0xB0, 0xA0,  # pixel 3
])
check_true('set_pixels', pixel_data == expected)

# set_pixels() with per-pixel brightness.
sensor.set_pixels([(0x10, 0x20, 0x30, 31), (0x40, 0x50, 0x60, 16), (0x70, 0x80, 0x90, 8), (0xA0, 0xB0, 0xC0, 4)])
sensor.show()
pixel_data = connection.writes[-1][4:4 + N * 4]
expected = bytes([
    0xFF, 0x30, 0x20, 0x10,  # pixel 0: brightness=31
    0xF0, 0x60, 0x50, 0x40,  # pixel 1: brightness=16 -> 0xE0|16 = 0xF0
    0xE8, 0x90, 0x80, 0x70,  # pixel 2: brightness=8  -> 0xE0|8  = 0xE8
    0xE4, 0xC0, 0xB0, 0xA0,  # pixel 3: brightness=4  -> 0xE0|4  = 0xE4
])
check_true('set_pixels_hardware_brightness', pixel_data == expected)

# brightness scaling at show() time: sent = stored * brightness // 255.
# Hardware brightness byte is NOT scaled.
sensor.brightness = 128
sensor.set_pixel(0, 200, 100, 50)  # stored: [0xFF, 50, 100, 200]
sensor.set_pixel(1, 0, 0, 0)
sensor.set_pixel(2, 0, 0, 0)
sensor.set_pixel(3, 0, 0, 0)
sensor.show()
pixel_data = connection.writes[-1][4:4 + N * 4]
scaled_r = 200 * 128 // 255
scaled_g = 100 * 128 // 255
scaled_b = 50 * 128 // 255
check_true('brightness_scaling', pixel_data[0:4] == bytes([0xFF, scaled_b, scaled_g, scaled_r]))
# Other pixels unchanged (still zero)
check_true('brightness_other_pixels_zero', pixel_data[4:8] == bytes([0xFF, 0, 0, 0]))
sensor.brightness = 255

# rotate(): shifts pixel buffer left by `steps` whole pixels, no transmit.
sensor.set_pixels([(1, 0, 0), (2, 0, 0), (3, 0, 0), (4, 0, 0)])
sensor.show()
before_rotate = len(connection.writes)
sensor.rotate(1)
check_true('rotate_no_transmit', len(connection.writes) == before_rotate)
sensor.show()
pixel_data = connection.writes[-1][4:4 + N * 4]
# After rotate(1): pixel 0 gets old pixel 1 (R=2), pixel 3 gets old pixel 0 (R=1)
check_true('rotate_shifts_left', pixel_data[3] == 2 and pixel_data[(N - 1) * 4 + 3] == 1)

# fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
sensor.fill_hsv(0.0, 1.0, 1.0)
pixel_data = connection.writes[-1][4:4 + N * 4]
# Red=255, Green=0, Blue=0 -> wire: [0xFF, 0, 0, 255]
check_true('fill_hsv_red', pixel_data[0:4] == bytes([0xFF, 0, 0, 255]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)