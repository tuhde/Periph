"""
file     Si1132
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1132 import Si1132Full


class Si1132:
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
        self._driver = Si1132Full(connection)

    def read_uv_index(self) -> float:
        """
        label:
            en: '%1 UV index'
        """
        return self._driver.read_uv_index()

    def read_visible(self) -> int:
        """
        label:
            en: '%1 visible count'
        """
        return self._driver.read_visible()

    def read_infrared(self) -> int:
        """
        label:
            en: '%1 infrared count'
        """
        return self._driver.read_infrared()

    def read_temperature_raw(self) -> int:
        """
        label:
            en: '%1 raw die temperature'
        """
        return self._driver.read_temperature_raw()

    def read_vdd_raw(self) -> int:
        """
        label:
            en: '%1 raw supply voltage'
        """
        return self._driver.read_vdd_raw()

    def set_channels(self, uv: int = 1, visible: int = 1, infrared: int = 1):
        """
        label:
            en: '%1 set channels UV %2 visible %3 infrared %4 (1 = on)'
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
        """
        self._driver.set_channels(bool(uv), bool(visible), bool(infrared))

    def set_gain(self, visible: int = 0, infrared: int = 0):
        """
        label:
            en: '%1 set gain visible %2 infrared %3 (0-7)'
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
        """
        self._driver.set_gain(visible, infrared)

    def set_high_range(self, visible: int = 0, infrared: int = 0):
        """
        label:
            en: '%1 set high range visible %2 infrared %3 (1 = direct sunlight)'
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
        """
        self._driver.set_high_range(bool(visible), bool(infrared))

    def start_autonomous(self, interval_ms: int = 500):
        """
        label:
            en: '%1 start autonomous interval %2 ms (0 = forced mode)'
        params:
            interval_ms:
                name: interval_ms
                type: int
                default: '500'
                field: number
        """
        self._driver.start_autonomous(interval_ms / 1000.0)

    def pause(self):
        """
        label:
            en: '%1 pause autonomous mode'
        """
        self._driver.pause()

    def measurement_ready(self) -> int:
        """
        label:
            en: '%1 measurement ready'
        """
        return int(self._driver.measurement_ready())

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
