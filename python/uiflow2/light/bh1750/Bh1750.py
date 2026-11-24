"""
file     Bh1750
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.bh1750 import BH1750Full


class Bh1750:
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

    def __init__(self, bus: int = 0, address: int = 35):
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
                default: '35'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = BH1750Full(connection)
        self._driver.init()

    def lux(self) -> float:
        """
        label:
            en: '%1 illuminance (lx)'
        """
        return self._driver.read()

    def lux_mode(self, mode: int = 0) -> float:
        """
        label:
            en: '%1 illuminance (lx) mode (0=H 1=H2 2=L) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
        """
        return self._driver.measure_once(mode)

    def set_measurement_time(self, mtreg: int = 69):
        """
        label:
            en: '%1 measurement time register (31-254) %2'
        params:
            mtreg:
                name: mtreg
                type: int
                default: '69'
                field: number
                max: '254'
                min: '31'
        """
        self._driver.set_measurement_time(mtreg)

    def measurement_time_register(self) -> int:
        """
        label:
            en: '%1 measurement time register'
        """
        return self._driver.get_measurement_time_register()

    def set_sensitivity(self, factor: float = 1.0):
        """
        label:
            en: '%1 sensitivity factor (0.45-3.68) %2'
        params:
            factor:
                name: factor
                type: float
                default: '1.0'
                field: number
                max: '3.68'
                min: '0.45'
        """
        self._driver.set_sensitivity(factor)

    def power_down(self):
        """
        label:
            en: '%1 power down'
        """
        self._driver.power_down()

    def power_on(self):
        """
        label:
            en: '%1 power on'
        """
        self._driver.power_on()

    def reset(self):
        """
        label:
            en: '%1 reset data register'
        """
        self._driver.reset()
