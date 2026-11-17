"""
file     LTC4151
time     2026-11-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ltc4151 import LTC4151Full


class LTC4151:
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

    def __init__(self, bus: int = 0, address: int = 106, sense_milliohms: float = 20):
        """
        label:
            en: '%1 init bus %2 address %3 sense resistor (mOhm) %4'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '106'
                field: number
            sense_milliohms:
                name: sense_milliohms
                type: float
                default: '20'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = LTC4151Full(connection, sense_milliohms / 1000.0)

    def voltage(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.voltage()

    def current(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.current()

    def power(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.power()

    def sense_voltage(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.sense_voltage()

    def adin_voltage(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.adin_voltage()

    def read_raw(self, channel: int = 0) -> int:
        """
        label:
            en: '{l}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
        """
        return self._driver.read_raw(channel)

    def set_sense_resistor(self, milliohms: float = 20):
        """
        label:
            en: '{l}'
        params:
            milliohms:
                name: milliohms
                type: float
                default: '20'
                field: number
        """
        self._driver.set_sense_resistor(milliohms / 1000.0)

    def get_sense_resistor(self) -> float:
        """
        label:
            en: '{l}'
        """
        return self._driver.get_sense_resistor()

    def snapshot(self, channel: int = 1) -> float:
        """
        label:
            en: '{l}'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                max: '2'
                min: '0'
        """
        return self._driver.snapshot(channel)

    def set_continuous(self):
        """
        label:
            en: '{l}'
        """
        self._driver.set_continuous()

    def is_snapshot_mode(self) -> int:
        """
        label:
            en: '{l}'
        """
        return int(self._driver.is_snapshot_mode())

    def is_busy(self, channel: int = 0) -> int:
        """
        label:
            en: '{l}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
        """
        return int(self._driver.is_busy(channel))

    def set_stuck_bus_timer(self, enabled: int = 1):
        """
        label:
            en: '{l}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_stuck_bus_timer(bool(enabled))

    def set_page_rw(self, enabled: int = 1):
        """
        label:
            en: '{l}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_page_rw(bool(enabled))

    def read_control(self) -> int:
        """
        label:
            en: '{l}'
        """
        return self._driver.read_control()

    def write_control(self, value: int = 12):
        """
        label:
            en: '{l}'
        params:
            value:
                name: value
                type: int
                default: '12'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.write_control(value)

    def temperature_from_adin(self, adin_code: int = 900, vin_code: int = 1200) -> float:
        """
        label:
            en: '{l}'
        params:
            adin_code:
                name: adin_code
                type: int
                default: '900'
                field: number
                max: '4095'
                min: '0'
            vin_code:
                name: vin_code
                type: int
                default: '1200'
                field: number
                max: '4095'
                min: '1'
        """
        return self._driver.temperature_from_adin(adin_code, vin_code)
