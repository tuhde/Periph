"""
file     MCP4725
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4725 import MCP4725Full


class MCP4725:
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
        self._driver = MCP4725Full(connection)

    def set_voltage(self, fraction: float):
        """
        label:
            en: '%1 set output (fraction of V_DD) fraction %2'
        params:
            fraction:
                name: fraction
                type: float
                field: number
        """
        self._driver.set_voltage(fraction)

    def set_raw(self, code: int):
        """
        label:
            en: '%1 set raw 12-bit code code %2'
        params:
            code:
                name: code
                type: int
                field: number
        """
        self._driver.set_raw(code)
