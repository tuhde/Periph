from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.at24c64 import AT24C64Full

connection = I2CConnection(0x50, reg_bytes=2)                                         # Create I2C connection, (addr=0x50, reg_bytes=2) → Connection
eeprom = AT24C64Full(connection)                                                    # Create AT24C64 driver, (connection) → None

print('Size:', eeprom.size(), 'bytes')                                                # Get array size, () → int
                                                                                      # always 8192
print('Page:', eeprom.page_size(), 'bytes')                                           # Get write page size, () → int
                                                                                      # always 32
eeprom.max_chunk = 16                                                                 # Set max bytes per bus transaction, (max_chunk=30) → None
print('max_chunk:', eeprom.max_chunk)                                                 # Get max bytes per bus transaction, () → int

eeprom.write_byte(0x0010, 0xA5)                                                       # Write a single byte, (address 0–8191, value 0–255) → None
                                                                                      # ACK-polled until the write cycle ends
print('Byte:', hex(eeprom.read_byte(0x0010)))                                         # Read a single byte, (address 0–8191) → int
                                                                                      # random read

eeprom.write(0x0100, bytes(range(40)))                                                # Write data, (address, data) → None
                                                                                      # split at 32-byte page boundaries and max_chunk, ACK-polled per chunk
data = eeprom.read(0x0100, 40)                                                        # Sequential read, (address, length) → bytes
                                                                                      # split into max_chunk-byte transactions
print('Block:', data.hex())

eeprom.write_page(0x0180, bytes(range(16)))                                           # Write within one page, (address, data) → None
                                                                                      # raises ValueError if the data crosses a page boundary
eeprom.fill(0x0100, 40, 0xFF)                                                         # Fill a range, (address, length, value=0) → None
                                                                                      # page-wise writes of value
print('After fill:', eeprom.read(0x0100, 8).hex())

print('Ready:', eeprom.wait_ready(10))                                                # ACK-poll the chip, (timeout_ms) → bool
                                                                                      # True once the chip answers its address
