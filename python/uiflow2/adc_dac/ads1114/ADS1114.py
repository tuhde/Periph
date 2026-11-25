"""
file     ADS1114
time     2026-11-06
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads1114 import ADS1114Full


class ADS1114:
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
        self._driver = ADS1114Full(connection)

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

    def set_gain(self, pga: int):
        """
        label:
            en: '%1 set gain (0=6.144V 1=4.096V 2=2.048V 3=1.024V 4=0.512V 5=0.256V) %2'
        params:
            pga:
                name: pga
                type: int
                default: '2'
                field: number
                min: '0'
                max: '5'
        """
        self._driver.set_gain(pga)

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

    def configure_comparator(self, mode: int, polarity: int, latch: int, queue: int):
        """
        label:
            en: '%1 configure comparator mode (0=traditional 1=window) %2 polarity (0=low 1=high) %3 latch (0/1) %4 queue (0=1 1=2 2=4) %5'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            polarity:
                name: polarity
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            latch:
                name: latch
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            queue:
                name: queue
                type: int
                default: '0'
                field: number
                min: '0'
                max: '2'
        """
        self._driver.configure_comparator(mode, polarity, bool(latch), queue)

    def disable_comparator(self):
        """
        label:
            en: '%1 disable comparator'
        """
        self._driver.disable_comparator()

    def set_thresholds(self, low: float, high: float):
        """
        label:
            en: '%1 set thresholds low (V) %2 high (V) %3'
        params:
            low:
                name: low
                type: float
                default: '0.5'
                field: number
            high:
                name: high
                type: float
                default: '3.0'
                field: number
        """
        self._driver.set_thresholds(low, high)

    def clear_alert(self):
        """
        label:
            en: '%1 clear alert'
        """
        self._driver.clear_alert()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt (0=none 1=conv ready 2=comparator)'
        """
        return self._driver.poll_interrupt()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
