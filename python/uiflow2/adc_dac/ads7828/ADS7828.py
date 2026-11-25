"""
file     ADS7828
time     2026-11-11
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads7828 import ADS7828Full


class ADS7828:
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

    def __init__(self, bus: int = 0, address: int = 72, vref: float = 0.0):
        """
        label:
            en: '%1 init bus %2 address %3 external vref V (0 = internal 2.5 V) %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '72'
                field: number
            vref:
                name: vref
                type: float
                default: '0.0'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = ADS7828Full(connection, vref or None)

    def read_raw(self, channel: int) -> int:
        """
        label:
            en: '%1 read channel (0-7) raw code channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        return self._driver.read_raw(channel)

    def read_voltage(self, channel: int) -> float:
        """
        label:
            en: '%1 read channel (0-7) voltage V channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        return self._driver.read_voltage(channel)

    def read_differential(self, pair: int, inverted: int) -> float:
        """
        label:
            en: '%1 read differential pair (0-3) voltage V pair %2 inverted (0/1) %3'
        params:
            pair:
                name: pair
                type: int
                field: number
            inverted:
                name: inverted
                type: int
                field: number
        """
        return self._driver.read_differential(pair, bool(inverted))

    def read_signed_differential(self, pair: int) -> float:
        """
        label:
            en: '%1 read signed differential pair (0-3) V pair %2'
        params:
            pair:
                name: pair
                type: int
                field: number
        """
        return self._driver.read_signed_differential(pair)

    def set_reference(self, vref: float):
        """
        label:
            en: '%1 set reference V (0 = internal 2.5 V) vref %2'
        params:
            vref:
                name: vref
                type: float
                field: number
        """
        self._driver.set_reference(vref or None)

    def power_down(self):
        """
        label:
            en: '%1 power down'
        """
        self._driver.power_down()
