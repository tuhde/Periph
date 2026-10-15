"""Unit test for the UC8276C driver — mocked display connection, no hardware."""

from periph.chips.display.uc8276c import UC8276CFull, UC8276CMinimal
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
d = UC8276CMinimal(conn)
check_true('init: reset then wait BUSY_N (active low)',
           conn.events[0] == ('reset',) and conn.events[1] == ('wait_busy', False, 30000))
check_true('init: PSR 0x0F then CDI 0xD7, no TRES at 400x300',
           conn.commands == [(0x00, b'\x0f'), (0x50, b'\xd7')])

conn = DisplayConnectionMock()
UC8276CMinimal(conn, width=200, height=100)
check_true('init: TRES for a smaller panel',
           (0x61, b'\x00\xc8\x00\x64') in conn.commands)

check_raises('width not a multiple of 8', ValueError,
             lambda: UC8276CMinimal(DisplayConnectionMock(), width=100 + 1))
check_raises('width above 400', ValueError,
             lambda: UC8276CMinimal(DisplayConnectionMock(), width=408))
check_raises('height above 300', ValueError,
             lambda: UC8276CMinimal(DisplayConnectionMock(), height=301))

bad = DisplayConnectionMock()
bad.ready = False
check_raises('init timeout raises', TimeoutError, lambda: UC8276CMinimal(bad))

# --- framebuffer mapping --------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CFull(conn, width=16, height=2)
check_true('white after init', d.get_pixel(0, 0) == UC8276CMinimal.WHITE)
d.set_pixel(0, 0, UC8276CMinimal.BLACK)
d.set_pixel(9, 1, UC8276CMinimal.RED)
check_true('black pixel', d.get_pixel(0, 0) == UC8276CMinimal.BLACK)
check_true('red pixel', d.get_pixel(9, 1) == UC8276CMinimal.RED)
check_true('bw byte 0 bit 7 cleared', d._bw[0] == 0x7F)
check_true('red plane bit for x=9 (index 3, bit 6)', d._red[3] == 0x40 and d._bw[3] == 0xBF)
d.set_pixel(-1, 0)
d.set_pixel(16, 0)
d.set_pixel(0, 2)
check_true('out of range ignored', d.get_pixel(0, 0) == UC8276CMinimal.BLACK)
d.set_pixel(0, 0, UC8276CMinimal.WHITE)
check_true('white overwrites', d.get_pixel(0, 0) == UC8276CMinimal.WHITE)

# --- show sequence --------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CMinimal(conn, width=16, height=2)
n = len(conn.events)
d.set_pixel(0, 0, UC8276CMinimal.RED)
d.show()
tail = conn.events[n:]
check_true('show: DTM1 + 4 bw bytes',
           tail[0] == ('command', 0x10, b'') and tail[1] == ('data', b'\x7f\xff\xff\xff'))
check_true('show: DTM2 + red plane',
           tail[2] == ('command', 0x13, b'') and tail[3] == ('data', b'\x80\x00\x00\x00'))
check_true('show: PON wait DRF wait POF wait',
           [t[:2] for t in tail[4:]] == [('command', 0x04), ('wait_busy', False),
                                          ('command', 0x12), ('wait_busy', False),
                                          ('command', 0x02), ('wait_busy', False)])

conn = DisplayConnectionMock()
d = UC8276CMinimal(conn, width=16, height=2)
n = len(conn.events)
d.show()
check_true('show without red sends zeros', conn.events[n + 3] == ('data', b'\x00\x00\x00\x00'))

# --- sleep / wake ---------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CMinimal(conn)
d.sleep()
check_true('sleep: DSLP 0xA5', conn.commands[-1] == (0x07, b'\xa5'))
resets = conn.reset_count
conn.ready = True
d.show()
check_true('show after sleep resets and re-initialises', conn.reset_count == resets + 1)

# --- timeouts -------------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CMinimal(conn, width=16, height=2)
conn.ready = False
check_raises('show timeout raises', TimeoutError, d.show)

# --- settings -------------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CFull(conn, width=16, height=2)
conn.events.clear()
d.set_mode(UC8276CMinimal.MODE_KW)
d.set_flip(True, False)
check_true('set_mode sets PSR.KW/R', conn.commands[0] == (0x00, b'\x1f'))
check_true('set_flip clears SHL, keeps UD', conn.commands[1] == (0x00, b'\x1b'))
d.set_power(10.0, 10.0, 10.0, 16.0)
check_true('set_power layout', conn.commands[2] == (0x01, bytes((0x03, 0x00, 0x26, 0x26, 0x26))))
d.set_power(2.4, 11.0, 5.0, 13.0)
check_true('set_power gate level and clamps', conn.commands[3] == (0x01, bytes((0x03, 0x03, 0x00, 0x2B, 0x0D))))
d.set_vcom_dc(-1.0)
check_true('set_vcom_dc code', conn.commands[4] == (0x82, b'\x12'))
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
check_true('set_interval default = 0xD7', conn.commands[9] == (0x50, b'\xd7'))
check_true('set_temperature_sensor external, offset -3', conn.commands[10] == (0x41, b'\x8d'))
d.write_lut(bytes(56), None, bytes(56), None, None)
check_true('write_lut sends given tables then PSR.REG',
           cmds(conn)[11:14] == [0x20, 0x22, 0x00] and conn.commands[13][1] == b'\x3b')
d.send_command(0xAB, b'\x01')
check_true('raw command', conn.commands[-1] == (0xAB, b'\x01'))

# --- drawing --------------------------------------------------------------
d.clear(UC8276CMinimal.WHITE)
d.draw_line(0, 0, 3, 3)
check_true('line diagonal', all(d.get_pixel(i, i) == UC8276CMinimal.BLACK for i in range(4)))
d.draw_rect(4, 0, 4, 2, UC8276CMinimal.RED, True)
check_true('filled rect', d.get_pixel(4, 0) == UC8276CMinimal.RED and d.get_pixel(7, 1) == UC8276CMinimal.RED)
d.clear(UC8276CMinimal.WHITE)
d.draw_rect(0, 0, 4, 2)
check_true('outline rect (corners and edges)',
           d.get_pixel(0, 0) == 0 and d.get_pixel(3, 1) == 0 and d.get_pixel(4, 0) == 1)
d.clear(UC8276CMinimal.WHITE)
d.blit(8, 0, b'\xa0', 4, 1)
check_true('blit 1010', d.get_pixel(8, 0) == 0 and d.get_pixel(9, 0) == 1
           and d.get_pixel(10, 0) == 0 and d.get_pixel(11, 0) == 1)
d.clear(UC8276CMinimal.BLACK)
check_true('clear black', d.get_pixel(5, 1) == UC8276CMinimal.BLACK)
d.clear(UC8276CMinimal.RED)
check_true('clear red', d.get_pixel(5, 1) == UC8276CMinimal.RED)

# --- partial window -------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CFull(conn, width=32, height=4)
n = len(conn.events)
d.show_region(9, 1, 6, 2)
tail = conn.events[n:]
check_true('show_region: PTIN, PTL widened to byte columns',
           tail[0] == ('command', 0x91, b'') and
           tail[1] == ('command', 0x90, bytes((0, 8, 0, 15, 0, 1, 0, 2, 0))))
check_true('show_region: DTM1 window rows',
           tail[2] == ('command', 0x10, b'') and tail[3] == ('data', b'\xff') and tail[4] == ('data', b'\xff'))
check_true('show_region ends with POF wait and PTOUT',
           tail[-1] == ('command', 0x92, b'') and tail[-2][0] == 'wait_busy')
check_raises('show_region empty window', ValueError, lambda: d.show_region(0, 0, 0, 1))
check_raises('show_region outside panel', ValueError, lambda: d.show_region(40, 0, 4, 4))

# --- auto -----------------------------------------------------------------
conn = DisplayConnectionMock()
d = UC8276CFull(conn, width=16, height=2)
d.refresh_auto()
check_true('refresh_auto 0xA5', conn.commands[-1] == (0x17, b'\xa5'))
d.refresh_auto(deep_sleep=True)
check_true('refresh_auto deep sleep 0xA7', conn.commands[-1] == (0x17, b'\xa7'))
check_true('wait_busy delegates with BUSY_N polarity', d.wait_busy(10) is True and conn.events[-1] == ('wait_busy', False, 10))

# --- reads ----------------------------------------------------------------
conn = DisplayConnectionMock(WireMode.HALF_DUPLEX_PIN)
d = UC8276CFull(conn, width=16, height=2)
conn.queue_read(b'\x05')
check_true('read_status', d.read_status() == 5 and conn.commands[-1][0] == 0x71)
conn.queue_read(b'\xe7\x00')
check_true('read_temperature signed', d.read_temperature() == -25)
conn.queue_read(b'\x19')
check_true('read_revision 1-byte form', d.read_revision() == (None, 0x19))
conn.queue_read(b'\x00\x02\x01\x02\x03')
check_true('read_revision 5-byte form', d.read_revision(5) == (2, 0x030201))
conn.queue_read(b'\x0a')
check_true('read_vcom', abs(d.read_vcom() - (-0.60)) < 1e-9)
conn.queue_read(b'\x00')
check_true('read_low_power true when LPD = 0', d.read_low_power() is True)
conn.queue_read(b'\xff\x11\x22')
check_true('read_otp drops the dummy byte', d.read_otp(2) == b'\x11\x22')

conn = DisplayConnectionMock(WireMode.SIMPLEX)
d = UC8276CFull(conn, width=16, height=2)
check_raises('reads fail in SIMPLEX', RuntimeError, d.read_status)

print('%d passed, %d failed' % (passed, failed))
import sys  # noqa: E402
sys.exit(1 if failed else 0)
