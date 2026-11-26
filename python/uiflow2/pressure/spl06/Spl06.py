"""
file     Spl06
time     2026-11-26
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.spl06 import SPL06Full


class Spl06:
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

    def __init__(self, bus: int = 0, address: int = 119):
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
                default: '119'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = SPL06Full(connection)
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
            en: '%1 altitude (m, 101325 Pa sea level)'
        """
        return self._driver.altitude(101325.0)

    def set_pressure_config(self, oversampling: int = 16, rate: int = 1):
        """
        label:
            en: '%1 pressure oversampling (1-128) %2 rate (Hz) %3'
        params:
            oversampling:
                name: oversampling
                type: int
                default: '16'
                field: number
            rate:
                name: rate
                type: int
                default: '1'
                field: number
        """
        self._driver.set_pressure_config(oversampling, rate)

    def set_temperature_config(self, oversampling: int = 1, rate: int = 1):
        """
        label:
            en: '%1 temperature oversampling (1-128) %2 rate (Hz) %3'
        params:
            oversampling:
                name: oversampling
                type: int
                default: '1'
                field: number
            rate:
                name: rate
                type: int
                default: '1'
                field: number
        """
        self._driver.set_temperature_config(oversampling, rate)

    def start_continuous(self):
        """
        label:
            en: '%1 start continuous (pressure and temperature)'
        """
        self._driver.start_continuous()

    def stop(self):
        """
        label:
            en: '%1 stop continuous'
        """
        self._driver.stop()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
