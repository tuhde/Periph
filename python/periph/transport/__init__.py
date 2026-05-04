from .base import Transport
from .spi import SPITransport
from .smbus import SMBusTransport

# I2C transports are platform-specific — import the one matching your target:
#   from periph.transport.i2c_micropython import I2CTransport   (machine.I2C)
#   from periph.transport.i2c_circuitpython import I2CTransport (busio.I2C)
#   from periph.transport.i2c_linux import I2CTransport         (smbus2 / /dev/i2c-N)
