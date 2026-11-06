"""
file     ADS1113
time     2026-11-06
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads1113 import ADS1113Full


class ADS1113:
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

    def __init__(self, bus: int = 0, address: int = 72):
        """
        label:
            en: '%1 init bus %2 address (72-75) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '72'
                field: number
                min: '72'
                max: '75'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = ADS1113Full(connection)

    def read_raw(self) -> int:
        """
        label:
            en: '%1 raw code'
        """
        return self._driver.read_raw()

    def read_voltage(self) -> float:
        """
        label:
            en: '%1 voltage (V)'
        """
        return self._driver.read_voltage()

    def full_scale(self) -> float:
        """
        label:
            en: '%1 full scale (V)'
        """
        return self._driver.full_scale()

    def set_data_rate(self, dr: int):
        """
        label:
            en: '%1 set data rate (0=8 1=16 2=32 3=64 4=128 5=250 6=475 7=860 SPS) %2'
        params:
            dr:
                name: dr
                type: int
                default: '4'
                field: number
                min: '0'
                max: '7'
        """
        self._driver.set_data_rate(dr)

    def data_rate_sps(self) -> int:
        """
        label:
            en: '%1 data rate (SPS)'
        """
        return self._driver.data_rate_sps()

    def start_continuous(self):
        """
        label:
            en: '%1 start continuous'
        """
        self._driver.start_continuous()

    def read_continuous(self) -> float:
        """
        label:
            en: '%1 continuous voltage (V)'
        """
        return self._driver.read_continuous()

    def stop_continuous(self):
        """
        label:
            en: '%1 stop continuous'
        """
        self._driver.stop_continuous()

    def is_converting(self) -> bool:
        """
        label:
            en: '%1 is converting'
        """
        return self._driver.is_converting()

    def read_config(self) -> int:
        """
        label:
            en: '%1 CONFIG register'
        """
        return self._driver.read_config()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
