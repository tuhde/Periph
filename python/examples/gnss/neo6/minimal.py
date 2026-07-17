from machine import UART, Pin
from periph.transport.uart_micropython import UARTTransport
from periph.chips.gnss.neo6 import NEO6Minimal
import time

# To use I2C (DDC) instead of UART:
#   from periph.transport.i2c_micropython import I2CTransport
#   transport = I2CTransport(I2C(0, scl=Pin(9), sda=Pin(8), freq=100_000), 0x42)
#   gps = NEO6Minimal(transport, bus_type='i2c')
# To use SPI instead of UART:
#   from periph.transport.spi_micropython import SPITransport
#   transport = SPITransport(SPI(1, baudrate=200_000), Pin(5))
#   gps = NEO6Minimal(transport, bus_type='spi')

uart = UART(1, baudrate=9600, tx=Pin(4), rx=Pin(5))
transport = UARTTransport(uart, baudrate=9600)
gps = NEO6Minimal(transport)                          # Create NEO-6 driver, (transport, bus_type='uart')

while True:
    if gps.update():                                  # Read + parse one NMEA sentence, () → bool
        print(gps.latitude(), gps.longitude(), gps.altitude())
    time.sleep_ms(50)
