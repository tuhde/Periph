"""
file     MCP4XXX
time     2026-11-07
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxFull


class MCP4XXX:
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

    def __init__(self, bus: int = 0, address: int = 88, part: int = 4532, r_ab: float = 10000.0, hvc: int = -1):
        """
        label:
            en: '%1 init bus %2 address (40-47 or 88-91) %3 part number (4531-4662) %4 RAB ohms %5 HVC pin GPIO (-1 none) %6'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '88'
                field: number
                max: '95'
                min: '40'
            part:
                name: part
                type: int
                default: '4532'
                field: number
                max: '4662'
                min: '4531'
            r_ab:
                name: r_ab
                type: float
                default: '10000.0'
                field: number
            hvc:
                name: hvc
                type: int
                default: '-1'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        hvc_pin = MicroPythonOutputPin(hvc) if hvc >= 0 else None
        self._driver = Mcp4xxxFull(connection, part='MCP%d' % part, r_ab=r_ab, hvc=hvc_pin)

    def set_wiper(self, code: int, channel: int):
        """
        label:
            en: '%1 set wiper code %2 network (0-1) %3'
        params:
            code:
                name: code
                type: int
                default: '64'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_wiper(code, channel)

    def get_wiper(self, channel: int) -> int:
        """
        label:
            en: '%1 wiper code (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.get_wiper(channel)

    def set_resistance(self, ohms: float, channel: int):
        """
        label:
            en: '%1 set W-B resistance ohms %2 network (0-1) %3'
        params:
            ohms:
                name: ohms
                type: float
                default: '5000.0'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_resistance(ohms, channel)

    def get_resistance(self, channel: int) -> float:
        """
        label:
            en: '%1 W-B resistance in ohms (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.get_resistance(channel)

    def set_ratio(self, ratio: float, channel: int):
        """
        label:
            en: '%1 set wiper ratio (0-1) %2 network (0-1) %3'
        params:
            ratio:
                name: ratio
                type: float
                default: '0.5'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_ratio(ratio, channel)

    def get_ratio(self, channel: int) -> float:
        """
        label:
            en: '%1 wiper ratio (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.get_ratio(channel)

    def get_resistance_wa(self, channel: int) -> float:
        """
        label:
            en: '%1 W-A resistance in ohms, potentiometer parts (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.get_resistance_wa(channel)

    def set_resistance_wa(self, ohms: float, channel: int):
        """
        label:
            en: '%1 set W-A resistance ohms, potentiometer parts %2 network (0-1) %3'
        params:
            ohms:
                name: ohms
                type: float
                default: '5000.0'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_resistance_wa(ohms, channel)

    def get_resistance_step(self) -> float:
        """
        label:
            en: '%1 resistance of one step in ohms'
        """
        return self._driver.get_resistance_step()

    def set_both(self, code0: int, code1: int):
        """
        label:
            en: '%1 set both wipers, dual parts, network 0 code %2 network 1 code %3'
        params:
            code0:
                name: code0
                type: int
                default: '64'
                field: number
            code1:
                name: code1
                type: int
                default: '64'
                field: number
        """
        self._driver.set_both(code0, code1)

    def increment(self, steps: int, channel: int) -> int:
        """
        label:
            en: '%1 increment wiper, returns new code, steps %2 network (0-1) %3'
        params:
            steps:
                name: steps
                type: int
                default: '1'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.increment(steps, channel)

    def decrement(self, steps: int, channel: int) -> int:
        """
        label:
            en: '%1 decrement wiper, returns new code, steps %2 network (0-1) %3'
        params:
            steps:
                name: steps
                type: int
                default: '1'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.decrement(steps, channel)

    def shutdown(self, channel: int):
        """
        label:
            en: '%1 shutdown network (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.shutdown(channel)

    def resume(self, channel: int):
        """
        label:
            en: '%1 resume network (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.resume(channel)

    def is_shutdown(self, channel: int) -> bool:
        """
        label:
            en: '%1 network in shutdown? (0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.is_shutdown(channel)

    def set_terminals(self, a: int, w: int, b: int, channel: int):
        """
        label:
            en: '%1 connect terminals A (1/0) %2 W (1/0) %3 B (1/0) %4 network (0-1) %5'
        params:
            a:
                name: a
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            w:
                name: w
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            b:
                name: b
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_terminals(a != 0, w != 0, b != 0, channel)

    def get_tcon(self) -> int:
        """
        label:
            en: '%1 raw TCON register'
        """
        return self._driver.get_tcon()

    def set_general_call(self, enabled: int):
        """
        label:
            en: '%1 General Call enabled (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_general_call(enabled != 0)

    def reset(self):
        """
        label:
            en: '%1 reset to power-up state'
        """
        self._driver.reset()

    def set_rab(self, r_ab: float, r_wiper: float):
        """
        label:
            en: '%1 set resistance model RAB ohms %2 RW ohms %3'
        params:
            r_ab:
                name: r_ab
                type: float
                default: '10000.0'
                field: number
            r_wiper:
                name: r_wiper
                type: float
                default: '75.0'
                field: number
        """
        self._driver.set_rab(r_ab, r_wiper)

    def wait_ready(self, timeout_ms: float):
        """
        label:
            en: '%1 wait for EEPROM write, non-volatile parts, timeout ms %2'
        params:
            timeout_ms:
                name: timeout_ms
                type: float
                default: '20.0'
                field: number
        """
        self._driver.wait_ready(timeout_ms)

    def get_nv_wiper(self, channel: int) -> int:
        """
        label:
            en: '%1 EEPROM wiper, non-volatile parts (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.get_nv_wiper(channel)

    def set_nv_wiper(self, code: int, channel: int):
        """
        label:
            en: '%1 set EEPROM wiper, non-volatile parts, code %2 network (0-1) %3'
        params:
            code:
                name: code
                type: int
                default: '64'
                field: number
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_nv_wiper(code, channel)

    def store_wiper(self, channel: int) -> int:
        """
        label:
            en: '%1 store wiper in EEPROM, non-volatile parts, returns code (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.store_wiper(channel)

    def read_data(self, index: int) -> int:
        """
        label:
            en: '%1 EEPROM data register, non-volatile parts (index 0-9) %2'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
                max: '9'
                min: '0'
        """
        return self._driver.read_data(index)

    def write_data(self, index: int, value: int):
        """
        label:
            en: '%1 write EEPROM data register, non-volatile parts, index (0-9) %2 value (0-511) %3'
        params:
            index:
                name: index
                type: int
                default: '0'
                field: number
                max: '9'
                min: '0'
            value:
                name: value
                type: int
                default: '0'
                field: number
                max: '511'
                min: '0'
        """
        self._driver.write_data(index, value)

    def is_wiper_locked(self, channel: int) -> bool:
        """
        label:
            en: '%1 WiperLock active? non-volatile parts (network 0-1) %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        return self._driver.is_wiper_locked(channel)

    def is_write_protected(self) -> bool:
        """
        label:
            en: '%1 EEPROM write protected? non-volatile parts'
        """
        return self._driver.is_write_protected()

    def set_wiper_lock(self, locked: int, channel: int):
        """
        label:
            en: '%1 set WiperLock, needs HVC pin, locked (1/0) %2 network (0-1) %3'
        params:
            locked:
                name: locked
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_wiper_lock(locked != 0, channel)

    def set_write_protect(self, enabled: int):
        """
        label:
            en: '%1 set write protect, needs HVC pin, enabled (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_write_protect(enabled != 0)
