"""
file     ENS160
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.ens160 import ENS160Full


class ENS160:
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
        self._driver = ENS160Full(connection)

    def read_aqi(self) -> int:
        """
        label:
            en: '%1 air quality index'
        """
        return self._driver.read_aqi()

    def read_tvoc(self) -> float:
        """
        label:
            en: '%1 TVOC (ppb)'
        """
        return self._driver.read_tvoc()

    def read_eco2(self) -> float:
        """
        label:
            en: '%1 eCO2 (ppm)'
        """
        return self._driver.read_eco2()
