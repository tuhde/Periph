import time
from periph.transport.spi_auto import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Minimal

transport = SPITransport(bus=1, cs_pin=9)                          # Create SPI transport, (bus, cs)
mfrc = MFRC522Minimal(transport)                           # Create MFRC522 driver, (transport, bus_type='spi')

for _ in range(10):
    present = mfrc.is_card_present()                       # Detect card in field, () → bool
    uid = mfrc.read_uid()                                  # Read card UID (REQA → anticollision → HLTA), () → bytes | None
    print('present={} uid={}'.format(present, uid.hex() if uid else None))
    time.sleep_ms(500)
print('===DONE: 0 passed, 0 failed===')
