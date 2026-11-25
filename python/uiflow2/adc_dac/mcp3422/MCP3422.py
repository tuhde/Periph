"""
file     MCP3422
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp3422 import MCP3422Full


class MCP3422:
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

    def __init__(self, bus: int = 0, address: int = 104):
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
                default: '104'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = MCP3422Full(connection)

    def read_raw(self, channel: int) -> int:
        """
        label:
            en: '%1 raw code channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.read_raw(channel)

    def read_voltage(self, channel: int) -> float:
        """
        label:
            en: '%1 voltage (V) channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.read_voltage(channel)

    def set_resolution(self, bits: int):
        """
        label:
            en: '%1 resolution (12/14/16/18 bit) %2'
        params:
            bits:
                name: bits
                type: int
                default: '16'
                field: number
                max: '18'
                min: '12'
        """
        self._driver.set_resolution(bits)

    def get_resolution(self) -> int:
        """
        label:
            en: '%1 resolution (bit)'
        """
        return self._driver.get_resolution()

    def set_gain(self, gain: int):
        """
        label:
            en: '%1 gain (1/2/4/8) %2'
        params:
            gain:
                name: gain
                type: int
                default: '1'
                field: number
                max: '8'
                min: '1'
        """
        self._driver.set_gain(gain)

    def get_gain(self) -> int:
        """
        label:
            en: '%1 gain'
        """
        return self._driver.get_gain()

    def set_channel(self, channel: int):
        """
        label:
            en: '%1 continuous channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_channel(channel)

    def start_continuous(self, channel: int):
        """
        label:
            en: '%1 start continuous channel (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.start_continuous(channel)

    def stop_continuous(self):
        """
        label:
            en: '%1 stop continuous'
        """
        self._driver.stop_continuous()

    def is_continuous(self) -> bool:
        """
        label:
            en: '%1 continuous mode'
        """
        return self._driver.is_continuous()

    def read_continuous_raw(self) -> int:
        """
        label:
            en: '%1 continuous raw code'
        """
        return self._driver.read_continuous_raw()

    def read_continuous(self) -> float:
        """
        label:
            en: '%1 continuous voltage (V)'
        """
        return self._driver.read_continuous()

    def is_ready(self) -> bool:
        """
        label:
            en: '%1 result ready'
        """
        return self._driver.is_ready()

    def general_call_reset(self):
        """
        label:
            en: '%1 general call reset'
        """
        self._driver.general_call_reset()

    def lsb(self) -> float:
        """
        label:
            en: '%1 LSB (V)'
        """
        return self._driver.lsb()

    def full_scale(self) -> float:
        """
        label:
            en: '%1 full scale (V)'
        """
        return self._driver.full_scale()
