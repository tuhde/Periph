"""
file     MCP23008
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.mcp23008 import Mcp23008Full


class MCP23008:
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
        self._driver = Mcp23008Full(connection)

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

    def read_port(self) -> int:
        """
        label:
            en: '%1 read port (8 pins)'
        """
        return self._driver.read_port()

    def write_port(self, mask: int):
        """
        label:
            en: '%1 write port mask (0-255) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.write_port(0, mask)

    def configure_pullup(self, mask: int):
        """
        label:
            en: '%1 pull-up mask (0-255) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.configure_pullup(mask)

    def configure_polarity(self, mask: int):
        """
        label:
            en: '%1 input polarity mask (0-255) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.configure_polarity(mask)

    def set_default_value(self, mask: int):
        """
        label:
            en: '%1 interrupt default value (0-255) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.set_default_value(mask)

    def enable_pin_interrupt(self, mask: int):
        """
        label:
            en: '%1 interrupt-enable mask (0-255) mask %2'
        params:
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.enable_pin_interrupt(mask)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt flags (clears INT)'
        """
        return self._driver.poll_interrupt()

    def read_capture(self) -> int:
        """
        label:
            en: '%1 read interrupt capture (clears INT)'
        """
        return self._driver.read_capture()

    def reset(self):
        """
        label:
            en: '%1 reset registers to defaults'
        """
        self._driver.reset()
