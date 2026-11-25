"""
file     MPL115A2
time     2026-11-19
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl115a2 import MPL115A2Full


class MPL115A2:
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

    def __init__(self, bus: int = 0, address: int = 96):
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
                default: '96'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = MPL115A2Full(connection)

    def read_pressure(self) -> float:
        """
        label:
            en: '%1 pressure (Pa)'
        """
        return self._driver.read_pressure()

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C, approximate)'
        """
        return self._driver.read_temperature()

    def read_pressure_hpa(self) -> float:
        """
        label:
            en: '%1 pressure (hPa)'
        """
        return self._driver.pressure_hpa()

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

    def compensate(self, padc: int, tadc: int) -> float:
        """
        label:
            en: '%1 compensate pressure (Pa) from Padc %2 Tadc %3'
        params:
            padc:
                name: padc
                type: int
                field: number
            tadc:
                name: tadc
                type: int
                field: number
        """
        return self._driver.compensate(padc, tadc)

    def reload_coefficients(self):
        """
        label:
            en: '%1 reload coefficients'
        """
        self._driver.reload_coefficients()
