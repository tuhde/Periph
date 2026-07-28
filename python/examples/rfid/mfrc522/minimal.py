import time
from periph.connection.spi_auto import SPIConnection
from periph.chips.rfid.mfrc522 import MFRC522Minimal

connection = SPIConnection(bus=1, cs_pin=9)                          # Create SPI connection, (bus, cs)
mfrc = MFRC522Minimal(connection)                           # Create MFRC522 driver, (connection, bus_type='spi')

for _ in range(10):
    present = mfrc.is_card_present()                       # Detect card in field, () → bool
    uid = mfrc.read_uid()                                  # Read card UID (REQA → anticollision → HLTA), () → bytes | None
    print('present={} uid={}'.format(present, uid.hex() if uid else None))
    time.sleep_ms(500)
print('===DONE: 0 passed, 0 failed===')
