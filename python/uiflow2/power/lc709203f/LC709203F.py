"""
file     LC709203F
time     2026-11-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lc709203f import LC709203FFull


class LC709203F:
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

    def __init__(self, bus: int = 0, address: int = 11, capacity_mah: int = 1000, profile: int = 1):
        """
        label:
            en: '%1 init bus %2 address %3 capacity (mAh) %4 profile %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '11'
                field: number
            capacity_mah:
                name: capacity_mah
                type: int
                default: '1000'
                field: number
            profile:
                name: profile
                type: int
                default: '1'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = LC709203FFull(connection, capacity_mah, profile)

    def read_voltage(self) -> float:
        """
        label:
            en: '%1 cell voltage (V)'
        """
        return self._driver.voltage()

    def read_percent(self) -> float:
        """
        label:
            en: '%1 state of charge (%)'
        """
        return self._driver.percent()

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 cell temperature (°C)'
        """
        return self._driver.temperature()

    def read_rsoc(self) -> int:
        """
        label:
            en: '%1 RSOC (%, integer)'
        """
        return self._driver.rsoc()

    def set_temperature(self, celsius: float):
        """
        label:
            en: '%1 set cell temperature (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0'
                field: number
        """
        self._driver.set_temperature(celsius)

    def set_rsoc(self, percent: int):
        """
        label:
            en: '%1 force RSOC (%) %2'
        params:
            percent:
                name: percent
                type: int
                default: '0'
                field: number
        """
        self._driver.set_rsoc(percent)

    def init_rsoc(self):
        """
        label:
            en: '%1 initialize RSOC from voltage'
        """
        self._driver.init_rsoc()

    def use_thermistor(self, b_constant: int):
        """
        label:
            en: '%1 use thermistor, B constant (K) %2'
        params:
            b_constant:
                name: b_constant
                type: int
                default: '0'
                field: number
        """
        self._driver.use_thermistor(b_constant)

    def use_i2c_temperature(self, celsius: float):
        """
        label:
            en: '%1 use I2C temperature (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0'
                field: number
        """
        self._driver.use_i2c_temperature(celsius)

    def set_apa(self, value: int):
        """
        label:
            en: '%1 set APA (0-255) %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.set_apa(value)

    def set_current_direction(self, mode: int):
        """
        label:
            en: '%1 current direction (0 auto, 1 charge, 65535 discharge) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
        """
        self._driver.set_current_direction(mode)

    def set_profile(self, profile: int):
        """
        label:
            en: '%1 battery profile (0 or 1) %2'
        params:
            profile:
                name: profile
                type: int
                default: '0'
                field: number
        """
        self._driver.set_profile(profile)

    def profile_id(self) -> int:
        """
        label:
            en: '%1 profile ID'
        """
        return self._driver.profile_id()

    def ic_version(self) -> int:
        """
        label:
            en: '%1 IC version'
        """
        return self._driver.ic_version()

    def set_alarm_rsoc(self, percent: int):
        """
        label:
            en: '%1 alarm RSOC (%, 0 = off) %2'
        params:
            percent:
                name: percent
                type: int
                default: '0'
                field: number
        """
        self._driver.set_alarm_rsoc(percent)

    def set_alarm_voltage(self, volts: float):
        """
        label:
            en: '%1 alarm voltage (V, 0 = off) %2'
        params:
            volts:
                name: volts
                type: float
                default: '0'
                field: number
        """
        self._driver.set_alarm_voltage(volts)

    def poll_alarm(self) -> int:
        """
        label:
            en: '%1 alarm bitmask (1 RSOC, 2 voltage)'
        """
        return self._driver.poll_interrupt()

    def sleep(self):
        """
        label:
            en: '%1 sleep'
        """
        self._driver.sleep()

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()

    def is_sleeping(self) -> bool:
        """
        label:
            en: '%1 is sleeping'
        """
        return self._driver.is_sleeping()
