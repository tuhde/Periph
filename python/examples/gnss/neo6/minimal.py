from periph.transport.uart_auto import UARTTransport
from periph.chips.gnss.neo6 import NEO6Minimal
import time

# To use I2C (DDC) instead of UART:
#   from periph.transport.i2c_auto import I2CTransport
#   transport = I2CTransport(0x42, bus=0, freq=100_000)
#   gps = NEO6Minimal(transport, bus_type='i2c')
# To use SPI instead of UART:
#   from periph.transport.spi_auto import SPITransport
#   transport = SPITransport(bus=1, cs_pin=5, baudrate=200_000)
#   gps = NEO6Minimal(transport, bus_type='spi')

transport = UARTTransport(port=1, baudrate=9600, tx=4, rx=5)
gps = NEO6Minimal(transport)                          # Create NEO-6 driver, (transport, bus_type='uart')

while True:
    if gps.update():                                  # Read + parse one NMEA sentence, () → bool
        print(gps.latitude(), gps.longitude(), gps.altitude())
    time.sleep_ms(50)
