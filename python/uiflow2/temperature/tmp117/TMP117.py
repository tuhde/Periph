"""
file     TMP117
time     2026-09-23
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.temperature.tmp117 import TMP117Full


class TMP117:
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
        self._driver = TMP117Full(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def configure(self, mode: int, averaging: int, cycle_seconds: float):
        """
        label:
            en: '%1 configure mode (0=continuous 1=shutdown 2=one-shot) %2 averaging (0/8/32/64) %3 cycle (s) %4'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '2'
            averaging:
                name: averaging
                type: int
                default: '8'
                field: number
                min: '0'
                max: '64'
            cycle_seconds:
                name: cycle_seconds
                type: float
                default: '1.0'
                field: number
        """
        self._driver.configure(('continuous', 'shutdown', 'one_shot')[mode], averaging, cycle_seconds)

    def get_config(self) -> list:
        """
        label:
            en: '%1 config [mode, averaging, cycle s]'
        """
        return list(self._driver.get_config())

    def is_shutdown(self) -> bool:
        """
        label:
            en: '%1 is shut down?'
        """
        return self._driver.is_shutdown()

    def trigger_one_shot(self):
        """
        label:
            en: '%1 trigger one-shot conversion'
        """
        self._driver.trigger_one_shot()

    def is_data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready?'
        """
        return self._driver.is_data_ready()

    def set_high_limit(self, celsius: float):
        """
        label:
            en: '%1 set high limit (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '30.0'
                field: number
        """
        self._driver.set_high_limit(celsius)

    def get_high_limit(self) -> float:
        """
        label:
            en: '%1 high limit (°C)'
        """
        return self._driver.get_high_limit()

    def set_low_limit(self, celsius: float):
        """
        label:
            en: '%1 set low limit (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '10.0'
                field: number
        """
        self._driver.set_low_limit(celsius)

    def get_low_limit(self) -> float:
        """
        label:
            en: '%1 low limit (°C)'
        """
        return self._driver.get_low_limit()

    def set_temperature_offset(self, celsius: float):
        """
        label:
            en: '%1 set temperature offset (°C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_temperature_offset(celsius)

    def get_temperature_offset(self) -> float:
        """
        label:
            en: '%1 temperature offset (°C)'
        """
        return self._driver.get_temperature_offset()

    def reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.reset()

    def unlock_eeprom(self):
        """
        label:
            en: '%1 unlock EEPROM'
        """
        self._driver.unlock_eeprom()

    def lock_eeprom(self):
        """
        label:
            en: '%1 lock EEPROM'
        """
        self._driver.lock_eeprom()

    def is_eeprom_busy(self) -> bool:
        """
        label:
            en: '%1 EEPROM busy?'
        """
        return self._driver.is_eeprom_busy()

    def read_eeprom_scratch(self, slot: int) -> int:
        """
        label:
            en: '%1 EEPROM scratch slot (1-3) %2'
        params:
            slot:
                name: slot
                type: int
                default: '2'
                field: number
                min: '1'
                max: '3'
        """
        return self._driver.read_eeprom_scratch(slot)

    def write_eeprom_scratch(self, slot: int, value: int):
        """
        label:
            en: '%1 write EEPROM scratch slot (2) %2 value (0-65535) %3'
        params:
            slot:
                name: slot
                type: int
                default: '2'
                field: number
                min: '2'
                max: '2'
            value:
                name: value
                type: int
                default: '0'
                field: number
                min: '0'
                max: '65535'
        """
        self._driver.write_eeprom_scratch(slot, value)

    def configure_alert(self, mode: int, polarity: int, pin_function: int):
        """
        label:
            en: '%1 configure alert mode (0=alert 1=therm) %2 polarity (0=active low 1=active high) %3 pin (0=alert 1=data ready) %4'
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
            pin_function:
                name: pin_function
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.configure_alert(('alert', 'therm')[mode],
                                     ('active_low', 'active_high')[polarity],
                                     ('alert', 'data_ready')[pin_function])

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 alert status (1=high 2=low)'
        """
        return self._driver.poll_interrupt()
