from periph.transport.i2c_auto import I2CTransport
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDMinimal
import time

transport = I2CTransport(0x50)                                                      # Create I2C transport, (addr=0x50) → Transport
eeprom = EEPROM24AA02UIDMinimal(transport)                                          # Create 24AA02UID driver, (transport) → None

while True:
    uid = eeprom.read_uid()                                                         # Read 32-bit unique serial number, () → bytes
    print('UID:', uid.hex().upper())
    time.sleep(2)
