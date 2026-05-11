from .base import Transport

# I2C transports are platform-specific — import the one matching your target:
#   from periph.transport.i2c_micropython import I2CTransport   (machine.I2C)
#   from periph.transport.i2c_circuitpython import I2CTransport (busio.I2C)
#   from periph.transport.i2c_linux import I2CTransport         (smbus2 / /dev/i2c-N)

# SPI transports are platform-specific — import the one matching your target:
#   from periph.transport.spi_micropython import SPITransport   (machine.SPI)
#   from periph.transport.spi_circuitpython import SPITransport (busio.SPI)
#   from periph.transport.spi_linux import SPITransport         (spidev / /dev/spidevB.D)

# SMBus transports are platform-specific — import the one matching your target:
#   from periph.transport.smbus_micropython import SMBusTransport   (machine.I2C)
#   from periph.transport.smbus_circuitpython import SMBusTransport (busio.I2C)
#   from periph.transport.smbus_linux import SMBusTransport         (smbus2 / /dev/i2c-N)
