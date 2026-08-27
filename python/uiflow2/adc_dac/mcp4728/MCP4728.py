"""
file     MCP4728
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4728 import MCP4728Full


class MCP4728:
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
        self._driver = MCP4728Full(connection)

    def set_voltage(self, channel: int, fraction: float):
        """
        label:
            en: '%1 set channel output (fraction of V_DD) channel %2 fraction %3'
        params:
            channel:
                name: channel
                type: int
                field: number
            fraction:
                name: fraction
                type: float
                field: number
        """
        self._driver.set_voltage(channel, fraction)

    def set_raw(self, channel: int, code: int):
        """
        label:
            en: '%1 set channel raw 12-bit code channel %2 code %3'
        params:
            channel:
                name: channel
                type: int
                field: number
            code:
                name: code
                type: int
                field: number
        """
        self._driver.set_raw(channel, code)

    def set_all(self, frac_a: float, frac_b: float, frac_c: float, frac_d: float):
        """
        label:
            en: '%1 set all channels (fractions of V_DD) frac_a %2 frac_b %3 frac_c %4 frac_d %5'
        params:
            frac_a:
                name: frac_a
                type: float
                field: number
            frac_b:
                name: frac_b
                type: float
                field: number
            frac_c:
                name: frac_c
                type: float
                field: number
            frac_d:
                name: frac_d
                type: float
                field: number
        """
        self._driver.set_all([frac_a, frac_b, frac_c, frac_d])
