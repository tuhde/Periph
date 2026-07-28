from .base import Connection
from .input_pin import InputPin
from .output_pin import OutputPin

# I2C connections are platform-specific — import the one matching your target:
#   from periph.connection.i2c_micropython import I2CConnection   (machine.I2C)
#   from periph.connection.i2c_circuitpython import I2CConnection (busio.I2C)
#   from periph.connection.i2c_linux import I2CConnection         (smbus2 / /dev/i2c-N)

# SPI connections are platform-specific — import the one matching your target:
#   from periph.connection.spi_micropython import SPIConnection   (machine.SPI)
#   from periph.connection.spi_circuitpython import SPIConnection (busio.SPI)
#   from periph.connection.spi_linux import SPIConnection         (spidev / /dev/spidevB.D)

# SMBus connections are platform-specific — import the one matching your target:
#   from periph.connection.smbus_micropython import SMBusConnection   (machine.I2C)
#   from periph.connection.smbus_circuitpython import SMBusConnection (busio.I2C)
#   from periph.connection.smbus_linux import SMBusConnection         (smbus2 / /dev/i2c-N)

# NeoPixel connections are platform-specific — import the one matching your target:
#   from periph.connection.neopixel_micropython import NeoPixelConnection   (machine.SPI)
#   from periph.connection.neopixel_circuitpython import NeoPixelConnection (busio.SPI)
#   from periph.connection.neopixel_linux import NeoPixelConnection         (spidev / /dev/spidevB.D)

# UART connections are platform-specific — import the one matching your target:
#   from periph.connection.uart_micropython import UARTConnection   (machine.UART)
#   from periph.connection.uart_circuitpython import UARTConnection (busio.UART)
#   from periph.connection.uart_linux import UARTConnection         (pyserial / /dev/ttyS0)

# HX711, DHTxx, and SiPo connections are custom GPIO bit-bang protocols and do
# not extend Connection — see hx711_*.py, dhtxx_*.py, sipo_*.py.
