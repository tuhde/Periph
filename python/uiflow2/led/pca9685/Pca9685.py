"""
file     Pca9685
time     2026-11-19
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.pca9685 import PCA9685Full


class Pca9685:
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

    def __init__(self, bus: int = 0, address: int = 64):
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
                default: '64'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCA9685Full(connection)

    def set_duty(self, channel: int, duty: float):
        """
        label:
            en: '%1 set duty channel %2 duty %3'
        params:
            channel:
                name: channel
                type: int
                field: number
            duty:
                name: duty
                type: float
                field: number
        """
        self._driver.set_duty(channel, duty)

    def set_pwm(self, channel: int, on: int, off: int):
        """
        label:
            en: '%1 set pwm channel %2 on %3 off %4'
        params:
            channel:
                name: channel
                type: int
                field: number
            on:
                name: on
                type: int
                field: number
            off:
                name: off
                type: int
                field: number
        """
        self._driver.set_pwm(channel, on, off)

    def get_pwm_on(self, channel: int) -> int:
        """
        label:
            en: '%1 get pwm on count channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """

        return self._driver.get_pwm(channel)[0]

    def get_pwm_off(self, channel: int) -> int:
        """
        label:
            en: '%1 get pwm off count channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """

        return self._driver.get_pwm(channel)[1]

    def set_full_on(self, channel: int):
        """
        label:
            en: '%1 set full on channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        self._driver.set_full_on(channel)

    def set_full_off(self, channel: int):
        """
        label:
            en: '%1 set full off channel %2'
        params:
            channel:
                name: channel
                type: int
                field: number
        """
        self._driver.set_full_off(channel)

    def set_all_duty(self, duty: float):
        """
        label:
            en: '%1 set all duty %2'
        params:
            duty:
                name: duty
                type: float
                field: number
        """
        self._driver.set_all_duty(duty)

    def set_all_pwm(self, on: int, off: int):
        """
        label:
            en: '%1 set all pwm on %2 off %3'
        params:
            on:
                name: on
                type: int
                field: number
            off:
                name: off
                type: int
                field: number
        """
        self._driver.set_all_pwm(on, off)

    def set_frequency(self, hz: float):
        """
        label:
            en: '%1 set frequency Hz %2'
        params:
            hz:
                name: hz
                type: float
                field: number
        """
        self._driver.set_frequency(hz)

    def get_frequency(self) -> float:
        """
        label:
            en: '%1 get frequency'
        """

        return self._driver.get_frequency()

    def set_pulse_width(self, channel: int, seconds: float):
        """
        label:
            en: '%1 set pulse width channel %2 seconds %3'
        params:
            channel:
                name: channel
                type: int
                field: number
            seconds:
                name: seconds
                type: float
                field: number
        """
        self._driver.set_pulse_width(channel, seconds)

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

    def restart(self):
        """
        label:
            en: '%1 restart'
        """
        self._driver.restart()

    def set_output_mode(self, open_drain: int, invert: int, off_state: int):
        """
        label:
            en: '%1 set output mode open drain %2 invert %3 off state %4'
        params:
            open_drain:
                name: open_drain
                type: int
                field: number
            invert:
                name: invert
                type: int
                field: number
            off_state:
                name: off_state
                type: int
                field: number
        """
        self._driver.set_output_mode(bool(open_drain), bool(invert), off_state)

    def set_update_on_ack(self, enabled: int):
        """
        label:
            en: '%1 set update on ACK enabled %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_update_on_ack(bool(enabled))

    def set_allcall(self, enabled: int, address: int = 112):
        """
        label:
            en: '%1 set all-call enabled %2 address %3'
        params:
            enabled:
                name: enabled
                type: int
                field: number
            address:
                name: address
                type: int
                default: '112'
                field: number
        """
        self._driver.set_allcall(bool(enabled), address)

    def set_subaddress(self, index: int, address: int, enabled: int):
        """
        label:
            en: '%1 set sub address index %2 address %3 enabled %4'
        params:
            index:
                name: index
                type: int
                field: number
            address:
                name: address
                type: int
                field: number
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_subaddress(index, address, bool(enabled))

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
