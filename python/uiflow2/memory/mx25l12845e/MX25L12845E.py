"""
file     MX25L12845E
time     2026-10-21
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.memory.mx25l12845e import MX25L12845EFull


class MX25L12845E:
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

    def __init__(self, bus: int = 2, cs_pin: int = 5):
        """
        label:
            en: '%1 init bus %2 CS pin %3'
        params:
            bus:
                name: bus
                type: int
                default: '2'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = MX25L12845EFull(connection)

    def size(self) -> int:
        """
        label:
            en: '%1 capacity (bytes)'
        """
        return self._driver.size()

    def jedec_id(self) -> str:
        """
        label:
            en: '%1 JEDEC ID (hex)'
        """
        return self._driver.jedec_id().hex()

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
        return self._driver.read_bytes(address, 1)[0]

    def write_byte(self, address: int, value: int):
        """
        label:
            en: '%1 write byte address %2 value %3 (erases the sector if needed)'
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
        self._driver.write_bytes(address, bytes([value]))

    def erase_sector(self, address: int):
        """
        label:
            en: '%1 erase 4 KiB sector at address %2'
        params:
            address:
                name: address
                type: int
                field: number
        """
        self._driver.erase_sector(address)

    def deep_power_down(self):
        """
        label:
            en: '%1 deep power-down'
        """
        self._driver.deep_power_down()

    def wake(self):
        """
        label:
            en: '%1 wake up (release deep power-down)'
        """
        self._driver.release_deep_power_down()