from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.mb85rc import MB85RCFull

connection = I2CConnection(0x50, reg_bytes=2)                                         # Create I2C connection, (addr=0x50, reg_bytes=2) → Connection
fram = MB85RCFull(connection)                                                         # Create MB85RC driver, (connection) → None

print('Size:', fram.size(), 'bytes')                                                  # Get array size, () → int
                                                                                      # always 32768 for the MB85RC256V
fram.max_chunk = 16                                                                   # Set max bytes per bus transaction, (max_chunk=30) → None
print('max_chunk:', fram.max_chunk)                                                   # Get max bytes per bus transaction, () → int

fram.write_byte(0x0010, 0xA5)                                                         # Write a single byte, (address 0–32767, value 0–255) → None
                                                                                      # no write cycle, no polling
print('Byte:', hex(fram.read_byte(0x0010)))                                           # Read a single byte, (address 0–32767) → int
                                                                                      # random read

fram.write(0x0100, bytes(range(40)))                                                  # Sequential write, (address, data) → None
                                                                                      # split into max_chunk-byte transactions
data = fram.read(0x0100, 40)                                                          # Sequential read, (address, length) → bytes
                                                                                      # split into max_chunk-byte transactions
print('Block:', data.hex())

fram.fill(0x0100, 40, 0xFF)                                                           # Fill a range, (address, length, value=0) → None
                                                                                      # writes value over the range in chunks
print('After fill:', fram.read(0x0100, 8).hex())

try:
    id_connection = I2CConnection(0x7C, reg_bytes=1)                                  # Create Device ID connection, (addr=0x7C, reg_bytes=1) → Connection
    manufacturer, density, product = fram.read_device_id(id_connection, 0x50)         # Read Device ID, (id_connection, address=0x50) → (int, int, int)
                                                                                      # reads 3 bytes via reserved slave address 0x7C
    print('Device ID: mfr=0x%03X density=0x%X product=0x%02X' % (manufacturer, density, product))
except OSError:
    print('Device ID not readable on this bus')
