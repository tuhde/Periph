"""
file     BMP280
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp280 import BMP280Full


class BMP280:
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

    def __init__(self, bus: int = 0, address: int = 118):
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
                default: '118'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = BMP280Full(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.temperature()

    def read_pressure(self) -> float:
        """
        label:
            en: '%1 pressure (hPa)'
        """
        return self._driver.pressure()

    def read_altitude(self, sea_level_hpa: float) -> float:
        """
        label:
            en: '%1 altitude (m), sea-level pressure (hPa) sea_level_hpa %2'
        params:
            sea_level_hpa:
                name: sea_level_hpa
                type: float
                field: number
        """
        return self._driver.altitude(sea_level_hpa)
