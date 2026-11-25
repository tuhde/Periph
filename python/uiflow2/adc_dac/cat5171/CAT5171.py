"""
file     CAT5171
time     2026-11-11
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.cat5171 import CAT5171Full


class CAT5171:
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

    def __init__(self, bus: int = 0, r_ab: float = 100000.0):
        """
        label:
            en: '%1 init bus %2 RAB (ohm) %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            r_ab:
                name: r_ab
                type: float
                default: '100000.0'
                field: number
        """
        connection = I2CConnection(0x2C, bus=bus)
        self._driver = CAT5171Full(connection, r_ab)

    def set_wiper(self, code: int):
        """
        label:
            en: '%1 set wiper code (0-255) %2'
        params:
            code:
                name: code
                type: int
                default: '128'
                field: number
                min: '0'
                max: '255'
        """
        self._driver.set_wiper(code)

    def get_wiper(self) -> int:
        """
        label:
            en: '%1 wiper code'
        """
        return self._driver.get_wiper()

    def set_resistance(self, ohms: float):
        """
        label:
            en: '%1 set W-B resistance ohms %2'
        params:
            ohms:
                name: ohms
                type: float
                default: '50000.0'
                field: number
        """
        self._driver.set_resistance(ohms)

    def get_resistance(self) -> float:
        """
        label:
            en: '%1 W-B resistance (ohm)'
        """
        return self._driver.get_resistance()

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

    def set_resistance_wa(self, ohms: float):
        """
        label:
            en: '%1 set W-A resistance ohms %2'
        params:
            ohms:
                name: ohms
                type: float
                default: '50000.0'
                field: number
        """
        self._driver.set_resistance_wa(ohms)

    def get_resistance_wa(self) -> float:
        """
        label:
            en: '%1 W-A resistance (ohm)'
        """
        return self._driver.get_resistance_wa()

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

    def midscale(self):
        """
        label:
            en: '%1 move to midscale'
        """
        self._driver.midscale()

    def shutdown(self):
        """
        label:
            en: '%1 shutdown'
        """
        self._driver.shutdown()

    def resume(self):
        """
        label:
            en: '%1 resume'
        """
        self._driver.resume()

    def is_shutdown(self) -> bool:
        """
        label:
            en: '%1 is shut down'
        """
        return self._driver.is_shutdown()

    def reset(self):
        """
        label:
            en: '%1 reset to midscale'
        """
        self._driver.reset()

    def set_rab(self, r_ab: float, r_wiper: float):
        """
        label:
            en: '%1 set RAB (ohm) %2 RW (ohm) %3'
        params:
            r_ab:
                name: r_ab
                type: float
                default: '100000.0'
                field: number
            r_wiper:
                name: r_wiper
                type: float
                default: '50.0'
                field: number
        """
        self._driver.set_rab(r_ab, r_wiper)
