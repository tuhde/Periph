"""
file     Hts221
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hts221 import HTS221Full


class Hts221:
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

    def __init__(self, bus: int = 0, address: int = 95):
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
                default: '95'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = HTS221Full(connection)

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def humidity(self) -> float:
        """
        label:
            en: '%1 relative humidity (%rH)'
        """
        return self._driver.read_humidity()

    def dew_point(self) -> float:
        """
        label:
            en: '%1 dew point (°C)'
        """
        return self._driver.dew_point()

    def who_am_i(self) -> int:
        """
        label:
            en: '%1 WHO_AM_I'
        """
        return self._driver.who_am_i()

    def data_ready(self) -> int:
        """
        label:
            en: '%1 data ready (1=yes 0=no)'
        """
        return int(self._driver.data_ready())

    def set_output_data_rate(self, hz: float = 0):
        """
        label:
            en: '%1 output data rate (0, 1, 7, 12.5 Hz) %2'
        params:
            hz:
                name: hz
                type: float
                default: '0'
                field: number
                max: '13'
                min: '0'
        """
        self._driver.set_output_data_rate(hz)

    def set_averaging(self, temperature_samples: int = 16, humidity_samples: int = 32):
        """
        label:
            en: '%1 averaging T samples (2-256) %2 RH samples (4-512) %3'
        params:
            temperature_samples:
                name: temperature_samples
                type: int
                default: '16'
                field: number
                max: '256'
                min: '2'
            humidity_samples:
                name: humidity_samples
                type: int
                default: '32'
                field: number
                max: '512'
                min: '4'
        """
        self._driver.set_averaging(temperature_samples, humidity_samples)

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
        return int(self._driver.is_heater_on())

    def power_down(self):
        """
        label:
            en: '%1 power down'
        """
        self._driver.power_down()

    def power_up(self):
        """
        label:
            en: '%1 power up'
        """
        self._driver.power_up()

    def reboot(self):
        """
        label:
            en: '%1 reload calibration (reboot)'
        """
        self._driver.reboot()
