"""
file     L3gd20h
time     2026-09-17
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.gyroscope.l3gd20h import L3GD20HFull


class L3gd20h:
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

    def __init__(self, bus: int = 1, address: int = 106):
        """
        label:
            en: '%1 init I2C bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '1'
                field: number
                min: '0'
                max: '7'
            address:
                name: address
                type: int
                default: '106'
                field: number
                min: '0'
                max: '127'
        """
        self._driver = L3GD20HFull(I2CConnection(address, bus=bus))

    def gyro(self) -> tuple:
        """
        label:
            en: '%1 read angular rate (rad/s)'
        """
        return self._driver.gyro()

    def configure(self, odr: int = 0, bw: int = 0, full_scale: int = 0):
        """
        label:
            en: '%1 configure ODR %2 BW %3 full_scale %4'
        params:
            odr:
                name: odr
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            bw:
                name: bw
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            full_scale:
                name: full_scale
                type: int
                default: '0'
                field: number
                min: '0'
                max: '2'
        """
        self._driver.configure(odr=odr, bw=bw, full_scale=full_scale)

    def gyro_raw(self) -> tuple:
        """
        label:
            en: '%1 read raw angular rate'
        """
        return self._driver.gyro_raw()

    def temperature(self) -> int:
        """
        label:
            en: '%1 read temperature (counts)'
        """
        return self._driver.temperature()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 data ready'
        """
        return self._driver.data_ready()

    def configure_hp_filter(self, mode: int = 0, cutoff: int = 0):
        """
        label:
            en: '%1 configure HPF mode %2 cutoff %3'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            cutoff:
                name: cutoff
                type: int
                default: '0'
                field: number
                min: '0'
                max: '15'
        """
        self._driver.configure_hp_filter(mode=mode, cutoff=cutoff)

    def enable_hp_filter(self, enable: int = 1):
        """
        label:
            en: '%1 enable HPF %2'
        params:
            enable:
                name: enable
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.enable_hp_filter(enable=bool(enable))

    def configure_fifo(self, mode: int = 1, watermark: int = 10):
        """
        label:
            en: '%1 configure FIFO mode %2 watermark %3'
        params:
            mode:
                name: mode
                type: int
                default: '1'
                field: number
                min: '0'
                max: '7'
            watermark:
                name: watermark
                type: int
                default: '10'
                field: number
                min: '0'
                max: '31'
        """
        self._driver.configure_fifo(mode=mode, watermark=watermark)

    def enable_fifo(self, enable: int = 1):
        """
        label:
            en: '%1 enable FIFO %2'
        params:
            enable:
                name: enable
                type: int
                default: '1'
                field: number
                min: '0'
                max: '1'
        """
        self._driver.enable_fifo(enable=bool(enable))

    def fifo_level(self) -> int:
        """
        label:
            en: '%1 FIFO level'
        """
        return self._driver.fifo_level()

    def read_fifo(self) -> list:
        """
        label:
            en: '%1 read FIFO'
        """
        return self._driver.read_fifo()

    def set_power_mode(self, mode: int = 0):
        """
        label:
            en: '%1 set power mode %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                min: '0'
                max: '2'
        """
        modes = ['normal', 'sleep', 'power_down']
        self._driver.set_power_mode(modes[mode])