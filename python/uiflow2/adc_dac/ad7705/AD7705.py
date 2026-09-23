"""
file     AD7705
time     2026-09-21
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.adc_dac.ad7705 import AD7705Full


class AD7705:
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

    def __init__(self, bus: int = 0, cs_pin: int = 5, vref: float = 2.5, mclk_hz: int = 2457600):
        """
        label:
            en: '%1 init SPI bus %2 CS pin %3 Vref (V) %4 MCLK (Hz) %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
                min: '0'
                max: '255'
            vref:
                name: vref
                type: float
                default: '2.5'
                field: number
            mclk_hz:
                name: mclk_hz
                type: int
                default: '2457600'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin, polarity=1, phase=1)
        self._driver = AD7705Full(connection, vref, mclk_hz)

    def configure(self, channel: int = 1, gain: int = 1, bipolar: int = 1, buffered: int = 0, output_rate_hz: int = 50):
        """
        label:
            en: '%1 configure channel %2 gain %3 bipolar %4 buffered %5 output_rate_hz %6'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
            gain:
                name: gain
                type: int
                default: '1'
                field: number
            bipolar:
                name: bipolar
                type: int
                default: '1'
                field: number
            buffered:
                name: buffered
                type: int
                default: '0'
                field: number
            output_rate_hz:
                name: output_rate_hz
                type: int
                default: '50'
                field: number
        """
        self._driver.configure(channel=channel, gain=gain, bipolar=bool(bipolar), buffered=bool(buffered), output_rate_hz=output_rate_hz)

    def read_raw(self, channel: int = 1) -> int:
        """
        label:
            en: '%1 read raw (channel %2)'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        return self._driver.read_raw(channel=channel)

    def read_voltage(self, channel: int = 1) -> float:
        """
        label:
            en: '%1 read voltage V (channel %2)'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        return self._driver.read_voltage(channel=channel)

    def self_calibrate(self, channel: int = 1):
        """
        label:
            en: '%1 self_calibrate channel %2'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.self_calibrate(channel=channel)

    def system_calibrate_zero(self, channel: int = 1):
        """
        label:
            en: '%1 system calibrate zero channel %2'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.system_calibrate_zero(channel=channel)

    def system_calibrate_full(self, channel: int = 1):
        """
        label:
            en: '%1 system calibrate full channel %2'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.system_calibrate_full(channel=channel)

    def get_offset_calibration(self, channel: int = 1) -> int:
        """
        label:
            en: '%1 get offset cal (channel %2)'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        return self._driver.get_offset_calibration(channel=channel)

    def set_offset_calibration(self, value: int, channel: int = 1):
        """
        label:
            en: '%1 set offset cal channel %2 value %3'
        params:
            value:
                name: value
                type: int
                field: number
                min: '0'
                max: '16777215'
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.set_offset_calibration(value, channel=channel)

    def get_gain_calibration(self, channel: int = 1) -> int:
        """
        label:
            en: '%1 get gain cal (channel %2)'
        params:
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        return self._driver.get_gain_calibration(channel=channel)

    def set_gain_calibration(self, value: int, channel: int = 1):
        """
        label:
            en: '%1 set gain cal channel %2 value %3'
        params:
            value:
                name: value
                type: int
                field: number
                min: '0'
                max: '16777215'
            channel:
                name: channel
                type: int
                default: '1'
                field: number
                min: '1'
                max: '2'
        """
        self._driver.set_gain_calibration(value, channel=channel)

    def standby(self):
        """
        label:
            en: '%1 standby'
        """
        self._driver.standby()

    def wakeup(self):
        """
        label:
            en: '%1 wakeup'
        """
        self._driver.wakeup()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
