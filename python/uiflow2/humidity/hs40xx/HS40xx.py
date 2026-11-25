"""
file     HS40xx
time     2026-11-19
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs40xx import HS40xxFull


class HS40xx:
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

    def __init__(self, bus: int = 0, address: int = 84):
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
                default: '84'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = HS40xxFull(connection)

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

    def raw_humidity(self) -> int:
        """
        label:
            en: '%1 raw humidity (14 bit)'
        """
        return self._driver.read_raw()[0]

    def raw_temperature(self) -> int:
        """
        label:
            en: '%1 raw temperature (14 bit)'
        """
        return self._driver.read_raw()[1]

    def sensor_id(self) -> int:
        """
        label:
            en: '%1 sensor ID'
        """
        return self._driver.read_id()

    def set_resolution(self, humidity_bits: int = 14, temperature_bits: int = 14):
        """
        label:
            en: '%1 resolution humidity %2 bits temperature %3 bits'
        params:
            humidity_bits:
                name: humidity_bits
                type: int
                default: '14'
                field: number
                max: '14'
                min: '8'
            temperature_bits:
                name: temperature_bits
                type: int
                default: '14'
                field: number
                max: '14'
                min: '8'
        """
        self._driver.set_resolution(humidity_bits, temperature_bits)

    def start_periodic(self, period: int = 1):
        """
        label:
            en: '%1 start periodic mode, period %2 (0=0.5 s, 1=1 s, 2=2.5 s)'
        params:
            period:
                name: period
                type: int
                default: '1'
                field: number
                max: '2'
                min: '0'
        """
        self._driver.start_periodic((0.5, 1, 2.5)[period])

    def stop_periodic(self):
        """
        label:
            en: '%1 stop periodic mode'
        """
        self._driver.stop_periodic()

    def periodic_humidity(self) -> float:
        """
        label:
            en: '%1 periodic relative humidity (%RH)'
        """
        return self._driver.read_periodic()['humidity_pct']

    def periodic_temperature(self) -> float:
        """
        label:
            en: '%1 periodic temperature (°C)'
        """
        return self._driver.read_periodic()['temperature_c']

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()
