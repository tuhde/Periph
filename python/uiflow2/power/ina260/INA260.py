"""
file     INA260
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina260 import INA260Full


class INA260:
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

    def __init__(self, bus: int = 0, address: int = 64):
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
                default: '64'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = INA260Full(connection)

    def read_voltage(self) -> float:
        """
        label:
            en: '%1 bus voltage (V)'
        """
        return self._driver.voltage()

    def read_current(self) -> float:
        """
        label:
            en: '%1 current (A)'
        """
        return self._driver.current()

    def read_power(self) -> float:
        """
        label:
            en: '%1 power (W)'
        """
        return self._driver.power()
