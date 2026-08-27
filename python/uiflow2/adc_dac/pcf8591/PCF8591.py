"""
file     PCF8591
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.pcf8591 import PCF8591Full


class PCF8591:
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

    def __init__(self, bus: int = 0, address: int = 72):
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
                default: '72'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCF8591Full(connection)

    def read_channel(self, channel: int) -> int:
        """
        label:
            en: '%1 read channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        return self._driver.read_channel(channel)

    def read_channel_voltage(self, channel: int, vref: float, vagnd: float) -> float:
        """
        label:
            en: '%1 read channel voltage channel %2 vref %3 vagnd %4'
        params:
            channel:
                name: channel
                type: int
                field: number
            vref:
                name: vref
                type: float
                field: number
            vagnd:
                name: vagnd
                type: float
                field: number
        """
        return self._driver.read_channel_voltage(channel, vref, vagnd)

    def set_dac_voltage(self, fraction: float):
        """
        label:
            en: '%1 set DAC output (fraction of VREF-VAGND) fraction %2'
        params:
            fraction:
                name: fraction
                type: float
                field: number
        """
        self._driver.set_dac_voltage(fraction)
