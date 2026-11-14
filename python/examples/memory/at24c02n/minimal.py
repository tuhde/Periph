from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.at24c02n import AT24C02NMinimal

connection = I2CConnection(0x50)                                         # Create I2C connection, (addr=0x50) → Connection
eeprom = AT24C02NMinimal(connection)                                                 # Create AT24C02N driver, (connection) → None

eeprom.write_byte(0x0000, 0xA5)                                                       # Write a single byte, (address 0–255, value 0–255) → None
print('Byte:', hex(eeprom.read_byte(0x0000)))                                         # Read a single byte, (address 0–255) → int
