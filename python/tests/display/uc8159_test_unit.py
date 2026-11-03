"""Unit test for the UC8159 driver — mocked display connection, no hardware."""

from periph.chips.display.uc8159 import UC8159Full, UC8159Minimal
from periph.connection.display_base import WireMode
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


def check_raises(label, exc, fn):
    try:
        fn()
    except exc:
        check_true(label, True)
    except Exception as e:  # noqa: BLE001
        check_true('%s (raised %r)' % (label, e), False)
    else:
        check_true('%s (nothing raised)' % label, False)


def frame_bytes(conn, start):
    return b''.join(e[1] for e in conn.events[start:] if e[0] == 'data')


# --- initialisation -------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8159Minimal(conn)
check_true('init: reset then wait BUSY (active low, 60 s)',
           conn.events[0] == ('reset',) and conn.events[1] == ('wait_busy', False, 60000))
check_true('init: vendor command sequence',
           conn.commands == [(0x00, b'\xef\x08'), (0x01, b'\x37\x00\x23\x23'), (0x03, b'\x00'),
                             (0x06, b'\xc7\xc7\x1d'), (0x30, b'\x3c'), (0x41, b'\x00'),
                             (0x50, b'\x37'), (0x60, b'\x22'), (0x61, b'\x02\x58\x01\xc0'),
                             (0xE3, b'\xaa')])
check_true('geometry', d.width == 600 and d.height == 448)
bad = DisplayConnectionMock()
bad.ready = False
check_raises('init timeout raises', TimeoutError, lambda: UC8159Minimal(bad))

# --- framebuffer ----------------------------------------------------------
d = UC8159Full(DisplayConnectionMock())
check_true('white after init', d.get_pixel(0, 0) == 1 and d.get_pixel(599, 447) == 1)
for c in range(8):
    d.set_pixel(7 + c, 3, c)
check_true('eight colours roundtrip', all(d.get_pixel(7 + c, 3) == c for c in range(8)))
check_true('neighbours untouched', d.get_pixel(6, 3) == 1 and d.get_pixel(15, 3) == 1)
d.set_pixel(599, 447, UC8159Minimal.ORANGE)
check_true('last pixel', d.get_pixel(599, 447) == 6 and d.get_pixel(598, 447) == 1)
d.set_pixel(-1, 0, 4)
d.set_pixel(600, 0, 4)
d.set_pixel(0, 448, 4)
check_true('out of range ignored', d.get_pixel(0, 0) == 1 and d.get_pixel(700, 9) == 0)
d.clear(UC8159Minimal.GREEN)
check_true('clear green', d.get_pixel(0, 0) == 2 and d.get_pixel(333, 222) == 2 and d.get_pixel(599, 447) == 2)
d.clear()
check_true('clear default white', d.get_pixel(1, 1) == 1)

# --- show -----------------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8159Minimal(conn)
d.set_pixel(0, 0, UC8159Minimal.BLACK)
d.set_pixel(1, 0, UC8159Minimal.ORANGE)
d.set_pixel(599, 447, UC8159Minimal.RED)
n = len(conn.events)
d.show()
tail = conn.events[n:]
check_true('show starts with DTM1', tail[0] == ('command', 0x10, b''))
data = frame_bytes(conn, n)
check_true('frame is 134400 bytes', len(data) == 134400)
check_true('first byte black/orange', data[0] == 0x06 and data[1] == 0x11)
check_true('last byte white/red', data[-1] == 0x14)
cmds_after = [t[:2] for t in tail if t[0] != 'data']
check_true('tail PON wait DRF wait POF wait',
           cmds_after[-6:] == [('command', 0x04), ('wait_busy', False), ('command', 0x12),
                               ('wait_busy', False), ('command', 0x02), ('wait_busy', False)])

# --- sleep / wake ---------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8159Minimal(conn)
d.sleep()
check_true('sleep: DSLP 0xA5', conn.commands[-1] == (0x07, b'\xa5'))
resets = conn.reset_count
d.show()
check_true('show after sleep resets and re-initialises', conn.reset_count == resets + 1)

# --- timeouts -------------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8159Minimal(conn)
conn.ready = False
check_raises('show timeout raises', TimeoutError, d.show)

# --- settings -------------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8159Full(conn)
conn.events.clear()
d.set_flip(True, False)
check_true('flip horizontal', conn.commands[0] == (0x00, b'\xeb\x08'))
d.set_flip(False, True)
check_true('flip vertical', conn.commands[1] == (0x00, b'\xe7\x08'))
d.set_flip(False, False)
check_true('flip none', conn.commands[2] == (0x00, b'\xef\x08'))
d.set_frame_rate(0x3A)
check_true('frame rate code', conn.commands[3] == (0x30, b'\x3a'))
d.set_interval(7)
check_true('interval default 0x37', conn.commands[4] == (0x50, b'\x37'))
d.set_interval(3, 0, 0)
check_true('interval custom', conn.commands[5] == (0x50, b'\x03'))
d.set_temperature_sensor(True, -3)
check_true('temperature sensor external', conn.commands[6] == (0x41, b'\x8d'))
d.set_vcom_dc(-0.10)
d.set_vcom_dc(-2.0)
d.set_vcom_dc(-9.0)
check_true('vcom_dc codes', [c[1] for c in conn.commands[7:10]] == [b'\x02', b'\x28', b'\x50'])
d.send_command(0x13, b'\x00')
check_true('raw command', conn.commands[-1] == (0x13, b'\x00'))

# --- drawing --------------------------------------------------------------
d.draw_line(0, 0, 9, 9, UC8159Minimal.RED)
check_true('line diagonal', all(d.get_pixel(i, i) == 4 for i in range(10)) and d.get_pixel(5, 4) == 1)
d.clear()
d.draw_rect(1, 1, 5, 4, UC8159Minimal.BLUE)
check_true('rect outline', d.get_pixel(1, 1) == 3 and d.get_pixel(5, 4) == 3 and d.get_pixel(3, 2) == 1)
d.draw_rect(10, 1, 3, 3, UC8159Minimal.YELLOW, True)
check_true('rect filled', d.get_pixel(11, 2) == 5 and d.get_pixel(13, 3) == 1)
d.clear()
d.blit(4, 2, b'\xa0\x40', 4, 2, UC8159Minimal.GREEN)
check_true('blit', d.get_pixel(4, 2) == 2 and d.get_pixel(5, 2) == 1 and d.get_pixel(6, 2) == 2
           and d.get_pixel(5, 3) == 2)

# --- reads ----------------------------------------------------------------
conn = DisplayConnectionMock(WireMode.HALF_DUPLEX_PIN)
d = UC8159Full(conn)
conn.queue_read(b'\x0f')
check_true('read_status', d.read_status() == 0x0F and conn.commands[-1][0] == 0x71)
conn.queue_read(b'\xe7\x00')
check_true('read_temperature signed', d.read_temperature() == -25)
conn.queue_read(b'\x01')
check_true('read_low_power false when normal', d.read_low_power() is False)
conn.queue_read(b'\x0c')
check_true('read_vcom', abs(d.read_vcom() - (-0.60)) < 1e-9)
conn = DisplayConnectionMock(WireMode.SIMPLEX)
d = UC8159Full(conn)
check_raises('reads fail in SIMPLEX', RuntimeError, d.read_status)

print('%d passed, %d failed' % (passed, failed))
import sys  # noqa: E402
sys.exit(1 if failed else 0)
