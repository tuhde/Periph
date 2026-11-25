"""
file     SHT31
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.sht31 import SHT31Full


class SHT31:
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
        self._driver = SHT31Full(connection)

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

    def set_repeatability(self, level: int = 2):
        """
        label:
            en: '%1 repeatability (0=low 1=medium 2=high) %2'
        params:
            level:
                name: level
                type: int
                default: '2'
                field: number
                max: '2'
                min: '0'
        """
        self._driver.set_repeatability(['low', 'medium', 'high'][level])

    def set_heater(self, on: int = 0):
        """
        label:
            en: '%1 heater (1=on 0=off) %2'
        params:
            on:
                name: on
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_heater(bool(on))

    def status(self) -> int:
        """
        label:
            en: '%1 status register'
        """
        return self._driver.read_status()

    def clear_status(self):
        """
        label:
            en: '%1 clear status'
        """
        self._driver.clear_status()

    def start_periodic(self, rate: int = 20):
        """
        label:
            en: '%1 start periodic mode, rate in tenths of mps (5, 10, 20, 40, 100) %2'
        params:
            rate:
                name: rate
                type: int
                default: '20'
                field: number
                max: '100'
                min: '5'
        """
        self._driver.start_periodic(rate / 10.0)

    def stop_periodic(self):
        """
        label:
            en: '%1 stop periodic mode'
        """
        self._driver.stop_periodic()

    def fetch_temperature(self) -> float:
        """
        label:
            en: '%1 periodic temperature (°C, -999 if no data)'
        """
        return (lambda r: r[0] if r else -999.0)(self._driver.fetch())

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()
