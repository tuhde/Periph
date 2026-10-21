"""
file     SRAM23X1024
time     2026-10-21
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.memory._23x1024 import SRAM23X1024Full


class SRAM23X1024:
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

    def __init__(self, bus: int = 0, cs_pin: int = 5):
        """
        label:
            en: '%1 init bus %2 CS pin %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = SRAM23X1024Full(connection)
    def read_byte(self, address: int) -> int:
        """
        label:
            en: '%1 read byte address %2'
        params:
            address:
                name: address
                type: int
                field: number
        """
        return self._driver.read_byte(address)

    def write_byte(self, address: int, value: int):
        """
        label:
            en: '%1 write byte address %2 value %3'
        params:
            address:
                name: address
                type: int
                field: number
            value:
                name: value
                type: int
                field: number
        """
        self._driver.write_byte(address, value)

    def fill(self, address: int, length: int, value: int = 0):
        """
        label:
            en: '%1 fill address %2 length %3 value %4'
        params:
            address:
                name: address
                type: int
                field: number
            length:
                name: length
                type: int
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.fill(address, length, value)

    def size(self) -> int:
        """
        label:
            en: '%1 size (bytes)'
        """
        return self._driver.size()

    def set_mode(self, mode: int = 64):
        """
        label:
            en: '%1 set mode (0 byte, 128 page, 64 sequential) %2'
        params:
            mode:
                name: mode
                type: int
                default: '64'
                field: number
        """
        self._driver.set_mode(mode)

    def get_mode(self) -> int:
        """
        label:
            en: '%1 mode (0 byte, 128 page, 64 sequential)'
        """
        return self._driver.get_mode()

    def reset_io(self):
        """
        label:
            en: '%1 reset I/O (back to SPI mode)'
        """
        self._driver.reset_io()