"""MB85RC unit test for Python/Linux — exercises the driver against
I2CConnectionMock, with no hardware required.

Run via ``python/test_linux.sh --level unit memory/mb85rc``.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.memory.mb85rc import MB85RCMinimal, MB85RCFull

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


def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    return False


connection = I2CConnectionMock(reg_bytes=2)
fram = MB85RCFull(connection)
check_true('init_no_traffic', connection.writes == [])
check_true('size', fram.size() == 32768)

connection.set_register(0x1234, 0x42)
check_true('read_byte', fram.read_byte(0x1234) == 0x42)
check_true('read_byte_address_bytes', connection.writes[-1] == bytes([0x12, 0x34]))

fram.write_byte(0x0010, 0x99)
check_true('write_byte', connection.registers[0x0010] == 0x99)
check_true('write_byte_frame', connection.writes[-1] == bytes([0x00, 0x10, 0x99]))

check_true('minimal_class', isinstance(MB85RCMinimal(connection), MB85RCMinimal))
check_true('read_byte_range_hi', raises(lambda: fram.read_byte(0x8000)))
check_true('read_byte_range_neg', raises(lambda: fram.read_byte(-1)))
check_true('write_byte_range', raises(lambda: fram.write_byte(0x8000, 1)))

# Chunking: 70 bytes at max_chunk=30 -> 30 + 30 + 10.
check_true('max_chunk_default', fram.max_chunk == 30)
data = bytes(range(70))
del connection.writes[:]
fram.write(0x0100, data)
check_true('write_chunk_count', len(connection.writes) == 3)
check_true('write_chunk_0', connection.writes[0] == bytes([0x01, 0x00]) + data[:30])
check_true('write_chunk_1', connection.writes[1] == bytes([0x01, 30 + 0x00]) + data[30:60])
check_true('write_chunk_2', connection.writes[2] == bytes([0x01, 60]) + data[60:])
del connection.writes[:]
check_true('read_roundtrip', fram.read(0x0100, 70) == data)
check_true('read_chunk_count', len(connection.writes) == 3)
check_true('read_chunk_addr', connection.writes[1] == bytes([0x01, 30]))

fram.max_chunk = 8
del connection.writes[:]
fram.write(0x0200, bytes(20))
check_true('custom_max_chunk', len(connection.writes) == 3)
check_true('max_chunk_invalid', raises(lambda: setattr(fram, 'max_chunk', 0)))
fram.max_chunk = 30

# Fill.
fram.fill(0x0300, 65, 0xAB)
check_true('fill', all(connection.registers[0x0300 + i] == 0xAB for i in range(65)))
check_true('fill_end', connection.registers.get(0x0300 + 65, 0) == 0)
fram.fill(0x0300, 4)
check_true('fill_default_zero', all(connection.registers[0x0300 + i] == 0 for i in range(4)))

# Range rules: reaching exactly 0x7FFF is fine, wrapping is rejected.
fram.write(0x7FFE, bytes([1, 2]))
check_true('write_last_bytes', fram.read(0x7FFE, 2) == bytes([1, 2]))
check_true('write_wrap_rejected', raises(lambda: fram.write(0x7FFF, bytes([1, 2]))))
check_true('read_wrap_rejected', raises(lambda: fram.read(0x7FF0, 0x20)))
check_true('fill_wrap_rejected', raises(lambda: fram.fill(0x7FFF, 2)))
check_true('read_zero_length', fram.read(0x0000, 0) == b'')

# Device ID: set A2A1A0 = 3 -> address word 0xA6. The mock is keyed by a
# 1-byte register for the ID connection.
id_connection = I2CConnectionMock()
id_connection.set_register(0xA6, 0x00, 0xA5, 0x10)
check_true('read_device_id', fram.read_device_id(id_connection, 0x53) == (0x00A, 0x5, 0x10))
check_true('read_device_id_word', id_connection.writes[-1] == bytes([0xA6]))
id_connection.set_register(0xA0, 0x00, 0xA5, 0x10)
check_true('read_device_id_default', fram.read_device_id(id_connection) == (0x00A, 0x5, 0x10))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
