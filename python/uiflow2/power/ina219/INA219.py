"""
file     INA219
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina219 import INA219Full


class INA219:
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

    def __init__(self, bus: int = 0, address: int = 64, r_shunt: float = 0.1, max_current: float = 2.0):
        """
        label:
            en: '%1 init bus %2 address %3 r_shunt %4 max_current %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '64'
                field: number
            r_shunt:
                name: r_shunt
                type: float
                default: '0.1'
                field: number
            max_current:
                name: max_current
                type: float
                default: '2'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = INA219Full(connection, r_shunt, max_current)

    def voltage(self) -> float:
        """
        label:
            en: '%1 bus voltage (V)'
        """
        return self._driver.voltage()

    def shunt_voltage(self) -> float:
        """
        label:
            en: '%1 shunt voltage (V)'
        """
        return self._driver.shunt_voltage()

    def current(self) -> float:
        """
        label:
            en: '%1 current (A)'
        """
        return self._driver.current()

    def power(self) -> float:
        """
        label:
            en: '%1 power (W)'
        """
        return self._driver.power()
