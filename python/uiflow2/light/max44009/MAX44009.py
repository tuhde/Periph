"""
file     MAX44009
time     2026-11-13
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.max44009 import MAX44009Full


class MAX44009:
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

    def __init__(self, bus: int = 0, address: int = 74):
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
                default: '74'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = MAX44009Full(connection)

    def lux(self) -> float:
        """
        label:
            en: '%1 illuminance (lx)'
        """
        return self._driver.lux()

    def is_overrange(self) -> bool:
        """
        label:
            en: '%1 overrange'
        """
        return self._driver.is_overrange()

    def resolution(self) -> float:
        """
        label:
            en: '%1 resolution (lx per LSB)'
        """
        return self._driver.resolution()

    def set_continuous(self, continuous: int):
        """
        label:
            en: '%1 continuous mode (1 = yes) %2'
        params:
            continuous:
                name: continuous
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_continuous(bool(continuous))

    def set_automatic(self):
        """
        label:
            en: '%1 automatic range'
        """
        self._driver.set_automatic()

    def set_manual(self, integration_ms: float, divide_by_8: int):
        """
        label:
            en: '%1 manual range integration (ms) %2 divide by 8 (1 = yes) %3'
        params:
            integration_ms:
                name: integration_ms
                type: float
                default: '100.0'
                field: number
            divide_by_8:
                name: divide_by_8
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_manual(integration_ms, bool(divide_by_8))

    def set_thresholds(self, low_lux: float, high_lux: float):
        """
        label:
            en: '%1 interrupt window low (lx) %2 high (lx) %3'
        params:
            low_lux:
                name: low_lux
                type: float
                default: '50.0'
                field: number
            high_lux:
                name: high_lux
                type: float
                default: '500.0'
                field: number
        """
        self._driver.set_thresholds(low_lux, high_lux)

    def get_low_threshold(self) -> float:
        """
        label:
            en: '%1 lower threshold (lx)'
        """
        return self._driver.get_thresholds()[0]

    def get_high_threshold(self) -> float:
        """
        label:
            en: '%1 upper threshold (lx)'
        """
        return self._driver.get_thresholds()[1]

    def set_threshold_delay(self, seconds: float):
        """
        label:
            en: '%1 threshold delay (s) %2'
        params:
            seconds:
                name: seconds
                type: float
                default: '2.0'
                field: number
        """
        self._driver.set_threshold_delay(seconds)

    def get_threshold_delay(self) -> float:
        """
        label:
            en: '%1 threshold delay (s)'
        """
        return self._driver.get_threshold_delay()

    def enable_interrupt(self):
        """
        label:
            en: '%1 enable interrupt'
        """
        self._driver.enable_interrupt()

    def disable_interrupt(self):
        """
        label:
            en: '%1 disable interrupt'
        """
        self._driver.disable_interrupt()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status'
        """
        return self._driver.poll_interrupt()
