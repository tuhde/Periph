"""
file     DS1881
time     2026-11-12
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ds1881 import DS1881Full


class DS1881:
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

    def __init__(self, bus: int = 0):
        """
        label:
            en: '%1 init bus %2'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
        """
        connection = I2CConnection(0x28, bus=bus)
        self._driver = DS1881Full(connection)
        self._driver.init()

    def set_attenuation(self, channel: int, db: float):
        """
        label:
            en: '{lab}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            db:
                name: db
                type: float
                default: '10.0'
                field: number
        """
        self._driver.set_attenuation(channel, db)

    def get_attenuation(self, channel: int) -> float:
        """
        label:
            en: '{lab}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.get_attenuation(channel)

    def mute(self, channel: int):
        """
        label:
            en: '{lab}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.mute(channel)

    def set_position(self, channel: int, position: int):
        """
        label:
            en: '{lab}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            position:
                name: position
                type: int
                default: '0'
                field: number
                min: '0'
                max: '63'
        """
        self._driver.set_position(channel, position)

    def get_position(self, channel: int) -> int:
        """
        label:
            en: '{lab}'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        return self._driver.get_position(channel)

    def set_both(self, position0: int, position1: int):
        """
        label:
            en: '{lab}'
        params:
            position0:
                name: position0
                type: int
                default: '0'
                field: number
                min: '0'
                max: '63'
            position1:
                name: position1
                type: int
                default: '0'
                field: number
                min: '0'
                max: '63'
        """
        self._driver.set_both(position0, position1)

    def set_attenuation_both(self, db0: float, db1: float):
        """
        label:
            en: '{lab}'
        params:
            db0:
                name: db0
                type: float
                default: '10.0'
                field: number
            db1:
                name: db1
                type: float
                default: '10.0'
                field: number
        """
        self._driver.set_attenuation_both(db0, db1)

    def set_option(self, option: int):
        """
        label:
            en: '{lab}'
        params:
            option:
                name: option
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.set_option(option)

    def set_zero_crossing(self, enabled: int):
        """
        label:
            en: '{lab}'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_zero_crossing(bool(enabled))

    def set_nonvolatile(self, enabled: int):
        """
        label:
            en: '{lab}'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.set_nonvolatile(bool(enabled))

    def store(self):
        """
        label:
            en: '{lab}'
        """
        self._driver.store()

    def position_to_db(self, position: int) -> float:
        """
        label:
            en: '{lab}'
        params:
            position:
                name: position
                type: int
                default: '0'
                field: number
                min: '0'
                max: '63'
        """
        return self._driver.position_to_db(position)

    def db_to_position(self, db: float) -> int:
        """
        label:
            en: '{lab}'
        params:
            db:
                name: db
                type: float
                default: '10.0'
                field: number
        """
        return self._driver.db_to_position(db)

    def max_position(self) -> int:
        """
        label:
            en: '{lab}'
        """
        return self._driver.max_position()

    def reset(self):
        """
        label:
            en: '{lab}'
        """
        self._driver.reset()
