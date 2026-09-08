import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull

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


connection = I2CConnectionMock()

# UID (0xFC-0xFF), MSB first.
connection.set_register(EEPROM24AA02UIDFull._ADDR_UID_BASE, 0xAA, 0xBB, 0xCC, 0xDD)

eeprom = EEPROM24AA02UIDFull(connection)
check_true('init', True)

check_true('read_uid', eeprom.read_uid() == bytes([0xAA, 0xBB, 0xCC, 0xDD]))

connection.set_register(0x10, 0x42)
check_true('read_byte', eeprom.read_byte(0x10) == 0x42)

eeprom.write_byte(0x10, 0x99)
check_true('write_byte', connection.registers[0x10] == 0x99)
check_true('write_byte_issues_write', connection.writes[-2] == bytes([0x10, 0x99]))

# Sequential read (0x05-0x08).
connection.set_register(0x05, 1, 2, 3, 4)
check_true('read', eeprom.read(0x05, 4) == bytes([1, 2, 3, 4]))

eeprom.write_page(0x08, bytes([10, 20, 30]))
check_true('write_page', connection.registers[0x08] == 10 and
           connection.registers[0x09] == 20 and connection.registers[0x0A] == 30)

# write() spanning a page boundary: page 0 is 0x00-0x07, page 1 is 0x08-0x0F.
# Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3 bytes, page 0) then
# [0x08..0x0E] (7 bytes, page 1).
eeprom.write(0x05, bytes(range(100, 110)))
# Each write_page() call issues a data write followed by an ack-poll
# write_read, so the two page-chunk writes are at [-4] and [-2].
check_true('write_page0_chunk', connection.writes[-4] == bytes([0x05, 100, 101, 102]))
check_true('write_page1_chunk', connection.writes[-2] == bytes([0x08, 103, 104, 105, 106, 107, 108, 109]))
check_true('write_updated_registers',
           connection.registers[0x05] == 100 and connection.registers[0x06] == 101 and
           connection.registers[0x07] == 102 and connection.registers[0x08] == 103 and
           connection.registers[0x0E] == 109)

connection.set_register(EEPROM24AA02UIDFull._ADDR_MFR_CODE, 0x29)
check_true('read_manufacturer_code', eeprom.read_manufacturer_code() == 0x29)

connection.set_register(EEPROM24AA02UIDFull._ADDR_DEV_CODE, 0x41)
check_true('read_device_code', eeprom.read_device_code() == 0x41)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
