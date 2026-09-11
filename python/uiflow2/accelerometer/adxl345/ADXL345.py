"""
file     ADXL345
time     2026-09-09
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.accelerometer.adxl345 import ADXL345Full


class ADXL345:
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

    def __init__(self, bus: int = 0, address: int = 83):
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
                default: '83'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = ADXL345Full(connection)

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