"""
file     ES8311
time     2026-11-09
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.es8311 import ES8311Full


class ES8311:
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

    def __init__(self, bus: int = 0, address: int = 24, mclk_hz: int = 12288000, sample_rate_hz: int = 48000):
        """
        label:
            en: '%1 init bus %2 address %3 MCLK Hz %4 sample rate Hz %5'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '24'
                field: number
            mclk_hz:
                name: mclk_hz
                type: int
                default: '12288000'
                field: number
            sample_rate_hz:
                name: sample_rate_hz
                type: int
                default: '48000'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = ES8311Full(connection, mclk_hz, sample_rate_hz)

    def set_volume(self, db: float = -20.0):
        """
        label:
            en: '%1 set DAC volume (dB, -95.5..32) %2'
        params:
            db:
                name: db
                type: float
                default: '-20.0'
                field: number
        """
        self._driver.set_volume(db)

    def get_volume(self) -> float:
        """
        label:
            en: '%1 get DAC volume (dB)'
        """
        return self._driver.get_volume()

    def set_mute(self, muted: int = 0):
        """
        label:
            en: '%1 set DAC mute (1/0) %2'
        params:
            muted:
                name: muted
                type: int
                default: '0'
                field: number
        """
        self._driver.set_mute(bool(muted))

    def set_mic_gain(self, db: float = 24.0):
        """
        label:
            en: '%1 set mic PGA gain (dB, 0..30) %2'
        params:
            db:
                name: db
                type: float
                default: '24.0'
                field: number
        """
        self._driver.set_mic_gain(db)

    def get_mic_gain(self) -> float:
        """
        label:
            en: '%1 get mic PGA gain (dB)'
        """
        return self._driver.get_mic_gain()

    def reset(self):
        """
        label:
            en: '%1 software reset'
        """
        self._driver.reset()

    def power_up(self):
        """
        label:
            en: '%1 power up'
        """
        self._driver.power_up()

    def power_down(self):
        """
        label:
            en: '%1 power down (standby)'
        """
        self._driver.power_down()

    def set_low_power(self, dac: int = 0, pga: int = 0, pga_out: int = 0, adc: int = 0, adc_ref: int = 0, dac_ref: int = 0, flash: int = 0, integrator: int = 0):
        """
        label:
            en: '%1 set low-power bits (1/0) %2 %3 %4 %5 %6 %7 %8 %9'
        params:
            dac:
                name: dac
                type: int
                default: '0'
                field: number
            pga:
                name: pga
                type: int
                default: '0'
                field: number
            pga_out:
                name: pga_out
                type: int
                default: '0'
                field: number
            adc:
                name: adc
                type: int
                default: '0'
                field: number
            adc_ref:
                name: adc_ref
                type: int
                default: '0'
                field: number
            dac_ref:
                name: dac_ref
                type: int
                default: '0'
                field: number
            flash:
                name: flash
                type: int
                default: '0'
                field: number
            integrator:
                name: integrator
                type: int
                default: '0'
                field: number
        """
        self._driver.set_low_power(bool(dac), bool(pga), bool(pga_out), bool(adc), bool(adc_ref), bool(dac_ref), bool(flash), bool(integrator))

    def configure_clock(self, mclk_hz: int = 12288000, sample_rate_hz: int = 48000):
        """
        label:
            en: '%1 configure clock (MCLK Hz, sample rate Hz) %2 %3'
        params:
            mclk_hz:
                name: mclk_hz
                type: int
                default: '12288000'
                field: number
            sample_rate_hz:
                name: sample_rate_hz
                type: int
                default: '48000'
                field: number
        """
        self._driver.configure_clock(mclk_hz, sample_rate_hz)

    def configure_serial(self, format: str = 'i2s', word_length: int = 16, master: int = 0):
        """
        label:
            en: '%1 configure serial port (i2s/left_justified/dsp_a/dsp_b, bits, master 1/0) %2 %3 %4'
        params:
            format:
                name: format
                type: str
                default: 'i2s'
            word_length:
                name: word_length
                type: int
                default: '16'
                field: number
            master:
                name: master
                type: int
                default: '0'
                field: number
        """
        self._driver.configure_serial(format, word_length, bool(master))

    def set_tristate(self, bclk_lrck: int = 0, adcdat: int = 0):
        """
        label:
            en: '%1 set tri-state (BCLK/LRCK 1/0, ADCDAT 1/0) %2 %3'
        params:
            bclk_lrck:
                name: bclk_lrck
                type: int
                default: '0'
                field: number
            adcdat:
                name: adcdat
                type: int
                default: '0'
                field: number
        """
        self._driver.set_tristate(bool(bclk_lrck), bool(adcdat))

    def set_input(self, source: str = 'analog'):
        """
        label:
            en: '%1 set input (analog/digital/none) %2'
        params:
            source:
                name: source
                type: str
                default: 'analog'
        """
        self._driver.set_input(source)

    def configure_dmic(self, latch_falling_edge: int = 0):
        """
        label:
            en: '%1 set PDM latch edge (falling 1/0) %2'
        params:
            latch_falling_edge:
                name: latch_falling_edge
                type: int
                default: '0'
                field: number
        """
        self._driver.configure_dmic(bool(latch_falling_edge))

    def set_adc_volume(self, db: float = 0.0):
        """
        label:
            en: '%1 set ADC volume (dB, -95.5..32) %2'
        params:
            db:
                name: db
                type: float
                default: '0.0'
                field: number
        """
        self._driver.set_adc_volume(db)

    def configure_adc(self, scale_db: int = 24, osr: int = 64, double_speed: int = 0, hpf: int = 1):
        """
        label:
            en: '%1 configure ADC (scale dB, OSR, double speed 1/0, HPF 1/0) %2 %3 %4 %5'
        params:
            scale_db:
                name: scale_db
                type: int
                default: '24'
                field: number
            osr:
                name: osr
                type: int
                default: '64'
                field: number
            double_speed:
                name: double_speed
                type: int
                default: '0'
                field: number
            hpf:
                name: hpf
                type: int
                default: '1'
                field: number
        """
        self._driver.configure_adc(scale_db, osr, bool(double_speed), bool(hpf))

    def set_adc_ramp(self, rate: int = 0):
        """
        label:
            en: '%1 set ADC ramp rate (0..15) %2'
        params:
            rate:
                name: rate
                type: int
                default: '0'
                field: number
        """
        self._driver.set_adc_ramp(rate)

    def configure_alc(self, enabled: int = 1, max_level_db: float = -6.0, min_level_db: float = -30.1, window: int = 0, automute: int = 0, automute_window: int = 0, noise_gate_db: int = -96):
        """
        label:
            en: '%1 configure ALC (enable 1/0, max dB, min dB, window, automute 1/0, automute window, noise gate dB) %2 %3 %4 %5 %6 %7 %8'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
            max_level_db:
                name: max_level_db
                type: float
                default: '-6.0'
                field: number
            min_level_db:
                name: min_level_db
                type: float
                default: '-30.1'
                field: number
            window:
                name: window
                type: int
                default: '0'
                field: number
            automute:
                name: automute
                type: int
                default: '0'
                field: number
            automute_window:
                name: automute_window
                type: int
                default: '0'
                field: number
            noise_gate_db:
                name: noise_gate_db
                type: int
                default: '-96'
                field: number
        """
        self._driver.configure_alc(bool(enabled), max_level_db, min_level_db, window, bool(automute), automute_window, noise_gate_db)

    def set_dac_ramp(self, rate: int = 0):
        """
        label:
            en: '%1 set DAC ramp rate (0..15) %2'
        params:
            rate:
                name: rate
                type: int
                default: '0'
                field: number
        """
        self._driver.set_dac_ramp(rate)

    def configure_drc(self, enabled: int = 1, max_level_db: float = -30.1, min_level_db: float = -30.1, window: int = 0):
        """
        label:
            en: '%1 configure DRC (enable 1/0, max dB, min dB, window) %2 %3 %4 %5'
        params:
            enabled:
                name: enabled
                type: int
                default: '1'
                field: number
            max_level_db:
                name: max_level_db
                type: float
                default: '-30.1'
                field: number
            min_level_db:
                name: min_level_db
                type: float
                default: '-30.1'
                field: number
            window:
                name: window
                type: int
                default: '0'
                field: number
        """
        self._driver.configure_drc(bool(enabled), max_level_db, min_level_db, window)

    def set_dac_mute_options(self, dsm: int = 1, dem: int = 1, dsm_target: int = 0):
        """
        label:
            en: '%1 set DAC mute options (DSM, DEM, DSM target 1/0) %2 %3 %4'
        params:
            dsm:
                name: dsm
                type: int
                default: '1'
                field: number
            dem:
                name: dem
                type: int
                default: '1'
                field: number
            dsm_target:
                name: dsm_target
                type: int
                default: '0'
                field: number
        """
        self._driver.set_dac_mute_options(bool(dsm), bool(dem), bool(dsm_target))

    def set_loopback(self, enabled: int = 0):
        """
        label:
            en: '%1 set ADC-to-DAC loopback (1/0) %2'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
        """
        self._driver.set_loopback(bool(enabled))

    def set_adcdat_mode(self, mode: str = 'adc_adc'):
        """
        label:
            en: '%1 set ADCDAT mode (adc_adc/adc_zero/zero_adc/dac_adc/adc_dac/dac_dac) %2'
        params:
            mode:
                name: mode
                type: str
                default: 'adc_adc'
        """
        self._driver.set_adcdat_mode(mode)

    def set_sdp_mute(self, input: int = 0, output: int = 0):
        """
        label:
            en: '%1 mute serial port (input 1/0, output 1/0) %2 %3'
        params:
            input:
                name: input
                type: int
                default: '0'
                field: number
            output:
                name: output
                type: int
                default: '0'
                field: number
        """
        self._driver.set_sdp_mute(bool(input), bool(output))

    def read_register(self, register: int = 0) -> int:
        """
        label:
            en: '%1 read register %2'
        params:
            register:
                name: register
                type: int
                default: '0'
                field: number
        """
        return self._driver.read_register(register)

    def write_register(self, register: int = 0, value: int = 0):
        """
        label:
            en: '%1 write register %2 %3'
        params:
            register:
                name: register
                type: int
                default: '0'
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.write_register(register, value)
