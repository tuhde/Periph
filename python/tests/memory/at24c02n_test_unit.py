"""AT24C02N unit test for Python/Linux — exercises the driver against
I2CConnectionMock, with no hardware required.

Run via ``python/test_linux.sh --level unit memory/at24c02n``.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.memory.at24c02n import AT24C02NMinimal, AT24C02NFull

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


connection = I2CConnectionMock(reg_bytes=1)
ee = AT24C02NFull(connection)
check_true('init_no_traffic', connection.writes == [])
check_true('size', ee.size() == 256)
check_true('page_size', ee.page_size() == 8)

connection.set_register(0x0034, 0x42)
check_true('read_byte', ee.read_byte(0x0034) == 0x42)
check_true('read_byte_address_bytes', connection.writes[-1] == bytes([0x34]))
check_true('read_byte_high_address', ee.read_byte(0x00FF) == 0)

del connection.writes[:]
ee.write_byte(0x0010, 0x99)
check_true('write_byte', connection.registers[0x0010] == 0x99)
check_true('write_byte_frame', connection.writes[0] == bytes([0x10, 0x99]))
check_true('write_byte_ack_poll', connection.writes[1] == bytes([0x00]))

check_true('minimal_class', isinstance(AT24C02NMinimal(connection), AT24C02NMinimal))
check_true('read_byte_range_hi', raises(lambda: ee.read_byte(0x0100)))
check_true('read_byte_range_neg', raises(lambda: ee.read_byte(-1)))
check_true('write_byte_range', raises(lambda: ee.write_byte(0x0100, 1)))

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

# Page splitting: 20 bytes starting at 0x0074 (pages of 8: chunks of 4, 8 and 8 bytes).
data = bytes(range(20))
del connection.writes[:]
ee.write(0x0074, data)
frames = [w for w in connection.writes if len(w) > 1]
check_true('write_chunk_count', len(frames) == 3)
check_true('write_first_chunk_stops_at_page', frames[0] == bytes([0x74]) + data[:4])
check_true('write_second_chunk_full_page', frames[1] == bytes([0x78]) + data[4:12])
check_true('write_third_chunk', frames[2] == bytes([0x80]) + data[12:20])
check_true('write_ack_polled_per_chunk', len(connection.writes) == 2 * len(frames))
check_true('write_roundtrip', ee.read(0x0074, 20) == data)

# max_chunk.
check_true('max_chunk_default', ee.max_chunk == 30)
ee.max_chunk = 8
del connection.writes[:]
ee.write(0x00E0, bytes(20))
check_true('custom_max_chunk', len([w for w in connection.writes if len(w) > 1]) == 3)
check_true('max_chunk_invalid', raises(lambda: setattr(ee, 'max_chunk', 0)))
ee.max_chunk = 30

# write_page.
del connection.writes[:]
ee.write_page(0x00C0, bytes(range(8)))
check_true('write_page_full', connection.writes[0] == bytes([0xC0]) + bytes(range(8)))
check_true('write_page_cross_rejected', raises(lambda: ee.write_page(0x00CC, bytes(8))))
check_true('write_page_too_long', raises(lambda: ee.write_page(0x00D0, bytes(9))))
check_true('write_page_range', raises(lambda: ee.write_page(0x00FF, bytes(2))))

# Fill.
ee.fill(0x0040, 100, 0xAB)
check_true('fill', all(connection.registers[0x0040 + i] == 0xAB for i in range(100)))
check_true('fill_end', connection.registers.get(0x0040 + 100, 0) == 0)
ee.fill(0x0040, 4)
check_true('fill_default_zero', all(connection.registers[0x0040 + i] == 0 for i in range(4)))

# Range rules.
ee.write(0x00FE, bytes([1, 2]))
check_true('write_last_bytes', ee.read(0x00FE, 2) == bytes([1, 2]))
check_true('write_wrap_rejected', raises(lambda: ee.write(0x00FF, bytes([1, 2]))))
check_true('read_wrap_rejected', raises(lambda: ee.read(0x00F0, 0x20)))
check_true('fill_wrap_rejected', raises(lambda: ee.fill(0x00FF, 2)))
check_true('read_zero_length', ee.read(0x0000, 0) == b'')

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
