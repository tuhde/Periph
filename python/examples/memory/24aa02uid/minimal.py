from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDMinimal
import time

connection = I2CConnection(0x50)                                                      # Create I2C connection, (addr=0x50) → Connection
eeprom = EEPROM24AA02UIDMinimal(connection)                                          # Create 24AA02UID driver, (connection) → None

while True:
    uid = eeprom.read_uid()                                                         # Read 32-bit unique serial number, () → bytes
    print('UID:', uid.hex().upper())
    time.sleep(2)
