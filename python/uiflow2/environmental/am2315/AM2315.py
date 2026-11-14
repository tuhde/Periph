"""
file     AM2315
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.am2315 import AM2315Full


class AM2315:
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

    def __init__(self, bus: int = 0, address: int = 92):
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
                default: '92'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = AM2315Full(connection)

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

    def fresh_temperature(self) -> float:
        """
        label:
            en: '%1 real-time temperature (°C, two reads)'
        """
        return self._driver.read_fresh()['temperature_c']

    def fresh_humidity(self) -> float:
        """
        label:
            en: '%1 real-time humidity (%RH, two reads)'
        """
        return self._driver.read_fresh()['humidity_pct']

    def device_id(self) -> int:
        """
        label:
            en: '%1 device ID'
        """
        return self._driver.read_device_info()['device_id']

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()
