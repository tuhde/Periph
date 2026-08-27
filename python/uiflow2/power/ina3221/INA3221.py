"""
file     INA3221
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina3221 import INA3221Full


class INA3221:
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

    def __init__(self, bus: int = 0, address: int = 64, r_shunt: float = 0.1):
        """
        label:
            en: '%1 init bus %2 address %3 r_shunt %4'
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
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = INA3221Full(connection, r_shunt)

    def read_voltage(self, channel: int) -> float:
        """
        label:
            en: '%1 bus voltage (V) channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        return self._driver.voltage(channel)

    def read_current(self, channel: int) -> float:
        """
        label:
            en: '%1 current (A) channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        return self._driver.current(channel)

    def read_power(self, channel: int) -> float:
        """
        label:
            en: '%1 power (W) channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        return self._driver.power(channel)
