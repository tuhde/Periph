from abc import ABC

from .base import Connection


class RegisterConnection(Connection, ABC):
    """Connection with register-addressed read/write, for I2C/SMBus/SPI-style buses.

    Sits between Connection and the register-capable concrete classes
    (I2CConnection, SMBusConnection, SPIConnection); every other concrete
    connection (UARTConnection, HX711Connection, NeoPixelConnection,
    SiPoConnection, DHTxxConnection) is unaffected and continues to extend
    Connection only.

    The default implementation below (write_read/write of a leading, big-endian
    register-address of `reg_bytes` bytes) matches I2C/SMBus behavior as-is;
    SPIConnection overrides both to build its single-byte command byte instead
    (see specs/feature_register_access_design.md §11.2 for why SPI stays
    single-byte).

    Args:
        int_pin: Optional InputPin for INT-line delivery.
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
        reg_bytes: Register address width in bytes, big-endian (default 1).
    """

    def __init__(self, int_pin=None, en_pin=None, reg_bytes=1, en_active_high=True):
        super().__init__(int_pin, en_pin, en_active_high=en_active_high)
        self._reg_bytes = reg_bytes

    def read_reg(self, reg: int, length: int) -> bytes:
        """Read `length` bytes starting at register `reg`.

        Args:
            reg: Register address.
            length: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        return self.write_read(reg.to_bytes(self._reg_bytes, 'big'), length)

    def write_reg(self, reg: int, data) -> None:
        """Write `data` to register `reg`.

        Args:
            reg: Register address.
            data: Bytes to write, or a single int for a 1-byte register.
        """
        payload = reg.to_bytes(self._reg_bytes, 'big') + (bytes([data]) if isinstance(data, int) else bytes(data))
        self.write(payload)
