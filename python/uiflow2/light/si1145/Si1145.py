"""
file     Si1145
time     2026-11-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1145 import Si1145Full


class Si1145:
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
        self._driver = Si1145Full(connection)
        self._driver.init()

    def proximity(self) -> int:
        """
        label:
            en: '%1 proximity (counts)'
        """
        return self._driver.read()['proximity']

    def uv_index(self) -> float:
        """
        label:
            en: '%1 UV index'
        """
        return self._driver.read()['uv_index']

    def visible(self) -> int:
        """
        label:
            en: '%1 visible light (counts)'
        """
        return self._driver.read()['visible']

    def infrared(self) -> int:
        """
        label:
            en: '%1 infrared light (counts)'
        """
        return self._driver.read()['infrared']

    def read_proximity(self, channel: int = 1) -> int:
        """
        label:
            en: '%1 proximity channel (1-3) %2'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
        """
        return self._driver.read_proximity(channel)

    def read_temperature_raw(self) -> int:
        """
        label:
            en: '%1 die temperature (raw)'
        """
        return self._driver.read_temperature_raw()

    def read_vdd_raw(self) -> int:
        """
        label:
            en: '%1 VDD (raw)'
        """
        return self._driver.read_vdd_raw()

    def set_channels(self, uv: int = 1, visible: int = 1, infrared: int = 1, ps1: int = 1):
        """
        label:
            en: '%1 channels UV %2 visible %3 infrared %4 proximity (1/0) %5'
        params:
            uv:
                name: uv
                type: int
                default: '1'
                field: number
            visible:
                name: visible
                type: int
                default: '1'
                field: number
            infrared:
                name: infrared
                type: int
                default: '1'
                field: number
            ps1:
                name: ps1
                type: int
                default: '1'
                field: number
        """
        self._driver.set_channels(bool(uv), bool(visible), bool(infrared), bool(ps1))

    def set_led_current(self, led: int = 1, level: int = 3):
        """
        label:
            en: '%1 LED %2 current level (0-15) %3'
        params:
            led:
                name: led
                type: int
                default: '1'
                field: number
            level:
                name: level
                type: int
                default: '3'
                field: number
        """
        self._driver.set_led_current(led, level)

    def set_ps_photodiode(self, channel: int = 1, large: int = 1):
        """
        label:
            en: '%1 proximity channel %2 large photodiode (1/0) %3'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
            large:
                name: large
                type: int
                default: '1'
                field: number
        """
        self._driver.set_ps_photodiode(channel, bool(large))

    def set_gain(self, visible: int = 0, infrared: int = 0, proximity: int = 0):
        """
        label:
            en: '%1 gain (0-7) visible %2 infrared %3 proximity %4'
        params:
            visible:
                name: visible
                type: int
                default: '0'
                field: number
            infrared:
                name: infrared
                type: int
                default: '0'
                field: number
            proximity:
                name: proximity
                type: int
                default: '0'
                field: number
        """
        self._driver.set_gain(visible, infrared, proximity)

    def set_high_range(self, visible: int = 0, infrared: int = 0, proximity: int = 0):
        """
        label:
            en: '%1 high range (1/0) visible %2 infrared %3 proximity %4'
        params:
            visible:
                name: visible
                type: int
                default: '0'
                field: number
            infrared:
                name: infrared
                type: int
                default: '0'
                field: number
            proximity:
                name: proximity
                type: int
                default: '0'
                field: number
        """
        self._driver.set_high_range(bool(visible), bool(infrared), bool(proximity))

    def start_autonomous(self, interval: float = 0.1):
        """
        label:
            en: '%1 start autonomous interval (s) %2'
        params:
            interval:
                name: interval
                type: float
                default: '0.1'
                field: number
        """
        self._driver.start_autonomous(interval)

    def pause(self):
        """
        label:
            en: '%1 pause'
        """
        self._driver.pause()

    def force(self):
        """
        label:
            en: '%1 force measurement'
        """
        self._driver.force()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt sources'
        """
        return self._driver.poll_interrupt()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
