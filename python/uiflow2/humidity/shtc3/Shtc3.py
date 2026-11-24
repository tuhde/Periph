"""
file     Shtc3
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.shtc3 import SHTC3Full


class Shtc3:
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

    def __init__(self, bus: int = 0, address: int = 112):
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
                default: '112'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = SHTC3Full(connection)
        self._driver.init()

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def humidity(self) -> float:
        """
        label:
            en: '%1 relative humidity (%RH)'
        """
        return self._driver.read_humidity()

    def dew_point(self) -> float:
        """
        label:
            en: '%1 dew point (°C)'
        """
        return self._driver.dew_point()

    def read_id(self) -> int:
        """
        label:
            en: '%1 ID word'
        """
        return self._driver.read_id()

    def set_low_power(self, enabled: int = 0):
        """
        label:
            en: '%1 low-power mode (1=on 0=off) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_low_power(bool(enabled))

    def set_sleep_between(self, enabled: int = 1):
        """
        label:
            en: '%1 sleep between reads (1=on 0=off) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_sleep_between(bool(enabled))

    def wake(self):
        """
        label:
            en: '%1 wake up'
        """
        self._driver.wake()

    def sleep(self):
        """
        label:
            en: '%1 sleep'
        """
        self._driver.sleep()

    def software_reset(self):
        """
        label:
            en: '%1 software reset'
        """
        self._driver.soft_reset()
