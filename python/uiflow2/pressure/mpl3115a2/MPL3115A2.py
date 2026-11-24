"""
file     MPL3115A2
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl3115a2 import MPL3115A2Full


class MPL3115A2:
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
        self._driver = MPL3115A2Full(connection)
        self._driver.init()

    def pressure(self) -> float:
        """
        label:
            en: '%1 pressure (Pa)'
        """
        return self._driver.read_pressure()

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def altitude(self) -> float:
        """
        label:
            en: '%1 altitude (m)'
        """
        return self._driver.read_altitude()

    def set_oversample(self, ratio: int = 128):
        """
        label:
            en: '%1 oversample ratio (1-128) %2'
        params:
            ratio:
                name: ratio
                type: int
                default: '128'
                field: number
        """
        self._driver.set_oversample(ratio)

    def set_sea_level_pressure(self, pa: int = 101325):
        """
        label:
            en: '%1 sea-level pressure (Pa) %2'
        params:
            pa:
                name: pa
                type: int
                default: '101325'
                field: number
        """
        self._driver.set_sea_level_pressure(pa)

    def start_continuous(self, step_exp: int = 0):
        """
        label:
            en: '%1 start continuous, step 2^n s, n = %2'
        params:
            step_exp:
                name: step_exp
                type: int
                default: '0'
                field: number
        """
        self._driver.start_continuous(step_exp)

    def stop_continuous(self):
        """
        label:
            en: '%1 stop continuous'
        """
        self._driver.stop_continuous()
