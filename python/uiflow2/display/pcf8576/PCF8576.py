"""
file     PCF8576
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.pcf8576 import PCF8576Full


class PCF8576:
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

    def __init__(self, bus: int = 0, address: int = 0):
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
                default: '0'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCF8576Full(connection)

    def clear(self):
        """
        label:
            en: '%1 clear display'
        """
        self._driver.clear()

    def set_digit(self, position: int, segments: int):
        """
        label:
            en: '%1 set digit position %2 segments %3'
        params:
            position:
                name: position
                type: int
                field: number
            segments:
                name: segments
                type: int
                field: number
        """
        self._driver.set_digit_7seg(position, segments)
