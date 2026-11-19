"""
file     HS30xx
time     2026-11-19
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs30xx import HS30xxFull


class HS30xx:
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

    def __init__(self, bus: int = 0, address: int = 68):
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
                default: '68'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = HS30xxFull(connection)

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def humidity(self) -> float:
        """
        label:
            en: '%1 relative humidity (%RH)'
        """
        return self._driver.read_humidity()

    def dew_point(self) -> float:
        """
        label:
            en: '%1 dew point (°C)'
        """
        return self._driver.dew_point()

    def raw_humidity(self) -> int:
        """
        label:
            en: '%1 raw humidity (14 bit)'
        """
        return self._driver.read_raw()[0]

    def raw_temperature(self) -> int:
        """
        label:
            en: '%1 raw temperature (14 bit)'
        """
        return self._driver.read_raw()[1]

    def set_wait(self, ms: int = 40):
        """
        label:
            en: '%1 measurement wait (ms) %2'
        params:
            ms:
                name: ms
                type: int
                default: '40'
                field: number
                max: '1000'
                min: '1'
        """
        self._driver.set_measurement_wait(ms)
