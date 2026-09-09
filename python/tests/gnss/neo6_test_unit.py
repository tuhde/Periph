import struct
import sys

from periph.connection.neo6_mock import NEO6ConnectionMock
from periph.chips.gnss.neo6 import NEO6Minimal, NEO6Full

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


def close_enough(a, b, eps=0.001):
    return abs(a - b) < eps


def nmea(body):
    """Build a $<body>*XX\\r\\n NMEA sentence with a correct XOR checksum."""
    checksum = 0
    for b in body.encode('ascii'):
        checksum ^= b
    return ('$' + body + '*{:02X}\r\n'.format(checksum)).encode('ascii')


def ubx_frame(msg_class, msg_id, payload=b''):
    """Build a UBX frame with a correct Fletcher checksum, mirroring
    NEO6Full.send_ubx's own framing (verified independently here, not by
    reusing the driver's private _ubx_checksum)."""
    length = len(payload)
    body = bytes([msg_class, msg_id, length & 0xFF, (length >> 8) & 0xFF]) + bytes(payload)
    ck_a = ck_b = 0
    for byte in body:
        ck_a = (ck_a + byte) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return bytes([0xB5, 0x62]) + body + bytes([ck_a, ck_b])


def feed(sensor, connection, data):
    """Queue data and drive update() enough times to consume it all,
    returning True if any call returned True (a GGA fix was parsed)."""
    connection.queue_bytes(data)
    got_fix = False
    for _ in range(len(data)):
        if sensor.update():
            got_fix = True
    return got_fix


# Field lists are built explicitly and joined with ',' rather than
# hand-typed as comma-heavy literals, to avoid miscounting empty fields.
GGA_FIX = ','.join([
    'GPGGA', '092750.000', '5321.6802', 'N', '00630.3372', 'W',
    '1', '08', '1.03', '61.7', 'M', '55.2', 'M', '', '',
])
GGA_NO_FIX = ','.join([
    'GPGGA', '092750.000', '', '', '', '',
    '0', '00', '', '', '', '', '', '', '',
])
RMC = ','.join([
    'GPRMC', '092750.000', 'A', '5321.6802', 'N', '00630.3372', 'W',
    '022.4', '084.4', '230394', '003.1', 'W', 'A',
])
VTG = ','.join([
    'GPVTG', '084.4', 'T', '077.4', 'M', '022.4', 'N', '041.5', 'K', 'A',
])

assert len(GGA_FIX.split(',')) == 15
assert len(GGA_NO_FIX.split(',')) == 15
assert len(RMC.split(',')) == 13

# --- NEO6Minimal: GGA decode across all three bus types ---

for bus_type in ('uart', 'i2c', 'spi'):
    connection = NEO6ConnectionMock()
    gps = NEO6Minimal(connection, bus_type=bus_type)
    check_true('init_fix_zero[{}]'.format(bus_type), gps.fix() == 0)
    check_true('init_latitude_none[{}]'.format(bus_type), gps.latitude() is None)

    got_fix = feed(gps, connection, nmea(GGA_FIX))
    check_true('gga_fix_returned_true[{}]'.format(bus_type), got_fix)
    check_true('gga_fix_value[{}]'.format(bus_type), gps.fix() == 1)
    check_true('gga_satellites[{}]'.format(bus_type), gps.satellites() == 8)
    check_true('gga_latitude[{}]'.format(bus_type), close_enough(gps.latitude(), 53.361336667, 1e-6))
    check_true('gga_longitude[{}]'.format(bus_type), close_enough(gps.longitude(), -6.505620, 1e-6))
    check_true('gga_altitude[{}]'.format(bus_type), close_enough(gps.altitude(), 61.7))

# --- NEO6Minimal: no-fix GGA updates fix/satellites but not lat/lon ---

connection = NEO6ConnectionMock()
gps = NEO6Minimal(connection)
feed(gps, connection, nmea(GGA_FIX))
got_fix = feed(gps, connection, nmea(GGA_NO_FIX))
check_true('gga_no_fix_returns_false', not got_fix)
check_true('gga_no_fix_clears_fix_value', gps.fix() == 0)
check_true('gga_no_fix_keeps_last_latitude', close_enough(gps.latitude(), 53.361336667, 1e-6))

# --- Checksum validation: a corrupted sentence is silently discarded ---

connection = NEO6ConnectionMock()
gps = NEO6Minimal(connection)
bad = bytearray(nmea(GGA_FIX))
bad[-4] ^= 0xFF  # corrupt one checksum hex digit
got_fix = feed(gps, connection, bytes(bad))
check_true('bad_checksum_discarded', not got_fix)
check_true('bad_checksum_leaves_fix_zero', gps.fix() == 0)

# --- Non-GGA / non-sentence bytes before '$' are ignored ---

connection = NEO6ConnectionMock()
gps = NEO6Minimal(connection)
got_fix = feed(gps, connection, b'\xff\xff\xff' + nmea(GGA_FIX))
check_true('leading_garbage_ignored', got_fix)

# --- NEO6Full: RMC (speed/course/time/date) and VTG (course/speed) ---

connection = NEO6ConnectionMock()
gps = NEO6Full(connection)
feed(gps, connection, nmea(RMC))
check_true('rmc_speed', close_enough(gps.speed(), 22.4 * 0.514444, 1e-4))
check_true('rmc_course', close_enough(gps.course(), 84.4))
check_true('rmc_utc_time', gps.utc_time() == '092750.000')
check_true('rmc_utc_date', gps.utc_date() == '230394')

connection2 = NEO6ConnectionMock()
gps2 = NEO6Full(connection2)
feed(gps2, connection2, nmea(VTG))
check_true('vtg_course', close_enough(gps2.course(), 84.4))
check_true('vtg_speed', close_enough(gps2.speed(), 41.5 / 3.6, 1e-4))

connection3 = NEO6ConnectionMock()
gps3 = NEO6Full(connection3)
feed(gps3, connection3, nmea(GGA_FIX))
check_true('gga_hdop', close_enough(gps3.hdop(), 1.03))

# --- NEO6Full: UBX send_ubx / poll_ubx / set_rate / set_platform /
# cold_start / save_config ---

connection = NEO6ConnectionMock()
gps = NEO6Full(connection)

gps.send_ubx(0x06, 0x08, bytes([1, 2, 3]))
check_true('send_ubx_frames_correctly',
           connection.writes[-1] == ubx_frame(0x06, 0x08, bytes([1, 2, 3])))

gps.set_rate(5)
meas_rate_ms = int(1000 / 5)
expected_payload = struct.pack('<HHH', meas_rate_ms, 1, 0)
check_true('set_rate_sends_cfg_rate',
           connection.writes[-1] == ubx_frame(0x06, 0x08, expected_payload))

gps.set_platform(4)
expected_payload = bytearray(36)
struct.pack_into('<H', expected_payload, 0, 0x0001)
expected_payload[2] = 4
check_true('set_platform_sends_cfg_nav5',
           connection.writes[-1] == ubx_frame(0x06, 0x24, bytes(expected_payload)))

gps.cold_start()
expected_payload = struct.pack('<HBB', 0xFFFF, 0x02, 0x00)
check_true('cold_start_sends_cfg_rst',
           connection.writes[-1] == ubx_frame(0x06, 0x04, expected_payload))

gps.save_config()
expected_payload = struct.pack('<III', 0x00000000, 0xFFFFFFFF, 0x00000000) + bytes([0x07])
check_true('save_config_sends_cfg_cfg',
           connection.writes[-1] == ubx_frame(0x06, 0x09, expected_payload))

# poll_ubx(): queue a matching UBX response frame, expect its payload back.
connection = NEO6ConnectionMock()
gps = NEO6Full(connection)
connection.queue_bytes(ubx_frame(0x01, 0x02, bytes(range(28))))
payload = gps.poll_ubx(0x01, 0x02)
check_true('poll_ubx_returns_payload', payload == bytes(range(28)))
check_true('poll_ubx_sends_poll_frame', connection.writes[0] == ubx_frame(0x01, 0x02))

# poll_ubx() raises OSError on ACK-NAK.
connection = NEO6ConnectionMock()
gps = NEO6Full(connection)
connection.queue_bytes(ubx_frame(0x05, 0x00, bytes([0x06, 0x08])))  # ACK-NAK for CFG-RATE
try:
    gps.poll_ubx(0x06, 0x08)
    check_true('poll_ubx_nak_raises', False)
except OSError:
    check_true('poll_ubx_nak_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
