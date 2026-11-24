"""
file     SAA1064
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.saa1064 import SAA1064Full


class SAA1064:
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

    def __init__(self, bus: int = 0, address: int = 56):
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
                default: '56'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = SAA1064Full(connection)

    def clear(self):
        """
        label:
            en: '%1 clear'
        """
        self._driver.clear()

    def show_number(self, value: int):
        """
        label:
            en: '%1 show number %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.show_number(value)

    def show_digit(self, position: int, value: int, dp: int):
        """
        label:
            en: '%1 show digit %2 %3 %4'
        params:
            position:
                name: position
                type: int
                default: '1'
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
            dp:
                name: dp
                type: int
                default: '0'
                field: number
        """
        self._driver.show_digit(position, None if value < 0 else value, bool(dp))

    def set_decimal_point(self, position: int, on: int):
        """
        label:
            en: '%1 set decimal point %2 %3'
        params:
            position:
                name: position
                type: int
                default: '1'
                field: number
            'on':
                name: 'on'
                type: int
                default: '1'
                field: number
        """
        self._driver.set_decimal_point(position, bool(on))

    def set_mode(self, dynamic: int):
        """
        label:
            en: '%1 set dynamic mode %2'
        params:
            dynamic:
                name: dynamic
                type: int
                default: '1'
                field: number
        """
        self._driver.set_mode(bool(dynamic))

    def blank(self, digits_13: int, digits_24: int):
        """
        label:
            en: '%1 blank %2 %3'
        params:
            digits_13:
                name: digits_13
                type: int
                default: '0'
                field: number
            digits_24:
                name: digits_24
                type: int
                default: '0'
                field: number
        """
        self._driver.blank(bool(digits_13), bool(digits_24))

    def set_current(self, ma: int):
        """
        label:
            en: '%1 set current %2'
        params:
            ma:
                name: ma
                type: int
                default: '12'
                field: number
        """
        self._driver.set_current(ma)

    def segment_test(self, on: int):
        """
        label:
            en: '%1 segment test %2'
        params:
            'on':
                name: 'on'
                type: int
                default: '1'
                field: number
        """
        self._driver.segment_test(bool(on))

    def write_control(self, value: int):
        """
        label:
            en: '%1 write control %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.write_control(value)

    def read_status(self) -> int:
        """
        label:
            en: '%1 read status'
        """
        return self._driver.read_status()

    def power_reset_flag(self) -> int:
        """
        label:
            en: '%1 power reset flag'
        """
        return int(self._driver.power_reset_flag())
