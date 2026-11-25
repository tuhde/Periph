"""
file     BMA180
time     2026-10-10
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.accelerometer.bma180 import BMA180Full


class BMA180:
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

    def __init__(self, bus: int = 0, address: int = 64):
        """
        label:
            en: '%1 init I2C bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            address:
                name: address
                type: int
                default: '64'
                field: number
                min: '0'
                max: '127'
        """
        self._driver = BMA180Full(I2CConnection(address, bus=bus))

    def x(self) -> float:
        """
        label:
            en: '%1 acceleration X (g)'
        """
        return self._driver.read()[0]

    def y(self) -> float:
        """
        label:
            en: '%1 acceleration Y (g)'
        """
        return self._driver.read()[1]

    def z(self) -> float:
        """
        label:
            en: '%1 acceleration Z (g)'
        """
        return self._driver.read()[2]