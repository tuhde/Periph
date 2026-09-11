"""
file     LPS22DF
time     2026-09-10
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps22df import LPS22DFFull


class LPS22DF:
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

    def __init__(self, bus: int = 0, address: int = 92):
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
                default: '92'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = LPS22DFFull(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.temperature()

    def read_pressure(self) -> float:
        """
        label:
            en: '%1 pressure (Pa)'
        """
        return self._driver.pressure()

    def read_altitude(self, sea_level_pa: float) -> float:
        """
        label:
            en: '%1 altitude (m), sea-level pressure (Pa) sea_level_pa %2'
        params:
            sea_level_pa:
                name: sea_level_pa
                type: float
                field: number
        """
        return self._driver.altitude(sea_level_pa)