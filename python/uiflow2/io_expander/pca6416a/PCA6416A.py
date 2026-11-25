"""
file     PCA6416A
time     2026-11-11
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca6416a import Pca6416aFull


class PCA6416A:
    """
    note:
        en: ''
    details:
        color: '#C084FC'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self, bus: int = 0, address: int = 32):
        """
        label:
            en: '%1 init bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '32'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = Pca6416aFull(connection)

    def set_pin_mode(self, pin: int, mode: int):
        """
        label:
            en: '%1 set pin (0-15) mode: IN=0, OUT=1 pin %2 mode %3'
        params:
            pin:
                name: pin
                type: int
                field: number
            mode:
                name: mode
                type: int
                field: number
        """
        self._driver.pin(pin).init(mode)

    def write_pin(self, pin: int, value: int):
        """
        label:
            en: '%1 write pin value (0/1) pin %2 value %3'
        params:
            pin:
                name: pin
                type: int
                field: number
            value:
                name: value
                type: int
                field: number
        """
        self._driver.pin(pin).value(bool(value))

    def read_pin(self, pin: int) -> bool:
        """
        label:
            en: '%1 read pin pin %2'
        params:
            pin:
                name: pin
                type: int
                field: number
        """
        return bool(self._driver.pin(pin).value())

    def read_port(self, port: int) -> int:
        """
        label:
            en: '%1 read port (8 pins) port %2'
        params:
            port:
                name: port
                type: int
                field: number
        """
        return self._driver.read_port(port)

    def write_port(self, port: int, mask: int):
        """
        label:
            en: '%1 write port mask (0-255) port %2 mask %3'
        params:
            port:
                name: port
                type: int
                field: number
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.write_port(port, mask)

    def read_all(self) -> int:
        """
        label:
            en: '%1 read all 16 pins'
        """
        return self._driver.read_all()

    def write_all(self, value: int):
        """
        label:
            en: '%1 write all 16 outputs (0-65535) value %2'
        params:
            value:
                name: value
                type: int
                field: number
        """
        self._driver.write_all(value)

    def configure_polarity(self, mask: int):
        """
        label:
            en: '%1 input polarity mask (0-65535) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.configure_polarity(mask)

    def set_direction(self, mask: int):
        """
        label:
            en: '%1 direction mask, 1=input (0-65535) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.set_direction(mask)

    def get_direction(self) -> int:
        """
        label:
            en: '%1 read direction mask'
        """
        return self._driver.get_direction()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll changed-pin mask (clears INT)'
        """
        return self._driver.poll_interrupt()

    def reset(self):
        """
        label:
            en: '%1 reset registers to defaults'
        """
        self._driver.reset()
