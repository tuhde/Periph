from abc import ABC

from .base import Connection


class RegisterConnection(Connection, ABC):
    """Connection with register-addressed read/write, for I2C/SMBus/SPI-style buses.

    Sits between Connection and the register-capable concrete classes
    (I2CConnection, SMBusConnection, SPIConnection); every other concrete
    connection (UARTConnection, HX711Connection, NeoPixelConnection,
    SiPoConnection, DHTxxConnection) is unaffected and continues to extend
    Connection only.

    The default implementation below (write_read/write of a leading
    register-address byte) matches I2C/SMBus behavior as-is; SPIConnection
    overrides both to build its command byte first.
    """

    def read_reg(self, reg: int, length: int) -> bytes:
        """Read `length` bytes starting at register `reg`.

        Args:
            reg: Register address.
            length: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        return self.write_read(bytes([reg]), length)

    def write_reg(self, reg: int, data) -> None:
        """Write `data` to register `reg`.

        Args:
            reg: Register address.
            data: Bytes to write, or a single int for a 1-byte register.
        """
        payload = bytes([reg]) + (bytes([data]) if isinstance(data, int) else bytes(data))
        self.write(payload)
