"""
file     HY3131
time     2026-11-02
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.adc_dac.hy3131 import HY3131Full


class HY3131:
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

    def __init__(self, bus: int = 0, cs_pin: int = 5, vref: float = 1.2):
        """
        label:
            en: '%1 init SPI bus %2 CS pin %3 REFO (V) %4'
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
                default: '1.2'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin, polarity=0, phase=0)
        self._driver = HY3131Full(connection, vref)

    def read_raw(self) -> int:
        """
        label:
            en: '%1 read AD1 raw code'
        """
        return self._driver.read_raw()

    def read_voltage(self) -> float:
        """
        label:
            en: '%1 read AD1 voltage (V)'
        """
        return self._driver.read_voltage()

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 AD1 data ready?'
        """
        return self._driver.data_ready()

    def configure_ad1(self, osr: int = 7, chop: int = 0, input_gain: int = 0, ref_gain: int = 0, offset: int = 0, buffers: int = 0):
        """
        label:
            en: '%1 configure AD1 osr (0-7) %2 chop (0-3) %3 input gain (0-3) %4 ref gain (0-1) %5 offset (0-7) %6 buffers (0-15) %7'
        params:
            osr:
                name: osr
                type: int
                default: '7'
                field: number
                min: '0'
                max: '7'
            chop:
                name: chop
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            input_gain:
                name: input_gain
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
            ref_gain:
                name: ref_gain
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            offset:
                name: offset
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
            buffers:
                name: buffers
                type: int
                default: '0'
                field: number
                min: '0'
                max: '15'
        """
        self._driver.configure_ad1(osr=osr, chop=chop, input_gain=input_gain, ref_gain=ref_gain, offset=offset, buffers=buffers)

    def set_ad1_input(self, pos: int = 10, neg: int = 3, filter_r: int = 2):
        """
        label:
            en: '%1 set AD1 input pos (0-15) %2 neg (0-7) %3 filter R (0-3) %4'
        params:
            pos:
                name: pos
                type: int
                default: '10'
                field: number
                min: '0'
                max: '15'
            neg:
                name: neg
                type: int
                default: '3'
                field: number
                min: '0'
                max: '7'
            filter_r:
                name: filter_r
                type: int
                default: '2'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.set_ad1_input(pos, neg, filter_r)

    def set_ad1_reference(self, high: int = 1, low: int = 1):
        """
        label:
            en: '%1 set AD1 reference high (0-6) %2 low (0-6) %3'
        params:
            high:
                name: high
                type: int
                default: '1'
                field: number
                min: '0'
                max: '6'
            low:
                name: low
                type: int
                default: '1'
                field: number
                min: '0'
                max: '6'
        """
        self._driver.set_ad1_reference(high, low)

    def read_temperature_code(self) -> int:
        """
        label:
            en: '%1 read temperature code'
        """
        return self._driver.read_temperature_code()

    def read_temperature(self, tcode_25: int = 0) -> float:
        """
        label:
            en: '%1 temperature (°C) from code at 25 °C %2'
        params:
            tcode_25:
                name: tcode_25
                type: int
                default: '0'
                field: number
        """
        return self._driver.read_temperature(tcode_25)

    def enable_ad2(self, osr: int = 5, input_gain: int = 1, source: int = 0):
        """
        label:
            en: '%1 enable AD2 osr (0-7) %2 input gain (0-3) %3 source (0-3) %4'
        params:
            osr:
                name: osr
                type: int
                default: '5'
                field: number
                min: '0'
                max: '7'
            input_gain:
                name: input_gain
                type: int
                default: '1'
                field: number
                min: '0'
                max: '3'
            source:
                name: source
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.enable_ad2(osr=osr, input_gain=input_gain, source=source)

    def read_ad2_raw(self) -> int:
        """
        label:
            en: '%1 read AD2 raw code'
        """
        return self._driver.read_ad2_raw()

    def enable_lpf(self, bw: int = 0):
        """
        label:
            en: '%1 enable low-pass filter bw (0-7) %2'
        params:
            bw:
                name: bw
                type: int
                default: '0'
                field: number
                min: '0'
                max: '7'
        """
        self._driver.enable_lpf(bw)

    def read_lpf_raw(self) -> int:
        """
        label:
            en: '%1 read LPF raw code'
        """
        return self._driver.read_lpf_raw()

    def enable_rms(self):
        """
        label:
            en: '%1 enable RMS converter'
        """
        self._driver.enable_rms()

    def read_rms(self) -> float:
        """
        label:
            en: '%1 read RMS (LSB)'
        """
        return self._driver.read_rms()

    def enable_peak_hold(self, source: int = 0):
        """
        label:
            en: '%1 enable peak hold source (0-3) %2'
        params:
            source:
                name: source
                type: int
                default: '0'
                field: number
                min: '0'
                max: '3'
        """
        self._driver.enable_peak_hold(source)

    def read_peak_min(self) -> int:
        """
        label:
            en: '%1 peak minimum code'
        """
        return self._driver.read_peak()[0]

    def read_peak_max(self) -> int:
        """
        label:
            en: '%1 peak maximum code'
        """
        return self._driver.read_peak()[1]

    def start_counter(self, gate_preload: int = 0):
        """
        label:
            en: '%1 start frequency counter gate preload (0-65535) %2'
        params:
            gate_preload:
                name: gate_preload
                type: int
                default: '0'
                field: number
                min: '0'
                max: '65535'
        """
        self._driver.start_counter(gate_preload)

    def read_flags(self) -> int:
        """
        label:
            en: '%1 read INTF flags'
        """
        return self._driver.read_flags()

    def enable_interrupts(self, mask: int = 4):
        """
        label:
            en: '%1 enable interrupts mask (0-31) %2'
        params:
            mask:
                name: mask
                type: int
                default: '4'
                field: number
                min: '0'
                max: '31'
        """
        self._driver.enable_interrupts(mask)

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
