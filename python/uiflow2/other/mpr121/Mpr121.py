"""
file     Mpr121
time     2026-09-14
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.other.mpr121 import Mpr121Full


class Mpr121:
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

    def __init__(self, bus: int = 0, address: int = 90):
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
                default: '90'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = Mpr121Full(connection)

    def touched(self) -> int:
        """
        label:
            en: '%1 touched bitmask (12-bit)'
        """
        return self._driver.touched()

    def is_touched(self, electrode: int = 0) -> bool:
        """
        label:
            en: '%1 is_touched electrode %2'
        params:
            electrode:
                name: electrode
                type: int
                default: '0'
                field: number
        """
        return self._driver.is_touched(electrode)

    def filtered(self, electrode: int = 0) -> int:
        """
        label:
            en: '%1 filtered electrode %2'
        params:
            electrode:
                name: electrode
                type: int
                default: '0'
                field: number
        """
        return self._driver.filtered(electrode)

    def baseline(self, electrode: int = 0) -> int:
        """
        label:
            en: '%1 baseline electrode %2'
        params:
            electrode:
                name: electrode
                type: int
                default: '0'
                field: number
        """
        return self._driver.baseline(electrode)

    def configure_thresholds(self, electrode: int = 0, touch: int = 12, release: int = 6):
        """
        label:
            en: '%1 configure thresholds electrode %2 touch %3 release %4'
        params:
            electrode:
                name: electrode
                type: int
                default: '0'
                field: number
            touch:
                name: touch
                type: int
                default: '12'
                field: number
            release:
                name: release
                type: int
                default: '6'
                field: number
        """
        self._driver.configure_thresholds(electrode, touch=touch, release=release)

    def configure_all_thresholds(self, touch: int = 12, release: int = 6):
        """
        label:
            en: '%1 configure all thresholds touch %2 release %3'
        params:
            touch:
                name: touch
                type: int
                default: '12'
                field: number
            release:
                name: release
                type: int
                default: '6'
                field: number
        """
        self._driver.configure_all_thresholds(touch=touch, release=release)

    def configure_sampling(self, cdc: int = 16, cdt: int = 1, ffi: int = 0, sfi: int = 0, esi: int = 4):
        """
        label:
            en: '%1 configure sampling cdc %2 cdt %3 ffi %4 sfi %5 esi %6'
        params:
            cdc:
                name: cdc
                type: int
                default: '16'
                field: number
            cdt:
                name: cdt
                type: int
                default: '1'
                field: number
            ffi:
                name: ffi
                type: int
                default: '0'
                field: number
            sfi:
                name: sfi
                type: int
                default: '0'
                field: number
            esi:
                name: esi
                type: int
                default: '4'
                field: number
        """
        self._driver.configure_sampling(cdc=cdc, cdt=cdt, ffi=ffi, sfi=sfi, esi=esi)

    def configure_debounce(self, touch: int = 1, release: int = 1):
        """
        label:
            en: '%1 configure debounce touch %2 release %3'
        params:
            touch:
                name: touch
                type: int
                default: '1'
                field: number
            release:
                name: release
                type: int
                default: '1'
                field: number
        """
        self._driver.configure_debounce(touch=touch, release=release)

    def stop(self):
        """
        label:
            en: '%1 stop (enter Stop Mode)'
        """
        self._driver.stop()

    def start(self, n_electrodes: int = 12, cl: int = 2, eleprox_en: int = 0):
        """
        label:
            en: '%1 start n_electrodes %2 cl %3 eleprox_en %4'
        params:
            n_electrodes:
                name: n_electrodes
                type: int
                default: '12'
                field: number
            cl:
                name: cl
                type: int
                default: '2'
                field: number
            eleprox_en:
                name: eleprox_en
                type: int
                default: '0'
                field: number
        """
        self._driver.start(n_electrodes=n_electrodes, cl=cl, eleprox_en=eleprox_en)

    def reset(self):
        """
        label:
            en: '%1 soft reset'
        """
        self._driver.reset()

    def clear_overcurrent(self):
        """
        label:
            en: '%1 clear overcurrent'
        """
        self._driver.clear_overcurrent()
