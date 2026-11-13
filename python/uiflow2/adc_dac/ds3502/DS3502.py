"""
file     DS3502
time     2026-11-13
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ds3502 import DS3502Full


class DS3502:
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

    def __init__(self, bus: int = 0, address: int = 40, v_high: float = 1.0, v_low: float = 0.0):
        """
        label:
            en: '%1 init bus %2 address %3 RH (V) %4 RL (V) %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '40'
                field: number
            v_high:
                name: v_high
                type: float
                default: '1.0'
                field: number
            v_low:
                name: v_low
                type: float
                default: '0.0'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = DS3502Full(connection, v_high, v_low)

    def set_wiper(self, code: int):
        """
        label:
            en: '%1 set wiper code (0-127) %2'
        params:
            code:
                name: code
                type: int
                default: '64'
                field: number
                max: '127'
                min: '0'
        """
        self._driver.set_wiper(code)

    def get_wiper(self) -> int:
        """
        label:
            en: '%1 wiper code'
        """
        return self._driver.get_wiper()

    def set_voltage(self, volts: float):
        """
        label:
            en: '%1 set output voltage (V) %2'
        params:
            volts:
                name: volts
                type: float
                default: '1.0'
                field: number
        """
        self._driver.set_voltage(volts)

    def get_voltage(self) -> float:
        """
        label:
            en: '%1 output voltage (V)'
        """
        return self._driver.get_voltage()

    def set_ratio(self, ratio: float):
        """
        label:
            en: '%1 set ratio (0-1) %2'
        params:
            ratio:
                name: ratio
                type: float
                default: '0.5'
                field: number
        """
        self._driver.set_ratio(ratio)

    def get_ratio(self) -> float:
        """
        label:
            en: '%1 ratio'
        """
        return self._driver.get_ratio()

    def get_resistance_low(self) -> float:
        """
        label:
            en: '%1 wiper-to-RL resistance (ohm)'
        """
        return self._driver.get_resistance('L')

    def get_resistance_high(self) -> float:
        """
        label:
            en: '%1 wiper-to-RH resistance (ohm)'
        """
        return self._driver.get_resistance('H')

    def increment(self, steps: int) -> int:
        """
        label:
            en: '%1 step wiper steps %2'
        params:
            steps:
                name: steps
                type: int
                default: '1'
                field: number
        """
        return self._driver.increment(steps)

    def store_wiper(self, code: int):
        """
        label:
            en: '%1 store wiper code in EEPROM %2'
        params:
            code:
                name: code
                type: int
                default: '64'
                field: number
                max: '127'
                min: '0'
        """
        self._driver.store_wiper(code)

    def set_mode(self, eeprom: int):
        """
        label:
            en: '%1 EEPROM-backed writes (1 = yes) %2'
        params:
            eeprom:
                name: eeprom
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_mode(bool(eeprom))

    def get_mode(self) -> bool:
        """
        label:
            en: '%1 EEPROM-backed writes'
        """
        return self._driver.get_mode()

    def wait_ready(self, timeout_ms: float):
        """
        label:
            en: '%1 wait until ready timeout (ms) %2'
        params:
            timeout_ms:
                name: timeout_ms
                type: float
                default: '30'
                field: number
        """
        self._driver.wait_ready(timeout_ms)

    def set_endpoints(self, v_high: float, v_low: float):
        """
        label:
            en: '%1 set RH (V) %2 RL (V) %3'
        params:
            v_high:
                name: v_high
                type: float
                default: '15.0'
                field: number
            v_low:
                name: v_low
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_endpoints(v_high, v_low)
