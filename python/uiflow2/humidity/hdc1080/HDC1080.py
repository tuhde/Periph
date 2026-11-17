"""
file     HDC1080
time     2026-11-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hdc1080 import HDC1080Full


class HDC1080:
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
        self._driver = HDC1080Full(connection)
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

    def set_resolution(self, temperature_bits: int = 14, humidity_bits: int = 14):
        """
        label:
            en: '%1 resolution T bits (14/11) %2 RH bits (14/11/8) %3'
        params:
            temperature_bits:
                name: temperature_bits
                type: int
                default: '14'
                field: number
                max: '14'
                min: '11'
            humidity_bits:
                name: humidity_bits
                type: int
                default: '14'
                field: number
                max: '14'
                min: '8'
        """
        self._driver.set_resolution(temperature_bits, humidity_bits)

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

    def battery_low(self) -> int:
        """
        label:
            en: '%1 battery low (1=yes 0=no)'
        """
        return int(self._driver.battery_low())

    def serial_id(self) -> int:
        """
        label:
            en: '%1 serial ID'
        """
        return self._driver.serial_id()

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.software_reset()
