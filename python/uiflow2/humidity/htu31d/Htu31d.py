"""
file     Htu31d
time     2026-11-24
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu31d import HTU31DFull


class Htu31d:
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
        self._driver = HTU31DFull(connection)
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

    def serial_number(self) -> int:
        """
        label:
            en: '%1 serial number'
        """
        return self._driver.serial_number()

    def diagnostic(self) -> int:
        """
        label:
            en: '%1 diagnostic byte'
        """
        return self._driver.diagnostic()

    def set_resolution(self, osr_rh: int = 3, osr_t: int = 3):
        """
        label:
            en: '%1 oversampling RH OSR (0-3) %2 T OSR (0-3) %3'
        params:
            osr_rh:
                name: osr_rh
                type: int
                default: '3'
                field: number
                max: '3'
                min: '0'
            osr_t:
                name: osr_t
                type: int
                default: '3'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_resolution(osr_rh, osr_t)

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

    def software_reset(self):
        """
        label:
            en: '%1 software reset'
        """
        self._driver.software_reset()
