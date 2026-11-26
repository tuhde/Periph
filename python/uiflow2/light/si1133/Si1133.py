"""
file     Si1133
time     2026-11-26
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1133 import Si1133Full


class Si1133:
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

    def __init__(self, bus: int = 0, address: int = 85):
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
                default: '85'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = Si1133Full(connection)
        self._driver.init()

    def uv(self) -> int:
        """
        label:
            en: '%1 UV (counts)'
        """
        return self._driver.read()['uv']

    def white(self) -> int:
        """
        label:
            en: '%1 white light (counts)'
        """
        return self._driver.read()['white']

    def read_ir(self, photodiode: int = 0) -> int:
        """
        label:
            en: '%1 IR (counts) photodiode (0-2) %2'
        params:
            photodiode:
                name: photodiode
                type: int
                default: '0'
                field: number
        """
        return self._driver.read_ir(photodiode)

    def configure_channel(self, channel: int = 2, adcmux: int = 11, hw_gain: int = 7, sw_gain: int = 0):
        """
        label:
            en: '%1 channel %2 photodiode mux %3 hw gain (0-11) %4 sw gain (0-7) %5'
        params:
            channel:
                name: channel
                type: int
                default: '2'
                field: number
            adcmux:
                name: adcmux
                type: int
                default: '11'
                field: number
            hw_gain:
                name: hw_gain
                type: int
                default: '7'
                field: number
            sw_gain:
                name: sw_gain
                type: int
                default: '0'
                field: number
        """
        self._driver.configure_channel(channel, adcmux, hw_gain, sw_gain)

    def enable_channels(self, mask: int = 3):
        """
        label:
            en: '%1 enable channels mask %2'
        params:
            mask:
                name: mask
                type: int
                default: '3'
                field: number
        """
        self._driver.enable_channels(mask)

    def read_channel(self, index: int = 0) -> int:
        """
        label:
            en: '%1 channel result index %2'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
        """
        return self._driver.read_channels()[index]

    def force(self):
        """
        label:
            en: '%1 force measurement'
        """
        self._driver.force()

    def start_autonomous(self, rate: float = 1.0, count0: int = 1, count1: int = 1, count2: int = 1):
        """
        label:
            en: '%1 start autonomous rate (s) %2 counters %3 %4 %5'
        params:
            rate:
                name: rate
                type: float
                default: '1.0'
                field: number
            count0:
                name: count0
                type: int
                default: '1'
                field: number
            count1:
                name: count1
                type: int
                default: '1'
                field: number
            count2:
                name: count2
                type: int
                default: '1'
                field: number
        """
        self._driver.start_autonomous(rate, (count0, count1, count2))

    def pause(self):
        """
        label:
            en: '%1 pause'
        """
        self._driver.pause()

    def set_burst(self, count: int = 0):
        """
        label:
            en: '%1 burst readings (0 = off) %2'
        params:
            count:
                name: count
                type: int
                default: '0'
                field: number
        """
        self._driver.set_burst(count)

    def set_threshold(self, index: int = 0, value: int = 1000):
        """
        label:
            en: '%1 threshold index (0-2) %2 value %3'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
            value:
                name: value
                type: int
                default: '1000'
                field: number
        """
        self._driver.set_threshold(index, value)

    def set_channel_threshold(self, channel: int = 0, index: int = 1):
        """
        label:
            en: '%1 channel %2 threshold select (0 = off, 1-3) %3'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
            index:
                name: index
                type: int
                default: '1'
                field: number
        """
        self._driver.set_channel_threshold(channel, index)

    def data_ready(self) -> int:
        """
        label:
            en: '%1 data ready mask'
        """
        return self._driver.data_ready()

    def read_parameter(self, address: int = 1) -> int:
        """
        label:
            en: '%1 parameter %2'
        params:
            address:
                name: address
                type: int
                default: '1'
                field: number
        """
        return self._driver.read_parameter(address)

    def write_parameter(self, address: int = 1, value: int = 3):
        """
        label:
            en: '%1 set parameter %2 value %3'
        params:
            address:
                name: address
                type: int
                default: '1'
                field: number
            value:
                name: value
                type: int
                default: '3'
                field: number
        """
        self._driver.write_parameter(address, value)

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
