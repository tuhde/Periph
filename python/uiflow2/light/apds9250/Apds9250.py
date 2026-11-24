"""
file     Apds9250
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.apds9250 import APDS9250Full


class Apds9250:
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

    def __init__(self, bus: int = 0, address: int = 82):
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
                default: '82'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = APDS9250Full(connection)

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

    def read_ir(self) -> int:
        """
        label:
            en: '%1 IR count'
        """
        return self._driver.read_ir()

    def data_ready(self) -> int:
        """
        label:
            en: '%1 data ready'
        """
        return int(self._driver.data_ready())

    def set_mode(self, colour: int = 1):
        """
        label:
            en: '%1 set colour mode %2 (1 = RGB+IR, 0 = ALS/IR)'
        params:
            colour:
                name: colour
                type: int
                default: '1'
                field: number
        """
        self._driver.set_mode(bool(colour))

    def enable(self):
        """
        label:
            en: '%1 enable'
        """
        self._driver.enable()

    def disable(self):
        """
        label:
            en: '%1 disable'
        """
        self._driver.disable()

    def set_resolution(self, bits: int = 18):
        """
        label:
            en: '%1 set resolution bits %2'
        params:
            bits:
                name: bits
                type: int
                default: '18'
                field: number
        """
        self._driver.set_resolution(bits)

    def set_measurement_rate(self, milliseconds: int = 100):
        """
        label:
            en: '%1 set measurement rate ms %2'
        params:
            milliseconds:
                name: milliseconds
                type: int
                default: '100'
                field: number
        """
        self._driver.set_measurement_rate(milliseconds)

    def set_gain(self, gain: int = 3):
        """
        label:
            en: '%1 set gain %2'
        params:
            gain:
                name: gain
                type: int
                default: '3'
                field: number
        """
        self._driver.set_gain(gain)

    def set_thresholds(self, low: int = 0, high: int = 1048575):
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
                default: '1048575'
                field: number
        """
        self._driver.set_thresholds(low, high)

    def set_interrupt_channel(self, channel: int = 1):
        """
        label:
            en: '%1 set interrupt channel %2 (0 IR, 1 green, 2 red, 3 blue)'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
        """
        self._driver.set_interrupt_channel(['ir', 'green', 'red', 'blue'][channel])

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

    def enable_interrupt(self, source: int = 1):
        """
        label:
            en: '%1 enable interrupt %2 (1 threshold, 2 variation)'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self):
        """
        label:
            en: '%1 disable interrupt'
        """
        self._driver.disable_interrupt()

    def status(self) -> int:
        """
        label:
            en: '%1 status byte'
        """
        return self._driver.status()

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()
