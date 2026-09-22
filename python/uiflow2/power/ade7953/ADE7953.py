"""
file     ADE7953
time     2026-09-16
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ade7953 import ADE7953Full


class ADE7953:
    """
    note:
        en: 'Single-phase multifunction metering IC — voltage, current, active/reactive/apparent power and energy.'
    details:
        color: '#C084FC'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self, voltage_gain: float = 251.0, current_gain: float = 30.0):
        """
        label:
            en: '%1 init ADE7953 voltage_gain %2 current_gain %3'
        params:
            voltage_gain:
                name: voltage_gain
                type: float
                default: '251.0'
                field: number
            current_gain:
                name: current_gain
                type: float
                default: '30.0'
                field: number
        """
        self._driver = ADE7953Full(I2CConnection(0x38), voltage_gain, current_gain, 'i2c')

    def voltage(self) -> float:
        """
        label:
            en: '%1 read voltage (V)'
        """
        return self._driver.voltage()

    def current(self) -> float:
        """
        label:
            en: '%1 read current Channel A (A)'
        """
        return self._driver.current()

    def current_b(self) -> float:
        """
        label:
            en: '%1 read current Channel B (A)'
        """
        return self._driver.current_b()

    def active_power(self) -> float:
        """
        label:
            en: '%1 read active power (W)'
        """
        return self._driver.active_power()

    def active_energy(self) -> float:
        """
        label:
            en: '%1 read active energy (Wh)'
        """
        return self._driver.active_energy()

    def reactive_power(self) -> float:
        """
        label:
            en: '%1 read reactive power (VAR)'
        """
        return self._driver.reactive_power()

    def apparent_power(self) -> float:
        """
        label:
            en: '%1 read apparent power (VA)'
        """
        return self._driver.apparent_power()

    def power_factor(self) -> float:
        """
        label:
            en: '%1 read power factor'
        """
        return self._driver.power_factor()

    def line_frequency(self) -> float:
        """
        label:
            en: '%1 read line frequency (Hz)'
        """
        return self._driver.line_frequency()

    def line_period(self) -> float:
        """
        label:
            en: '%1 read line period (s)'
        """
        return self._driver.line_period()

    def waveform_sample(self) -> dict:
        """
        label:
            en: '%1 read waveform sample (dict)'
        """
        return self._driver.waveform_sample()