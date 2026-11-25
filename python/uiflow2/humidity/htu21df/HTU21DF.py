"""
file     HTU21DF
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu21df import HTU21DFFull


class HTU21DF:
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

    def __init__(self, bus: int = 0, address: int = 64):
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
                default: '64'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = HTU21DFFull(connection)
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

    def humidity_compensated(self) -> float:
        """
        label:
            en: '%1 compensated humidity (%RH)'
        """
        return self._driver.read_humidity_compensated()

    def dew_point(self) -> float:
        """
        label:
            en: '%1 dew point (°C)'
        """
        return self._driver.dew_point()

    def set_resolution(self, level: int = 0):
        """
        label:
            en: '%1 resolution (0=12/14 1=8/12 2=10/13 3=11/11 bit RH/T) %2'
        params:
            level:
                name: level
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_resolution(level)

    def set_heater(self, enabled: int = 0):
        """
        label:
            en: '%1 heater (1=on 0=off) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_heater(bool(enabled))

    def heater_on(self) -> int:
        """
        label:
            en: '%1 heater on (1=yes 0=no)'
        """
        return int(self._driver.heater())

    def battery_low(self) -> int:
        """
        label:
            en: '%1 battery low (1=yes 0=no)'
        """
        return int(self._driver.battery_low())

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()
