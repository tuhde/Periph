"""
file     Ccs811
time     2026-11-26
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.ccs811 import CCS811Full


class Ccs811:
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

    def __init__(self, bus: int = 0, address: int = 90):
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
                default: '90'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = CCS811Full(connection)
        self._driver.init()

    def eco2(self) -> int:
        """
        label:
            en: '%1 eCO2 (ppm)'
        """
        return self._driver.read_eco2()

    def tvoc(self) -> int:
        """
        label:
            en: '%1 TVOC (ppb)'
        """
        return self._driver.read_tvoc()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready'
        """
        return self._driver.data_ready()

    def set_mode(self, mode: int = 1):
        """
        label:
            en: '%1 drive mode (0-4) %2'
        params:
            mode:
                name: mode
                type: int
                default: '1'
                field: number
        """
        self._driver.set_mode(mode)

    def set_environment(self, humidity: float = 50.0, temperature: float = 25.0):
        """
        label:
            en: '%1 environment humidity (%RH) %2 temperature (°C) %3'
        params:
            humidity:
                name: humidity
                type: float
                default: '50.0'
                field: number
            temperature:
                name: temperature
                type: float
                default: '25.0'
                field: number
        """
        self._driver.set_environment(humidity, temperature)

    def set_thresholds(self, low_medium: int = 1500, medium_high: int = 2500, hysteresis: int = 50):
        """
        label:
            en: '%1 thresholds low/medium (ppm) %2 medium/high (ppm) %3 hysteresis (ppm) %4'
        params:
            low_medium:
                name: low_medium
                type: int
                default: '1500'
                field: number
            medium_high:
                name: medium_high
                type: int
                default: '2500'
                field: number
            hysteresis:
                name: hysteresis
                type: int
                default: '50'
                field: number
        """
        self._driver.set_thresholds(low_medium, medium_high, hysteresis)

    def baseline(self) -> int:
        """
        label:
            en: '%1 baseline'
        """
        return self._driver.get_baseline()

    def set_baseline(self, baseline: int = 0):
        """
        label:
            en: '%1 set baseline %2'
        params:
            baseline:
                name: baseline
                type: int
                default: '0'
                field: number
        """
        self._driver.set_baseline(baseline)

    def status(self) -> int:
        """
        label:
            en: '%1 status'
        """
        return self._driver.status()

    def error(self) -> int:
        """
        label:
            en: '%1 error ID'
        """
        return self._driver.error()

    def soft_reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.soft_reset()
