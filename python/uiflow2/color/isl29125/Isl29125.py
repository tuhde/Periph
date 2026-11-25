"""
file     Isl29125
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.color.isl29125 import ISL29125Full


class Isl29125:
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

    def __init__(self, bus: int = 0, address: int = 68):
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
                default: '68'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = ISL29125Full(connection)

    def read_red(self) -> int:
        """
        label:
            en: '%1 red count'
        """
        return self._driver.read_red()

    def read_green(self) -> int:
        """
        label:
            en: '%1 green count'
        """
        return self._driver.read_green()

    def read_blue(self) -> int:
        """
        label:
            en: '%1 blue count'
        """
        return self._driver.read_blue()

    def data_ready(self) -> int:
        """
        label:
            en: '%1 conversion done'
        """
        return int(self._driver.data_ready())

    def set_mode(self, mode: int = 5):
        """
        label:
            en: '%1 set mode %2 (0 power down, 1 green, 2 red, 3 blue, 4 standby, 5 RGB, 6 green+red, 7 green+blue)'
        params:
            mode:
                name: mode
                type: int
                default: '5'
                field: number
        """
        self._driver.set_mode(['power_down', 'green', 'red', 'blue', 'standby', 'rgb', 'green_red', 'green_blue'][mode])

    def set_range(self, lux: int = 10000):
        """
        label:
            en: '%1 set range lux %2'
        params:
            lux:
                name: lux
                type: int
                default: '10000'
                field: number
        """
        self._driver.set_range(lux)

    def set_resolution(self, bits: int = 16):
        """
        label:
            en: '%1 set resolution bits %2'
        params:
            bits:
                name: bits
                type: int
                default: '16'
                field: number
        """
        self._driver.set_resolution(bits)

    def set_sync(self, external: int = 0):
        """
        label:
            en: '%1 set external sync %2 (1 = INT starts conversion)'
        params:
            external:
                name: external
                type: int
                default: '0'
                field: number
        """
        self._driver.set_sync(bool(external))

    def set_ir_compensation(self, offset: int = 1, adjust: int = 63):
        """
        label:
            en: '%1 set IR compensation offset %2 adjust %3'
        params:
            offset:
                name: offset
                type: int
                default: '1'
                field: number
            adjust:
                name: adjust
                type: int
                default: '63'
                field: number
        """
        self._driver.set_ir_compensation(bool(offset), adjust)

    def set_thresholds(self, low: int = 0, high: int = 65535):
        """
        label:
            en: '%1 set thresholds low %2 high %3'
        params:
            low:
                name: low
                type: int
                default: '0'
                field: number
            high:
                name: high
                type: int
                default: '65535'
                field: number
        """
        self._driver.set_thresholds(low, high)

    def set_interrupt_channel(self, channel: int = 1):
        """
        label:
            en: '%1 set interrupt channel %2 (0 none, 1 green, 2 red, 3 blue)'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
        """
        self._driver.set_interrupt_channel(['none', 'green', 'red', 'blue'][channel])

    def set_persistence(self, count: int = 1):
        """
        label:
            en: '%1 set persistence %2'
        params:
            count:
                name: count
                type: int
                default: '1'
                field: number
        """
        self._driver.set_persistence(count)

    def enable_interrupt(self, source: int = 0):
        """
        label:
            en: '%1 enable interrupt %2 (1 conversion, 0 threshold)'
        params:
            source:
                name: source
                type: int
                default: '0'
                field: number
        """
        self._driver.enable_interrupt(2 if source else 1)

    def disable_interrupt(self, source: int = 0):
        """
        label:
            en: '%1 disable interrupt %2 (1 conversion, 0 threshold)'
        params:
            source:
                name: source
                type: int
                default: '0'
                field: number
        """
        self._driver.disable_interrupt(2 if source else 1)

    def status(self) -> int:
        """
        label:
            en: '%1 status byte'
        """
        return self._driver.status()

    def device_id(self) -> int:
        """
        label:
            en: '%1 device ID'
        """
        return self._driver.device_id()

    def soft_reset(self):
        """
        label:
            en: '%1 software reset'
        """
        self._driver.soft_reset()
