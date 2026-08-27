"""
file     MCP23017
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.mcp23017 import Mcp23017Full


class MCP23017:
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
        self._driver = Mcp23017Full(connection)

    def set_pin_mode(self, pin: int, mode: int):
        """
        label:
            en: '%1 set pin mode: IN=0, OUT=1 pin %2 mode %3'
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
