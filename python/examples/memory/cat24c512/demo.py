import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.cat24c512 import CAT24C512Full

connection = I2CConnection(0x50, reg_bytes=2)                                         # Create I2C connection, (addr=0x50, reg_bytes=2) → Connection
eeprom = CAT24C512Full(connection)                                                    # Create CAT24C512 driver, (connection) → None

# --- Persistent boot counter: 4-byte big-endian value at address 0 ---
counter = int.from_bytes(eeprom.read(0x0000, 4), 'big')                               # Sequential read, (address, length) → bytes
counter = (counter + 1) & 0xFFFFFFFF
eeprom.write(0x0000, counter.to_bytes(4, 'big'))                                      # Write data, (address, data) → None
print('Boot counter:', counter)

# --- Page-write benchmark: 3 pages, 128-byte aligned ---
size = 3 * eeprom.page_size()                                                         # Get write page size, () → int
pattern = bytes((i * 7) & 0xFF for i in range(size))
start = time.time()
eeprom.write(0x0080, pattern)                                                         # Write data, (address, data) → None
elapsed = time.time() - start
print('Wrote %d bytes in %.1f ms (%.0f B/s incl. ACK-polled write cycles)' % (size, elapsed * 1000, size / elapsed))
print('Read-back matches:', eeprom.read(0x0080, size) == pattern)                     # Sequential read, (address, length) → bytes

# --- A write that crosses a page boundary is refused ---
try:
    eeprom.write_page(0x00FC, bytes(8))                                               # Write within one page, (address, data) → None
except ValueError as e:
    print('write_page refused:', e)

eeprom.fill(0x0080, size)                                                             # Fill a range, (address, length, value=0) → None
print('Zeroed:', eeprom.read(0x0080, size) == bytes(size))
print('Size', eeprom.size(), 'ready', eeprom.wait_ready(10))                         # Get array size, () → int ; ACK-poll the chip, (timeout_ms) → bool
