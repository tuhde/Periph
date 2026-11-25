"""
file     ADS1014
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads1014 import ADS1014Full


class ADS1014:
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
        self._driver = ADS1014Full(connection)

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
            en: '%1 set gain (0=6.144 V 1=4.096 V 2=2.048 V 3=1.024 V 4=0.512 V 5=0.256 V) %2'
        params:
            pga:
                name: pga
                type: int
                default: '2'
                field: number
        """
        self._driver.set_gain(pga)

    def get_gain(self) -> int:
        """
        label:
            en: '%1 gain code'
        """
        return self._driver.get_gain()

    def full_scale(self) -> float:
        """
        label:
            en: '%1 full scale (V)'
        """
        return self._driver.full_scale()

    def lsb(self) -> float:
        """
        label:
            en: '%1 LSB (V)'
        """
        return self._driver.lsb()

    def set_data_rate(self, dr: int):
        """
        label:
            en: '%1 set data rate (0=128 1=250 2=490 3=920 4=1600 5=2400 6=3300 SPS) %2'
        params:
            dr:
                name: dr
                type: int
                default: '4'
                field: number
        """
        self._driver.set_data_rate(dr)

    def get_data_rate(self) -> int:
        """
        label:
            en: '%1 data rate code'
        """
        return self._driver.get_data_rate()

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

    def read_continuous_raw(self) -> int:
        """
        label:
            en: '%1 continuous raw code'
        """
        return self._driver.read_continuous_raw()

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

    def configure_comparator(self, mode: int, polarity: int, latch: int, queue: int):
        """
        label:
            en: '%1 configure comparator mode (0=traditional 1=window) %2 polarity (0=low 1=high) %3 latch (0/1) %4 queue (0/1/2 = 1/2/4 conversions) %5'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
            polarity:
                name: polarity
                type: int
                default: '0'
                field: number
            latch:
                name: latch
                type: int
                default: '0'
                field: number
            queue:
                name: queue
                type: int
                default: '0'
                field: number
        """
        self._driver.configure_comparator(mode, polarity, latch, queue)

    def disable_comparator(self):
        """
        label:
            en: '%1 disable comparator'
        """
        self._driver.disable_comparator()

    def set_low_threshold_raw(self, raw: int):
        """
        label:
            en: '%1 set low threshold code %2'
        params:
            raw:
                name: raw
                type: int
                default: '-100'
                field: number
        """
        self._driver.set_low_threshold_raw(raw)

    def set_high_threshold_raw(self, raw: int):
        """
        label:
            en: '%1 set high threshold code %2'
        params:
            raw:
                name: raw
                type: int
                default: '100'
                field: number
        """
        self._driver.set_high_threshold_raw(raw)

    def set_thresholds(self, low: float, high: float):
        """
        label:
            en: '%1 set thresholds low (V) %2 high (V) %3'
        params:
            low:
                name: low
                type: float
                default: '-0.1'
                field: number
            high:
                name: high
                type: float
                default: '0.1'
                field: number
        """
        self._driver.set_thresholds(low, high)

    def clear_alert(self):
        """
        label:
            en: '%1 clear alert'
        """
        self._driver.clear_alert()

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 enable interrupt (1=conversion ready 2=comparator) %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt (1=conversion ready 2=comparator) %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
        """
        self._driver.disable_interrupt(source)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt status (1=ready 2=comparator)'
        """
        return self._driver.poll_interrupt()
