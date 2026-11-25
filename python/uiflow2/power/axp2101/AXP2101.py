"""
file     AXP2101
time     2026-11-08
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.axp2101 import AXP2101Full


class AXP2101:
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

    def __init__(self, bus: int = 0, address: int = 52):
        """
        label:
            en: '%1 init bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '52'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = AXP2101Full(connection)

    def battery_voltage(self) -> float:
        """
        label:
            en: '%1 battery voltage (V)'
        """
        return self._driver.battery_voltage()

    def battery_percent(self) -> int:
        """
        label:
            en: '%1 battery level (%)'
        """
        return self._driver.battery_percent()

    def charge_state(self) -> int:
        """
        label:
            en: '%1 charge state (0=trickle 1=pre 2=CC 3=CV 4=done 5=none)'
        """
        return self._driver.charge_state()

    def vbus_present(self) -> bool:
        """
        label:
            en: '%1 VBUS present?'
        """
        return self._driver.vbus_present()

    def vbus_voltage(self) -> float:
        """
        label:
            en: '%1 VBUS voltage (V)'
        """
        return self._driver.vbus_voltage()

    def system_voltage(self) -> float:
        """
        label:
            en: '%1 system voltage (V)'
        """
        return self._driver.system_voltage()

    def die_temperature(self) -> float:
        """
        label:
            en: '%1 die temperature (C)'
        """
        return self._driver.die_temperature()

    def set_charging(self, enabled: int):
        """
        label:
            en: '%1 charging (0=off 1=on) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_charging(bool(enabled))

    def set_charge_voltage(self, volts: float):
        """
        label:
            en: '%1 set charge voltage (V: 4.0 4.1 4.2 4.35 4.4) %2'
        params:
            volts:
                name: volts
                type: float
                default: '4.2'
                field: number
                max: '4.4'
                min: '4.0'
        """
        self._driver.set_charge_voltage(volts)

    def set_charge_current(self, amps: float):
        """
        label:
            en: '%1 set charge current (A, 0 to 1.5) %2'
        params:
            amps:
                name: amps
                type: float
                default: '0.5'
                field: number
                max: '1.5'
                min: '0'
        """
        self._driver.set_charge_current(amps)

    def set_input_current_limit(self, amps: float):
        """
        label:
            en: '%1 set input current limit (A: 0.1 0.5 0.9 1.0 1.5 2.0) %2'
        params:
            amps:
                name: amps
                type: float
                default: '1.5'
                field: number
                max: '2.0'
                min: '0.1'
        """
        self._driver.set_input_current_limit(amps)

    def set_gpio1(self, low: int):
        """
        label:
            en: '%1 GPIO1 (0=Hi-Z 1=drive low) %2'
        params:
            low:
                name: low
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_gpio1(bool(low))
