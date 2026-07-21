import time
from periph.transport.spi_micropython import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Minimal
from machine import SPI, Pin

spi = SPI(1, baudrate=1000000, polarity=0, phase=0)
cs  = Pin('P9', Pin.OUT)
transport = SPITransport(spi, cs)                          # Create SPI transport, (bus, cs)
mfrc = MFRC522Minimal(transport)                           # Create MFRC522 driver, (transport, bus_type='spi')

for _ in range(10):
    present = mfrc.is_card_present()                       # Detect card in field, () → bool
    uid = mfrc.read_uid()                                  # Read card UID (REQA → anticollision → HLTA), () → bytes | None
    print('present={} uid={}'.format(present, uid.hex() if uid else None))
    time.sleep_ms(500)
print('===DONE: 0 passed, 0 failed===')
