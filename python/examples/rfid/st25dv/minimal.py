import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.st25dv import ST25DVMinimal

user = I2CConnection(0x53, reg_bytes=2)                                               # Create user-memory connection, (addr=0x53, reg_bytes=2) → Connection
system = I2CConnection(0x57, reg_bytes=2)                                             # Create system-area connection, (addr=0x57, reg_bytes=2) → Connection
tag = ST25DVMinimal(user, system)                                                     # Create ST25DV driver, (user, system, gpo_active_high=False) → None

tag.write(0x0100, b'Hello NFC')                                                       # Write user memory, (address, data) → None
while True:
    print('Memory:', tag.read(0x0100, 9))                                             # Read user memory, (address, length) → bytes
    time.sleep(1)
