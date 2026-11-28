"""
file     TSL2561
time     2026-11-28
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.tsl2561 import TSL2561Full


class TSL2561:
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

    def __init__(self, bus: int = 0, address: int = 57):
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
                default: '57'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = TSL2561Full(connection)

    def read_lux(self) -> float:
        """
        label:
            en: '%1 illuminance (lx)'
        """
        return self._driver.read_lux()

    def auto_range(self) -> float:
        """
        label:
            en: '%1 auto-ranged illuminance (lx)'
        """
        return self._driver.auto_range()

    def ch0(self) -> int:
        """
        label:
            en: '%1 channel 0 count (visible + IR)'
        """
        return self._driver.read_raw()[0]

    def ch1(self) -> int:
        """
        label:
            en: '%1 channel 1 count (IR)'
        """
        return self._driver.read_raw()[1]

    def set_gain(self, gain: int):
        """
        label:
            en: '%1 set gain (1 or 16) %2'
        params:
            gain:
                name: gain
                type: int
                default: '1'
                field: number
                max: '16'
                min: '1'
        """
        self._driver.set_gain(gain)

    def get_gain(self) -> int:
        """
        label:
            en: '%1 gain'
        """
        return self._driver.get_gain()

    def set_integration_time(self, ms: int):
        """
        label:
            en: '%1 set integration time (ms) — 14, 101 or 402 %2'
        params:
            ms:
                name: ms
                type: int
                default: '402'
                field: number
                max: '402'
                min: '14'
        """
        self._driver.set_integration_time(ms)

    def get_integration_time(self) -> int:
        """
        label:
            en: '%1 integration time (ms)'
        """
        return self._driver.get_integration_time()

    def set_package(self, package: str):
        """
        label:
            en: '%1 set package — T, FN, CL or CS %2'
        params:
            package:
                name: package
                type: str
        """
        self._driver.set_package(package)

    def power_up(self):
        """
        label:
            en: '%1 power up'
        """
        self._driver.power_up()

    def power_down(self):
        """
        label:
            en: '%1 power down'
        """
        self._driver.power_down()

    def is_powered(self) -> bool:
        """
        label:
            en: '%1 powered'
        """
        return self._driver.is_powered()

    def start_manual(self):
        """
        label:
            en: '%1 start manual integration'
        """
        self._driver.start_manual()

    def stop_manual(self):
        """
        label:
            en: '%1 stop manual integration'
        """
        self._driver.stop_manual()

    def set_thresholds(self, low: int, high: int):
        """
        label:
            en: '%1 interrupt window (ch0 counts) low %2 high %3'
        params:
            low:
                name: low
                type: int
                default: '0'
                field: number
                max: '65535'
                min: '0'
            high:
                name: high
                type: int
                default: '65535'
                field: number
                max: '65535'
                min: '0'
        """
        self._driver.set_thresholds(low, high)

    def get_low_threshold(self) -> int:
        """
        label:
            en: '%1 low threshold (counts)'
        """
        return self._driver.get_thresholds()[0]

    def get_high_threshold(self) -> int:
        """
        label:
            en: '%1 high threshold (counts)'
        """
        return self._driver.get_thresholds()[1]

    def configure_interrupt(self, mode: int, persist: int):
        """
        label:
            en: '%1 interrupt mode (0 off, 1 level, 2 SMBAlert) %2 persist %3'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
            persist:
                name: persist
                type: int
                default: '0'
                field: number
                max: '15'
                min: '0'
        """
        self._driver.configure_interrupt(mode, persist)

    def poll_interrupt(self) -> bool:
        """
        label:
            en: '%1 ch0 outside window'
        """
        return self._driver.poll_interrupt()

    def clear_interrupt(self):
        """
        label:
            en: '%1 clear interrupt'
        """
        self._driver.clear_interrupt()

    def read_id(self) -> int:
        """
        label:
            en: '%1 ID register'
        """
        return self._driver.read_id()
