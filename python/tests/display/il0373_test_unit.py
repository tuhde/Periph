"""Unit test for the IL0373 driver — mocked display connection, no hardware."""

from periph.chips.display.il0373 import IL0373Full, IL0373Minimal
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


def cmds(conn):
    return [c for c, _ in conn.commands]


# --- initialisation -------------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Minimal(conn)
check_true('init: reset then wait BUSY (active low)',
           conn.events[0] == ('reset',) and conn.events[1] == ('wait_busy', False, 30000))
check_true('init: PWR, BTST, PON, PSR 0x1F, TRES 104x212, CDI 0x97',
           conn.commands == [(0x01, b'\x03\x00\x2b\x2b\x03'), (0x06, b'\x17\x17\x17'),
                             (0x04, b''), (0x00, b'\x1f'),
                             (0x61, b'\x68\x00\xd4'), (0x50, b'\x97')])
check_true('init: PON settles 200 ms', conn.events[5] == ('wait_busy', False, 200))
check_true('default size', d.width == 104 and d.height == 212)

conn = DisplayConnectionMock()
IL0373Minimal(conn, width=160, height=296)
check_true('init: TRES for the largest panel', (0x61, b'\xa0\x01\x28') in conn.commands)

check_raises('width not a multiple of 8', ValueError,
             lambda: IL0373Minimal(DisplayConnectionMock(), width=100 + 1))
check_raises('width above 160', ValueError,
             lambda: IL0373Minimal(DisplayConnectionMock(), width=168))
check_raises('height above 296', ValueError,
             lambda: IL0373Minimal(DisplayConnectionMock(), height=297))

bad = DisplayConnectionMock()
bad.ready = False
check_raises('init timeout raises', TimeoutError, lambda: IL0373Minimal(bad))

# --- framebuffer mapping --------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Full(conn, width=16, height=2)
check_true('white after init', d.get_pixel(0, 0) == IL0373Minimal.WHITE)
d.set_pixel(0, 0, IL0373Minimal.BLACK)
d.set_pixel(9, 1, IL0373Minimal.BLACK)
check_true('black pixel', d.get_pixel(0, 0) == IL0373Minimal.BLACK)
check_true('byte 0 bit 7 cleared', d._fb[0] == 0x7F)
check_true('bit for x=9 (index 3, bit 6) cleared', d._fb[3] == 0xBF)
d.set_pixel(-1, 0)
d.set_pixel(16, 0)
d.set_pixel(0, 2)
check_true('out of range ignored', d.get_pixel(0, 0) == IL0373Minimal.BLACK)
d.set_pixel(0, 0, IL0373Minimal.WHITE)
check_true('white overwrites', d.get_pixel(0, 0) == IL0373Minimal.WHITE)

# --- show sequence --------------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Minimal(conn, width=16, height=2)
n = len(conn.events)
d.set_pixel(0, 0, IL0373Minimal.BLACK)
d.show()
tail = conn.events[n:]
check_true('show: DTM1 carries OLD = NOT NEW',
           tail[0] == ('command', 0x10, b'') and tail[1] == ('data', b'\x80\x00\x00\x00'))
check_true('show: DTM2 carries the framebuffer',
           tail[2] == ('command', 0x13, b'') and tail[3] == ('data', b'\x7f\xff\xff\xff'))
check_true('show: DRF wait CDI 0xF7 POF wait',
           [t[:2] for t in tail[4:]] == [('command', 0x12), ('wait_busy', False),
                                          ('command', 0x50), ('command', 0x02),
                                          ('wait_busy', False)]
           and tail[6] == ('command', 0x50, b'\xf7'))
n = len(conn.events)
d.show()
tail = conn.events[n:]
check_true('second show powers on first (PON, wait, CDI 0x97)',
           tail[0] == ('command', 0x04, b'') and tail[1][0] == 'wait_busy'
           and tail[2] == ('command', 0x50, b'\x97') and tail[3] == ('command', 0x10, b''))

# --- sleep / wake ---------------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Minimal(conn)
d.sleep()
check_true('sleep: CDI 0xF7, POF, DSLP 0xA5',
           conn.commands[-3:] == [(0x50, b'\xf7'), (0x02, b''), (0x07, b'\xa5')])
resets = conn.reset_count
d.show()
check_true('show after sleep resets and re-initialises', conn.reset_count == resets + 1)
conn = DisplayConnectionMock()
d = IL0373Minimal(conn)
conn.events.clear()
d.sleep()
d.sleep()
check_true('sleep twice powers off once', cmds(conn).count(0x02) == 1)

# --- timeouts -------------------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Minimal(conn, width=16, height=2)
conn.ready = False
check_raises('show timeout raises', TimeoutError, d.show)

# --- settings -------------------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Full(conn, width=16, height=2)
conn.events.clear()
d.set_flip(True, False)
check_true('set_flip clears SHL, keeps UD', conn.commands[0] == (0x00, b'\x1b'))
d.set_power(14.0, 14.0, 20.0)
check_true('set_power layout', conn.commands[1] == (0x01, bytes((0x03, 0x00, 0x26, 0x26, 0x03))))
d.set_power(6.4, 15.0, 17.0)
check_true('set_power gate level and clamps', conn.commands[2] == (0x01, bytes((0x03, 0x03, 0x00, 0x2B, 0x03))))
d.set_vcom_dc(-1.0)
check_true('set_vcom_dc code', conn.commands[3] == (0x82, b'\x12'))
d.set_vcom_dc(-9.0)
check_true('set_vcom_dc clamps', conn.commands[4] == (0x82, b'\x3a'))
d.set_frame_rate(50)
d.set_frame_rate(200)
d.set_frame_rate(30)
check_true('set_frame_rate 50 Hz = M7 N4', conn.commands[5] == (0x30, b'\x3c'))
check_true('set_frame_rate 200 Hz = M7 N1', conn.commands[6] == (0x30, b'\x39'))
check_true('set_frame_rate 30 Hz nearest = 29 Hz (M1 N1)', conn.commands[7] == (0x30, b'\x09'))
d.set_soft_start(0x17, 0x17, 0x17)
d.set_interval(7)
d.set_temperature_sensor(True, -3)
check_true('soft start', conn.commands[8] == (0x06, b'\x17\x17\x17'))
check_true('set_interval default = 0x97', conn.commands[9] == (0x50, b'\x97'))
check_true('set_temperature_sensor external, offset -3', conn.commands[10] == (0x41, b'\x8d'))
d.write_lut(0, bytes(44))
d.write_lut(4, bytes(42))
check_true('write_lut sends the table then PSR.REG',
           cmds(conn)[11:15] == [0x20, 0x00, 0x24, 0x00]
           and len(conn.commands[11][1]) == 44 and conn.commands[12][1] == b'\x3b')
check_raises('write_lut bad table', ValueError, lambda: d.write_lut(5, b''))
d.send_command(0xAB, b'\x01')
check_true('raw command', conn.commands[-1] == (0xAB, b'\x01'))

# --- drawing --------------------------------------------------------------
d.clear(IL0373Minimal.WHITE)
d.draw_line(0, 0, 3, 3)
check_true('line diagonal', all(d.get_pixel(i, i) == IL0373Minimal.BLACK for i in range(4)))
d.draw_rect(4, 0, 4, 2, IL0373Minimal.BLACK, True)
check_true('filled rect', d.get_pixel(4, 0) == IL0373Minimal.BLACK and d.get_pixel(7, 1) == IL0373Minimal.BLACK)
d.clear(IL0373Minimal.WHITE)
d.draw_rect(0, 0, 4, 2)
check_true('outline rect (corners and edges)',
           d.get_pixel(0, 0) == 0 and d.get_pixel(3, 1) == 0 and d.get_pixel(4, 0) == 1)
d.clear(IL0373Minimal.WHITE)
d.blit(8, 0, b'\xa0', 4, 1)
check_true('blit 1010', d.get_pixel(8, 0) == 0 and d.get_pixel(9, 0) == 1
           and d.get_pixel(10, 0) == 0 and d.get_pixel(11, 0) == 1)
d.clear(IL0373Minimal.BLACK)
check_true('clear black', d.get_pixel(5, 1) == IL0373Minimal.BLACK)

# --- partial window -------------------------------------------------------
conn = DisplayConnectionMock()
d = IL0373Full(conn, width=32, height=4)
n = len(conn.events)
d.show_region(9, 1, 6, 2)
tail = conn.events[n:]
check_true('show_region: PTIN, PTL widened to byte columns',
           tail[0] == ('command', 0x91, b'') and
           tail[1] == ('command', 0x90, bytes((8, 15, 0, 1, 0, 2, 0))))
check_true('show_region: DTM1 window rows inverted',
           tail[2] == ('command', 0x10, b'') and tail[3] == ('data', b'\x00') and tail[4] == ('data', b'\x00'))
check_true('show_region: DTM2 window rows',
           tail[5] == ('command', 0x13, b'') and tail[6] == ('data', b'\xff'))
check_true('show_region ends with POF wait and PTOUT',
           tail[-1] == ('command', 0x92, b'') and tail[-2][0] == 'wait_busy')
check_raises('show_region empty window', ValueError, lambda: d.show_region(0, 0, 0, 1))
check_raises('show_region outside panel', ValueError, lambda: d.show_region(40, 0, 4, 4))
check_true('wait_busy delegates with BUSY polarity',
           d.wait_busy(10) is True and conn.events[-1] == ('wait_busy', False, 10))

# --- reads ----------------------------------------------------------------
conn = DisplayConnectionMock(WireMode.HALF_DUPLEX_PIN)
d = IL0373Full(conn, width=16, height=2)
conn.queue_read(b'\x05')
check_true('read_status', d.read_status() == 5 and conn.commands[-1][0] == 0x71)
conn.queue_read(b'\xe7\x00')
check_true('read_temperature signed', d.read_temperature() == -25)
conn.queue_read(b'\x19')
check_true('read_revision', d.read_revision() == 0x19)
conn.queue_read(b'\x0a')
check_true('read_vcom', abs(d.read_vcom() - (-0.60)) < 1e-9)
conn.queue_read(b'\x00')
check_true('read_low_power true when LPD = 0', d.read_low_power() is True)
conn.queue_read(b'\xff\x11\x22')
check_true('read_otp drops the dummy byte', d.read_otp(2) == b'\x11\x22')

conn = DisplayConnectionMock(WireMode.SIMPLEX)
d = IL0373Full(conn, width=16, height=2)
check_raises('reads fail in SIMPLEX', RuntimeError, d.read_status)

print('%d passed, %d failed' % (passed, failed))
import sys  # noqa: E402
sys.exit(1 if failed else 0)
