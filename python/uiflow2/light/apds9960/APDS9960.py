"""
file     APDS9960
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.apds9960 import APDS9960Full


class APDS9960:
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

    def __init__(self, bus: int = 0, address: int = 57):
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
                default: '57'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = APDS9960Full(connection)

    def color_clear(self) -> int:
        """
        label:
            en: '%1 clear channel'
        """
        return self._driver.color_clear()

    def color_red(self) -> int:
        """
        label:
            en: '%1 red channel'
        """
        return self._driver.color_red()

    def color_green(self) -> int:
        """
        label:
            en: '%1 green channel'
        """
        return self._driver.color_green()

    def color_blue(self) -> int:
        """
        label:
            en: '%1 blue channel'
        """
        return self._driver.color_blue()

    def enable_proximity(self, enabled: int):
        """
        label:
            en: '%1 enable (1) / disable (0) proximity enabled %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.enable_proximity(bool(enabled))

    def proximity(self) -> int:
        """
        label:
            en: '%1 proximity count (0-255)'
        """
        return self._driver.proximity()
