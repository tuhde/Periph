"""
file     Apds9930
time     2026-09-10
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.apds_9930 import APDS9930Full


class Apds9930:
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

    def __init__(self, bus: int = 0, address: int = 57):
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
                default: '57'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = APDS9930Full(connection)

    def lux(self) -> float:
        """
        label:
            en: '%1 lux'
        """
        return self._driver.lux()

    def proximity(self) -> int:
        """
        label:
            en: '%1 proximity count (0-1023)'
        """
        return self._driver.proximity()

    def ch0(self) -> int:
        """
        label:
            en: '%1 Ch0 raw (visible + IR)'
        """
        return self._driver.ch0()

    def ch1(self) -> int:
        """
        label:
            en: '%1 Ch1 raw (IR only)'
        """
        return self._driver.ch1()

    def configure_als(self, atime: int = 219, again: int = 0, agl: int = 0):
        """
        label:
            en: '%1 configure ALS atime %2 again %3 agl %4'
        params:
            atime:
                name: atime
                type: int
                default: '219'
                field: number
            again:
                name: again
                type: int
                default: '0'
                field: number
            agl:
                name: agl
                type: int
                default: '0'
                field: number
        """
        self._driver.configure_als(atime=atime, again=again, agl=bool(agl))

    def configure_proximity(self, ppulse: int = 8, pgain: int = 0, pdrive: int = 0, pdl: int = 0, ptime: int = 255):
        """
        label:
            en: '%1 configure proximity ppulse %2 pgain %3 pdrive %4 pdl %5 ptime %6'
        params:
            ppulse:
                name: ppulse
                type: int
                default: '8'
                field: number
            pgain:
                name: pgain
                type: int
                default: '0'
                field: number
            pdrive:
                name: pdrive
                type: int
                default: '0'
                field: number
            pdl:
                name: pdl
                type: int
                default: '0'
                field: number
            ptime:
                name: ptime
                type: int
                default: '255'
                field: number
        """
        self._driver.configure_proximity(ppulse=ppulse, pgain=pgain, pdrive=pdrive,
                                         pdl=bool(pdl), ptime=ptime)

    def disable_wait(self):
        """
        label:
            en: '%1 disable wait timer'
        """
        self._driver.disable_wait()

    def set_als_thresholds(self, low: int = 0, high: int = 65535, persistence: int = 1):
        """
        label:
            en: '%1 set ALS thresholds low %2 high %3 persistence %4'
        params:
            low:
                name: low
                type: int
                default: '0'
                field: number
            high:
                name: high
                type: int
                default: '65535'
                field: number
            persistence:
                name: persistence
                type: int
                default: '1'
                field: number
        """
        self._driver.set_als_thresholds(low=low, high=high, persistence=persistence)

    def set_proximity_thresholds(self, low: int = 0, high: int = 1023, persistence: int = 1):
        """
        label:
            en: '%1 set proximity thresholds low %2 high %3 persistence %4'
        params:
            low:
                name: low
                type: int
                default: '0'
                field: number
            high:
                name: high
                type: int
                default: '1023'
                field: number
            persistence:
                name: persistence
                type: int
                default: '1'
                field: number
        """
        self._driver.set_proximity_thresholds(low=low, high=high, persistence=persistence)

    def clear_interrupt(self, channel: int = 0):
        """
        label:
            en: '%1 clear interrupt channel %2'
        params:
            channel:
                name: channel
                type: int
                default: '0'
                field: number
        """
        self._driver.clear_interrupt(channel)

    def set_proximity_offset(self, offset: int = 0):
        """
        label:
            en: '%1 set proximity offset %2'
        params:
            offset:
                name: offset
                type: int
                default: '0'
                field: number
        """
        self._driver.set_proximity_offset(offset)

    def sleep_after_interrupt(self, enable: int = 0):
        """
        label:
            en: '%1 sleep after interrupt %2'
        params:
            enable:
                name: enable
                type: int
                default: '0'
                field: number
        """
        self._driver.sleep_after_interrupt(bool(enable))