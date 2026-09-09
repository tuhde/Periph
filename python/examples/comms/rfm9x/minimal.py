from machine import SPI, Pin
from periph.transport.spi_micropython import SPITransport
from periph.chips.comms.rfm9x import RFM95Minimal
import time

spi = SPI(1, baudrate=5_000_000, polarity=0, phase=0)
cs = Pin(5, Pin.OUT)
transport = SPITransport(spi, cs)
radio = RFM95Minimal(transport, 868_000_000)

while True:
    radio.send(b"hello")
    print("sent; sleeping 2 s")                              # Send packet, (data=bytes ≤255 B) → None
    time.sleep(2)
