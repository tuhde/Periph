"""
file     LM25066
time     2026-11-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lm25066 import LM25066Full


class LM25066:
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

    def __init__(self, bus: int = 0, address: int = 64, r_sense_mohm: float = 1.0, current_limit_high: int = 0):
        """
        label:
            en: '%1 init bus %2 address %3 sense resistor (mOhm) %4 CL pin at VDD (0/1) %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '64'
                field: number
            r_sense_mohm:
                name: r_sense_mohm
                type: float
                default: '1.0'
                field: number
            current_limit_high:
                name: current_limit_high
                type: int
                default: '0'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = LM25066Full(connection, r_sense_mohm, bool(current_limit_high))

    def voltage_in(self) -> float:
        """
        label:
            en: '%1 VIN (V)'
        """
        return self._driver.voltage_in()

    def voltage_out(self) -> float:
        """
        label:
            en: '%1 VOUT (V)'
        """
        return self._driver.voltage_out()

    def current(self) -> float:
        """
        label:
            en: '%1 input current (A)'
        """
        return self._driver.current()

    def power(self) -> float:
        """
        label:
            en: '%1 input power (W)'
        """
        return self._driver.power()

    def temperature(self) -> float:
        """
        label:
            en: '%1 MOSFET temperature (C)'
        """
        return self._driver.temperature()

    def voltage_aux(self) -> float:
        """
        label:
            en: '%1 VAUX pin voltage (V)'
        """
        return self._driver.voltage_aux()

    def voltage_in_avg(self) -> float:
        """
        label:
            en: '%1 averaged VIN (V)'
        """
        return self._driver.voltage_in_avg()

    def voltage_out_avg(self) -> float:
        """
        label:
            en: '%1 averaged VOUT (V)'
        """
        return self._driver.voltage_out_avg()

    def current_avg(self) -> float:
        """
        label:
            en: '%1 averaged input current (A)'
        """
        return self._driver.current_avg()

    def power_avg(self) -> float:
        """
        label:
            en: '%1 averaged input power (W)'
        """
        return self._driver.power_avg()

    def power_peak(self) -> float:
        """
        label:
            en: '%1 peak input power (W)'
        """
        return self._driver.power_peak()

    def clear_power_peak(self):
        """
        label:
            en: '%1 clear peak power'
        """
        self._driver.clear_power_peak()

    def set_averaging(self, exponent: int):
        """
        label:
            en: '%1 set averaging exponent (0-12) %2'
        params:
            exponent:
                name: exponent
                type: int
                default: '0'
                field: number
        """
        self._driver.set_averaging(exponent)

    def get_averaging(self) -> int:
        """
        label:
            en: '%1 averaging exponent'
        """
        return self._driver.get_averaging()

    def set_vin_ov_warn(self, volts: float):
        """
        label:
            en: '%1 VIN over-voltage warning (V) %2'
        params:
            volts:
                name: volts
                type: float
                default: '0'
                field: number
        """
        self._driver.set_vin_ov_warn(volts)

    def set_vin_uv_warn(self, volts: float):
        """
        label:
            en: '%1 VIN under-voltage warning (V) %2'
        params:
            volts:
                name: volts
                type: float
                default: '0'
                field: number
        """
        self._driver.set_vin_uv_warn(volts)

    def set_vout_uv_warn(self, volts: float):
        """
        label:
            en: '%1 VOUT under-voltage warning (V) %2'
        params:
            volts:
                name: volts
                type: float
                default: '0'
                field: number
        """
        self._driver.set_vout_uv_warn(volts)

    def set_ot_warn(self, celsius: float):
        """
        label:
            en: '%1 over-temperature warning (C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0'
                field: number
        """
        self._driver.set_ot_warn(celsius)

    def set_ot_fault(self, celsius: float):
        """
        label:
            en: '%1 over-temperature fault (C) %2'
        params:
            celsius:
                name: celsius
                type: float
                default: '0'
                field: number
        """
        self._driver.set_ot_fault(celsius)

    def set_iin_warn(self, amps: float):
        """
        label:
            en: '%1 input over-current warning (A) %2'
        params:
            amps:
                name: amps
                type: float
                default: '0'
                field: number
        """
        self._driver.set_iin_warn(amps)

    def set_pin_warn(self, watts: float):
        """
        label:
            en: '%1 input over-power warning (W) %2'
        params:
            watts:
                name: watts
                type: float
                default: '0'
                field: number
        """
        self._driver.set_pin_warn(watts)

    def status_word(self) -> int:
        """
        label:
            en: '%1 STATUS_WORD'
        """
        return self._driver.status_word()

    def diagnostic_word(self) -> int:
        """
        label:
            en: '%1 diagnostic word'
        """
        return self._driver.diagnostic_word()

    def clear_faults(self):
        """
        label:
            en: '%1 clear faults'
        """
        self._driver.clear_faults()

    def operation_on(self):
        """
        label:
            en: '%1 switch MOSFET ON'
        """
        self._driver.operation_on()

    def operation_off(self):
        """
        label:
            en: '%1 switch MOSFET OFF (cuts the load)'
        """
        self._driver.operation_off()

    def is_on(self) -> bool:
        """
        label:
            en: '%1 MOSFET is on'
        """
        return self._driver.is_on()

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 unmask alert source (ALERT_MASK bits) %2'
        params:
            source:
                name: source
                type: int
                default: '0'
                field: number
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 mask alert source (ALERT_MASK bits) %2'
        params:
            source:
                name: source
                type: int
                default: '0'
                field: number
        """
        self._driver.disable_interrupt(source)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 alert flags (diagnostic word)'
        """
        return self._driver.poll_interrupt()
