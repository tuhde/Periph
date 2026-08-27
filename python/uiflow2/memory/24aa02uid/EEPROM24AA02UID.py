"""
file     EEPROM24AA02UID
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull


class EEPROM24AA02UID:
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

    def __init__(self, bus: int = 0, address: int = 80):
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
                default: '80'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = EEPROM24AA02UIDFull(connection)

    def read_uid(self) -> str:
        """
        label:
            en: '%1 unique serial number (hex)'
        """
        return self._driver.read_uid().hex()

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
