from periph.connection.uart_auto import UARTConnection
from periph.chips.gnss.neo6 import NEO6Minimal
import time

# To use I2C (DDC) instead of UART:
#   from periph.connection.i2c_auto import I2CConnection
#   connection = I2CConnection(0x42, bus=0, freq=100_000)
#   gps = NEO6Minimal(connection, bus_type='i2c')
# To use SPI instead of UART:
#   from periph.connection.spi_auto import SPIConnection
#   connection = SPIConnection(bus=1, cs_pin=5, baudrate=200_000)
#   gps = NEO6Minimal(connection, bus_type='spi')

connection = UARTConnection(port=1, baudrate=9600, tx=4, rx=5)
gps = NEO6Minimal(connection)                          # Create NEO-6 driver, (connection, bus_type='uart')

while True:
    if gps.update():                                  # Read + parse one NMEA sentence, () → bool
        print(gps.latitude(), gps.longitude(), gps.altitude())
    time.sleep_ms(50)
