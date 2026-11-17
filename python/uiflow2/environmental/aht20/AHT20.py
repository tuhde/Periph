"""
file     AHT20
time     2026-11-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht20 import AHT20Full


class AHT20:
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
                default: '56'
                field: number
                min: '0'
                max: '127'
        """
        self._driver = AHT20Full(I2CConnection(address, bus=bus))

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 read temperature (°C)'
        """
        return self._driver.read_temperature()

    def read_humidity(self) -> float:
        """
        label:
            en: '%1 read humidity (%)'
        """
        return self._driver.read_humidity()
