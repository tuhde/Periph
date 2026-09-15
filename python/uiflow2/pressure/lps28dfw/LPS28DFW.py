"""
file     LPS28DFW
time     2026-09-09
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps28dfw import LPS28DFWFull


class LPS28DFW:
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

    def __init__(self, bus: int = 0, address: int = 92):
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
                default: '92'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = LPS28DFWFull(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def read_pressure(self) -> float:
        """
        label:
            en: '%1 pressure (hPa)'
        """
        return self._driver.read_pressure()

    def configure(self, odr: int, avg: int, fs_mode: int) -> None:
        """
        label:
            en: '%1 configure ODR %2 AVG %3 FS mode %4'
        params:
            odr:
                name: odr
                type: int
                default: '4'
                field: number
            avg:
                name: avg
                type: int
                default: '2'
                field: number
            fs_mode:
                name: fs_mode
                type: int
                default: '0'
                field: number
        """
        self._driver.configure(odr=odr, avg=avg, fs_mode=fs_mode, lpf_en=True, lpf_cfg=0)

    def set_threshold(self, threshold_hpa: float, high: bool, low: bool) -> None:
        """
        label:
            en: '%1 threshold %2 hPa high %3 low %4'
        params:
            threshold_hpa:
                name: threshold_hpa
                type: float
                field: number
            high:
                name: high
                type: bool
                default: 'true'
                field: dropdown
                data: ['true', 'false']
            low:
                name: low
                type: bool
                default: 'true'
                field: dropdown
                data: ['true', 'false']
        """
        self._driver.set_threshold(threshold_hpa, high=high, low=low)