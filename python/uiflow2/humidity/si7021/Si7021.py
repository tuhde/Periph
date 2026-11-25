"""
file     Si7021
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.si7021 import Si7021Full


class Si7021:
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
        self._driver = Si7021Full(connection)

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
        self._driver.set_resolution(*[(12, 14), (8, 12), (10, 13), (11, 11)][level])

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

    def vdd_low(self) -> int:
        """
        label:
            en: '%1 supply low (1=yes 0=no)'
        """
        return int(self._driver.vdd_low())

    def device_type(self) -> int:
        """
        label:
            en: '%1 device type (0x15 = Si7021)'
        """
        return self._driver.device_type()

    def firmware_revision(self) -> int:
        """
        label:
            en: '%1 firmware revision'
        """
        return self._driver.firmware_revision()

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.software_reset()
