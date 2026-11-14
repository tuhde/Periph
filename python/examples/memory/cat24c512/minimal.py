from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.cat24c512 import CAT24C512Minimal

connection = I2CConnection(0x50, reg_bytes=2)                                         # Create I2C connection, (addr=0x50, reg_bytes=2) → Connection
eeprom = CAT24C512Minimal(connection)                                                 # Create CAT24C512 driver, (connection) → None

eeprom.write_byte(0x0000, 0xA5)                                                       # Write a single byte, (address 0–65535, value 0–255) → None
print('Byte:', hex(eeprom.read_byte(0x0000)))                                         # Read a single byte, (address 0–65535) → int
