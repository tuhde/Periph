"""
file     TMP102
time     2026-11-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.temperature.tmp102 import TMP102Full


class TMP102:
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
        self._driver = TMP102Full(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def read_raw(self) -> int:
        """
        label:
            en: '%1 raw count'
        """
        return self._driver.read_raw()

    def read_config(self) -> int:
        """
        label:
            en: '%1 CONFIGURATION register'
        """
        return self._driver.read_config()

    def write_config(self, value: int):
        """
        label:
            en: '%1 write CONFIGURATION %2'
        params:
            value:
                name: value
                type: int
                default: '24736'
                field: number
        """
        self._driver.write_config(value)

    def set_conversion_rate(self, hz: float):
        """
        label:
            en: '%1 set conversion rate (0.25/1/4/8 Hz) %2'
        params:
            hz:
                name: hz
                type: float
                default: '4.0'
                field: number
        """
        self._driver.set_conversion_rate(hz)

    def set_extended_mode(self, on: int):
        """
        label:
            en: '%1 extended 13-bit mode (0=off 1=on) %2'
        params:
            on:
                name: on
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_extended_mode(bool(on))

    def set_t_high(self, celsius: float):
        """
        label:
            en: '%1 set T_HIGH (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '80.0'
                field: number
        """
        self._driver.set_t_high(celsius)

    def get_t_high(self) -> float:
        """
        label:
            en: '%1 T_HIGH (°C)'
        """
        return self._driver.get_t_high()

    def set_t_low(self, celsius: float):
        """
        label:
            en: '%1 set T_LOW (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '75.0'
                field: number
        """
        self._driver.set_t_low(celsius)

    def get_t_low(self) -> float:
        """
        label:
            en: '%1 T_LOW (°C)'
        """
        return self._driver.get_t_low()

    def set_fault_queue(self, count: int):
        """
        label:
            en: '%1 set fault queue (1/2/4/6) %2'
        params:
            count:
                name: count
                type: int
                default: '1'
                field: number
                min: '1'
                max: '6'
        """
        self._driver.set_fault_queue(count)

    def set_polarity(self, active_high: int):
        """
        label:
            en: '%1 ALERT polarity (0=active low 1=active high) %2'
        params:
            active_high:
                name: active_high
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_polarity(bool(active_high))

    def set_thermostat_mode(self, interrupt: int):
        """
        label:
            en: '%1 thermostat mode (0=comparator 1=interrupt) %2'
        params:
            interrupt:
                name: interrupt
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_thermostat_mode(bool(interrupt))

    def shutdown(self):
        """
        label:
            en: '%1 shut down'
        """
        self._driver.shutdown()

    def wake(self):
        """
        label:
            en: '%1 wake'
        """
        self._driver.wake()

    def one_shot(self) -> float:
        """
        label:
            en: '%1 one-shot temperature (°C)'
        """
        return self._driver.one_shot()

    def alert_state(self) -> bool:
        """
        label:
            en: '%1 alert active?'
        """
        return self._driver.alert_state()

    def reset(self):
        """
        label:
            en: '%1 general-call reset'
        """
        self._driver.reset()

    def poll_interrupt(self) -> bool:
        """
        label:
            en: '%1 poll interrupt (alert active?)'
        """
        return self._driver.poll_interrupt()
