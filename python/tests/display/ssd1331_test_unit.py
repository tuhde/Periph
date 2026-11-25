"""Unit test for the SSD1331 driver — mocked display connection, no hardware."""

from periph.chips.display.ssd1331 import SSD1331Full, SSD1331Minimal
from periph.connection.display_mock import DisplayConnectionMock

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


def stream(conn, start=0):
    """Command/parameter bytes as a list; data events as ('D', n); resets/delays as tuples."""
    out = []
    for e in conn.events[start:]:
        if e[0] == 'command':
            assert e[2] == b'', 'parameters must be separate commands (D/C# low)'
            out.append(e[1])
        elif e[0] == 'data':
            out.append(('D', len(e[1])))
        elif e[0] == 'delay':
            out.append(('d', e[1]))
        elif e[0] == 'reset':
            out.append('R')
    return out


def data_bytes(conn, start=0):
    return sum(len(e[1]) for e in conn.events[start:] if e[0] == 'data')


# --- initialisation -------------------------------------------------------
conn = DisplayConnectionMock()
d = SSD1331Minimal(conn)
check_true('init sequence (vendor)', stream(conn) == [
    'R', ('d', 100), 0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06,
    0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64, 0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4,
    0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31, 0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E,
    0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, ('d', 100), 0xAF])
check_true('init sends no pixel data', data_bytes(conn) == 0)
check_true('geometry', d.width == 96 and d.height == 64)
check_true('framebuffer 12288 bytes zero', len(d._fb) == 12288 and not any(d._fb))

# --- framebuffer ----------------------------------------------------------
conn = DisplayConnectionMock()
d = SSD1331Full(conn)
d.set_pixel(0, 0, d.RED)
check_true('pixel RGB565 high byte first', d._fb[0] == 0xF8 and d._fb[1] == 0x00)
d.set_pixel(95, 63, d.BLUE)
check_true('last pixel position', d._fb[12286] == 0x00 and d._fb[12287] == 0x1F)
d.set_pixel(1, 2, 0x1234)
check_true('index and readback', d._fb[(2 * 96 + 1) * 2] == 0x12 and d.get_pixel(1, 2) == 0x1234)
d.set_pixel(-1, 0)
d.set_pixel(0, -1)
d.set_pixel(96, 0)
d.set_pixel(0, 64)
check_true('out of range ignored', d._fb[0] == 0xF8 and d.get_pixel(99, 99) == 0)
d.clear(d.GREEN)
check_true('clear fills', d.get_pixel(0, 0) == d.GREEN and d.get_pixel(95, 63) == d.GREEN)
d.clear(d.WHITE)
check_true('clear same-byte colour', d.get_pixel(50, 30) == d.WHITE and len(d._fb) == 12288)
check_true('rgb packing', SSD1331Full.rgb(255, 0, 0) == 0xF800 and SSD1331Full.rgb(0, 255, 0) == 0x07E0
           and SSD1331Full.rgb(0, 0, 255) == 0x001F and SSD1331Full.rgb(255, 255, 255) == 0xFFFF)
d.clear()
d.draw_line(0, 0, 4, 4, d.WHITE)
check_true('line diagonal', d.get_pixel(0, 0) == d.WHITE and d.get_pixel(2, 2) == d.WHITE
           and d.get_pixel(4, 4) == d.WHITE and d.get_pixel(4, 0) == 0)
d.clear()
d.draw_rect(2, 2, 4, 3, d.WHITE)
check_true('rect outline', d.get_pixel(2, 2) == d.WHITE and d.get_pixel(5, 4) == d.WHITE
           and d.get_pixel(3, 3) == 0 and d.get_pixel(6, 2) == 0)
d.draw_rect(10, 10, 3, 3, d.RED, True)
check_true('rect filled', d.get_pixel(11, 11) == d.RED and d.get_pixel(13, 13) == 0)
d.blit(20, 20, [1, 2, 3, 4], 2, 2)
check_true('blit', [d.get_pixel(20, 20), d.get_pixel(21, 20), d.get_pixel(20, 21), d.get_pixel(21, 21)] == [1, 2, 3, 4])

# --- show / show_region ---------------------------------------------------
conn = DisplayConnectionMock()
d = SSD1331Full(conn)
d.clear(d.RED)
n = len(conn.events)
d.show()
s = stream(conn, n)
check_true('show window then rows', s[:6] == [0x15, 0x00, 0x5F, 0x75, 0x00, 0x3F] and s[6] == ('D', 192)
           and len(s) == 6 + 64)
check_true('show sends 12288 bytes', data_bytes(conn, n) == 12288)
check_true('show first data byte', conn.events[n + 6][1][0] == 0xF8)
n = len(conn.events)
d.show_region(10, 20, 4, 2)
check_true('show_region window and rows', stream(conn, n) == [0x15, 10, 13, 0x75, 20, 21, ('D', 8), ('D', 8)])
n = len(conn.events)
d.show_region(90, 60, 20, 20)
check_true('show_region clipped', stream(conn, n)[:6] == [0x15, 90, 95, 0x75, 60, 63] and data_bytes(conn, n) == 48)
n = len(conn.events)
d.show_region(200, 0, 5, 5)
check_true('show_region off panel no-op', len(conn.events) == n)

# --- hardware primitives and settings --------------------------------------
conn = DisplayConnectionMock()
d = SSD1331Full(conn)


def run(label, fn, expect):
    n = len(conn.events)
    fn()
    check_true(label, stream(conn, n) == expect)


run('hw_line red', lambda: d.hw_line(1, 2, 3, 4, d.RED), [0x21, 1, 2, 3, 4, 0x3E, 0, 0])
run('hw_rect filled', lambda: d.hw_rect(10, 20, 5, 6, d.GREEN, d.BLUE, True),
    [0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E])
run('hw_rect fill off', lambda: d.hw_rect(10, 20, 5, 6, d.WHITE), [0x26, 0, 0x22, 10, 20, 14, 25,
                                                                  0x3E, 0x3F, 0x3E, 0, 0, 0])
run('hw_rect zero size no-op', lambda: d.hw_rect(10, 20, 0, 6, d.WHITE), [])
run('hw_clear', lambda: d.hw_clear(0, 1, 2, 3), [0x25, 0, 1, 2, 3])
run('hw_dim', lambda: d.hw_dim(0, 1, 2, 3), [0x24, 0, 1, 2, 3])
run('hw_copy', lambda: d.hw_copy(1, 2, 3, 4, 5, 6), [0x23, 1, 2, 3, 4, 5, 6])
run('scroll interval masked and started', lambda: d.scroll(1, 0, 64, 2, 7), [0x27, 1, 0, 64, 2, 3, 0x2F])
run('scroll_stop', d.scroll_stop, [0x2E])
run('contrast', lambda: d.set_contrast(1, 2, 3), [0x81, 1, 0x82, 2, 0x83, 3])
run('master current 16', lambda: d.set_master_current(16), [0x87, 0x0F])
run('master current clamped low', lambda: d.set_master_current(0), [0x87, 0x00])
run('mode inverse', lambda: d.set_display_mode(d.MODE_INVERSE), [0xA7])
run('flip none keeps other bits', lambda: d.set_flip(False, False), [0xA0, 0x60])
run('flip both', lambda: d.set_flip(True, True), [0xA0, 0x72])
run('power save on', lambda: d.set_power_save(True), [0xB0, 0x1A])
run('power save off', lambda: d.set_power_save(False), [0xB0, 0x0B])
run('dim mode precharge masked', lambda: d.dim_mode(1, 2, 3, 0xFF), [0xAB, 0, 1, 2, 3, 0x1F, 0xAC])
run('display off/on', lambda: (d.display_off(), d.display_on()), [0xAE, 0xAF])
run('gray linear', d.gray_linear, [0xB9])
run('raw command params as commands', lambda: d.send_command(0xAA, b'\x12\x34'), [0xAA, 0x12, 0x34])

print('%d passed, %d failed' % (passed, failed))
raise SystemExit(1 if failed else 0)
