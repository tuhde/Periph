import time
from machine import SPI, Pin
from periph.transport.spi_micropython import SPITransport
from periph.chips.comms.rfm9x import RFM95Minimal

spi = SPI(1, baudrate=5000000, polarity=0, phase=0,
          sck=Pin(10), mosi=Pin(11), miso=Pin(12))
cs = Pin(13, Pin.OUT)
transport = SPITransport(spi, cs)
rfm = RFM95Minimal(transport, 868_000_000)            # Create RFM95 driver, (transport, frequency_hz=868 MHz)

version = rfm.version()                               # Read silicon revision, () → int
print('version: 0x{:02X}'.format(version))

rfm.send(b'Hello')                                    # Transmit packet, (data: bytes) → None
print('sent')

rfm.send(b'World')                                    # Transmit packet, (data: bytes) → None
print('sent')

packet = rfm.receive(timeout_ms=1000)                 # Receive packet, (timeout_ms=2000 ms) → bytes | None
if packet:
    print('received:', packet)

rfm.standby()                                         # Enter STANDBY mode, () → None

rfm.sleep()                                           # Enter SLEEP mode, () → None