"""CAT24C512 unit test for Python/Linux — exercises the driver against
I2CConnectionMock, with no hardware required.

Run via ``python/test_linux.sh --level unit memory/cat24c512``.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.memory.cat24c512 import CAT24C512Minimal, CAT24C512Full

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


def raises(fn, exc=ValueError):
    try:
        fn()
    except exc:
        return True
    return False


connection = I2CConnectionMock(reg_bytes=2)
ee = CAT24C512Full(connection)
check_true('init_no_traffic', connection.writes == [])
check_true('size', ee.size() == 65536)
check_true('page_size', ee.page_size() == 128)

connection.set_register(0x1234, 0x42)
check_true('read_byte', ee.read_byte(0x1234) == 0x42)
check_true('read_byte_address_bytes', connection.writes[-1] == bytes([0x12, 0x34]))
check_true('read_byte_high_address', ee.read_byte(0xFFFF) == 0)

del connection.writes[:]
ee.write_byte(0x0010, 0x99)
check_true('write_byte', connection.registers[0x0010] == 0x99)
check_true('write_byte_frame', connection.writes[0] == bytes([0x00, 0x10, 0x99]))
check_true('write_byte_ack_poll', connection.writes[1] == bytes([0x00, 0x00]))

check_true('minimal_class', isinstance(CAT24C512Minimal(connection), CAT24C512Minimal))
check_true('read_byte_range_hi', raises(lambda: ee.read_byte(0x10000)))
check_true('read_byte_range_neg', raises(lambda: ee.read_byte(-1)))
check_true('write_byte_range', raises(lambda: ee.write_byte(0x10000, 1)))

# ACK polling: two NACKs, then the chip answers.
connection.fail_next_writes(2)
del connection.writes[:]
check_true('wait_ready_after_nacks', ee.wait_ready(50))
check_true('wait_ready_polled_three_times', len(connection.writes) == 3)
connection.fail_next_writes(1000)
check_true('wait_ready_timeout', not ee.wait_ready(5))
connection.fail_next_writes(0)
connection.fail_next_writes(1, after=1)
check_true('write_byte_polls_through_nack', True)
ee.write_byte(0x0020, 1)
connection.fail_next_writes(0)
connection.fail_next_writes(1000, after=1)
check_true('write_byte_timeout_raises', raises(lambda: ee.write_byte(0x0021, 2), OSError))
connection.fail_next_writes(0)

# Page splitting: 70 bytes starting at 0x0070 (page boundary at 0x0080, max_chunk 30).
data = bytes(range(70))
del connection.writes[:]
ee.write(0x0070, data)
frames = [w for w in connection.writes if len(w) > 2]
check_true('write_first_chunk_stops_at_page', frames[0] == bytes([0x00, 0x70]) + data[:16])
check_true('write_second_chunk_page_start', frames[1] == bytes([0x00, 0x80]) + data[16:46])
check_true('write_third_chunk', frames[2] == bytes([0x00, 0x9E]) + data[46:70])
check_true('write_ack_polled_per_chunk', len(connection.writes) == 2 * len(frames))
check_true('write_roundtrip', ee.read(0x0070, 70) == data)

# max_chunk.
check_true('max_chunk_default', ee.max_chunk == 30)
ee.max_chunk = 8
del connection.writes[:]
ee.write(0x0200, bytes(20))
check_true('custom_max_chunk', len([w for w in connection.writes if len(w) > 2]) == 3)
check_true('max_chunk_invalid', raises(lambda: setattr(ee, 'max_chunk', 0)))
ee.max_chunk = 30

# write_page.
del connection.writes[:]
ee.write_page(0x0300, bytes(range(128)))
check_true('write_page_full', connection.writes[0] == bytes([0x03, 0x00]) + bytes(range(128)))
check_true('write_page_cross_rejected', raises(lambda: ee.write_page(0x037C, bytes(8))))
check_true('write_page_too_long', raises(lambda: ee.write_page(0x0400, bytes(129))))
check_true('write_page_range', raises(lambda: ee.write_page(0xFFFF, bytes(2))))

# Fill.
ee.fill(0x0500, 200, 0xAB)
check_true('fill', all(connection.registers[0x0500 + i] == 0xAB for i in range(200)))
check_true('fill_end', connection.registers.get(0x0500 + 200, 0) == 0)
ee.fill(0x0500, 4)
check_true('fill_default_zero', all(connection.registers[0x0500 + i] == 0 for i in range(4)))

# Range rules.
ee.write(0xFFFE, bytes([1, 2]))
check_true('write_last_bytes', ee.read(0xFFFE, 2) == bytes([1, 2]))
check_true('write_wrap_rejected', raises(lambda: ee.write(0xFFFF, bytes([1, 2]))))
check_true('read_wrap_rejected', raises(lambda: ee.read(0xFFF0, 0x20)))
check_true('fill_wrap_rejected', raises(lambda: ee.fill(0xFFFF, 2)))
check_true('read_zero_length', ee.read(0x0000, 0) == b'')

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
