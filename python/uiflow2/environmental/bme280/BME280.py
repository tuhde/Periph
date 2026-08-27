"""
file     BME280
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.bme280 import BME280Full


class BME280:
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
        self._driver = BME280Full(connection)

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.temperature()

    def pressure(self) -> float:
        """
        label:
            en: '%1 pressure (hPa)'
        """
        return self._driver.pressure()

    def humidity(self) -> float:
        """
        label:
            en: '%1 humidity (%)'
        """
        return self._driver.humidity()
