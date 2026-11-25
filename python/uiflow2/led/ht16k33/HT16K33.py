"""
file     HT16K33
time     2026-11-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.ht16k33 import HT16K33Full


class HT16K33:
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

    def __init__(self, bus: int = 0, address: int = 112):
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
                default: '112'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = HT16K33Full(connection)

    def clear(self):
        """
        label:
            en: '%1 clear'
        """
        self._driver.clear()

    def set_pixel(self, com: int, row: int, state: int):
        """
        label:
            en: '%1 set pixel com %2 row %3 state %4'
        params:
            com:
                name: com
                type: int
                field: number
            row:
                name: row
                type: int
                field: number
            state:
                name: state
                type: int
                field: number
        """
        self._driver.set_pixel(com, row, bool(state))

    def show(self):
        """
        label:
            en: '%1 show'
        """
        self._driver.show()

    def set_brightness(self, level: int):
        """
        label:
            en: '%1 set brightness %2'
        params:
            level:
                name: level
                type: int
                field: number
        """
        self._driver.set_brightness(level)

    def display_on(self):
        """
        label:
            en: '%1 display on'
        """
        self._driver.display_on()

    def display_off(self):
        """
        label:
            en: '%1 display off'
        """
        self._driver.display_off()

    def set_blink(self, frequency: float):
        """
        label:
            en: '%1 set blink frequency (Hz) %2'
        params:
            frequency:
                name: frequency
                type: float
                field: number
        """
        self._driver.set_blink(frequency)

    def standby(self):
        """
        label:
            en: '%1 standby'
        """
        self._driver.standby()

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()

    def set_int_output(self, enable: int, active_high: int):
        """
        label:
            en: '%1 set INT output enable %2 active high %3'
        params:
            enable:
                name: enable
                type: int
                field: number
            active_high:
                name: active_high
                type: int
                field: number
        """
        self._driver.set_int_output(bool(enable), bool(active_high))

    def read_keys(self) -> int:
        """
        label:
            en: '%1 read keys'
        """

        return self._driver.read_keys()

    def key_pressed(self) -> bool:
        """
        label:
            en: '%1 key pressed'
        """

        return self._driver.key_pressed()

    def get_pixel(self, com: int, row: int) -> bool:
        """
        label:
            en: '%1 get pixel com %2 row %3'
        params:
            com:
                name: com
                type: int
                field: number
            row:
                name: row
                type: int
                field: number
        """

        return self._driver.get_pixel(com, row)
