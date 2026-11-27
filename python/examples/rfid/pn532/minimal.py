from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.pn532 import PN532Minimal
import time

connection = I2CConnection(0x24)                                         # Create I²C connection, (addr=0x24) → Connection
reader = PN532Minimal(connection)                                        # Create PN532 driver, (connection) → None
reader.init()                                                            # Wake, check firmware, SAM normal mode, finite retries, () → None

while True:
    uid = reader.read_uid()                                              # Detect a type A card, (timeout=1.0 s) → bytes | None
    print('UID', ''.join('%02X' % b for b in uid) if uid else None)
    time.sleep(1)
