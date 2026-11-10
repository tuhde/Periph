"""ES8311 — low-power mono audio codec, I²C control port (Everest Semiconductor).

Mono ADC (analog differential microphone or PDM digital microphone, 0–30 dB
PGA, ALC, noise gate, equalizer) and mono DAC (digital volume, DRC, equalizer)
with one I²S / left-justified / DSP-A / DSP-B digital audio port. This driver
covers the I²C control port only: it configures clocks, formats, volume and
the analog paths; the audio stream itself runs over a separate I²S port that
this library does not provide. The same file is used on MicroPython,
CircuitPython and Linux hosts.

Address 0x18 (CE low) or 0x19 (CE high). Registers are 8 bit, 1-byte address.
The user guide leaves the start-up sequence and registers 0x04, 0x0B–0x0C and
0x10–0x13 undocumented; the driver uses the values of the Everest / Espressif
reference driver for them. Verify on hardware before relying on them.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device.
"""

import time

I2C_ADDRESS = 0x18

_REG_RESET = 0x00
_REG_CLK1 = 0x01
_REG_CLK2 = 0x02
_REG_CLK3 = 0x03
_REG_CLK4 = 0x04
_REG_CLK5 = 0x05
_REG_CLK6 = 0x06
_REG_CLK7 = 0x07
_REG_CLK8 = 0x08
_REG_SDP_IN = 0x09
_REG_SDP_OUT = 0x0A
_REG_SYS_0B = 0x0B
_REG_SYS_0C = 0x0C
_REG_SYS_PWR = 0x0D
_REG_SYS_ADC = 0x0E
_REG_SYS_LP = 0x0F
_REG_SYS_10 = 0x10
_REG_SYS_11 = 0x11
_REG_SYS_DAC = 0x12
_REG_SYS_HP = 0x13
_REG_SYS_MIC = 0x14
_REG_ADC_RAMP = 0x15
_REG_ADC_SCALE = 0x16
_REG_ADC_VOLUME = 0x17
_REG_ADC_ALC1 = 0x18
_REG_ADC_ALC2 = 0x19
_REG_ADC_AUTOMUTE = 0x1A
_REG_ADC_HPF1 = 0x1B
_REG_ADC_HPF2_EQ = 0x1C
_REG_ADCEQ = 0x1D
_REG_DAC_MUTE = 0x31
_REG_DAC_VOLUME = 0x32
_REG_DAC_DRC1 = 0x34
_REG_DAC_DRC2 = 0x35
_REG_DAC_RAMP_EQ = 0x37
_REG_DACEQ = 0x38
_REG_GPIO = 0x44
_REG_CHIP_ID1 = 0xFD
_REG_CHIP_ID2 = 0xFE

# (mclk_hz, sample_rate_hz) -> (0x02, 0x03, 0x04, 0x05) from the guide's clock table
_CLOCK_PRESETS = {
    (24576000, 32000): (0x40, 0x10, 0x10, 0x00),
    (24576000, 48000): (0x20, 0x10, 0x10, 0x00),
    (12288000, 32000): (0x48, 0x10, 0x10, 0x00),
    (12288000, 48000): (0x00, 0x10, 0x10, 0x00),
    (18432000, 32000): (0x20, 0x12, 0x12, 0x00),
    (18432000, 48000): (0x48, 0x10, 0x10, 0x00),
    (11289600, 44100): (0x00, 0x10, 0x10, 0x00),
    (12000000, 32000): (0x00, 0x17, 0x17, 0x00),
    (12000000, 44100): (0x00, 0x11, 0x11, 0x00),
    (12000000, 48000): (0x98, 0x19, 0x19, 0x00),
    (26000000, 32000): (0x40, 0x10, 0x10, 0x00),
    (3000000, 32000): (0x18, 0x52, 0x17, 0x41),
    (25000000, 32000): (0x48, 0x55, 0x10, 0x21),
    (25000000, 48000): (0x28, 0x55, 0x10, 0x21),
}

_PREFERRED_RATIOS = (256, 384, 512, 768, 1024, 1536)
_BCLK_DIVIDERS = (22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72)  # DIV_BCLK codes 20..31

_FORMATS = {'i2s': 0, 'left_justified': 1, 'dsp_a': 3, 'dsp_b': 3}
_WORD_LENGTHS = {24: 0, 20: 1, 18: 2, 16: 3, 32: 4}
_ADCDAT_MODES = {'adc_adc': 0, 'adc_zero': 1, 'zero_adc': 2, 'dac_adc': 4, 'adc_dac': 5, 'dac_dac': 6}

ALC_LEVELS_DB = (-30.1, -24.1, -20.6, -18.1, -16.1, -14.5, -13.2, -12.0,
                 -11.0, -10.1, -9.3, -8.5, -7.8, -7.2, -6.6, -6.0)
NOISE_GATE_DB = (-96, -90, -84, -78, -72, -66, -60, -54, -51, -48, -45, -42, -39, -36, -33, -30)


def _nearest(table, value):
    """Index of the table entry closest to value."""
    best = 0
    for i in range(len(table)):
        if abs(table[i] - value) < abs(table[best] - value):
            best = i
    return best


def _volume_to_reg(db):
    """dB (−95.5…+32, 0.5 dB steps) → 8-bit volume register code."""
    code = int(round(db / 0.5)) + 191
    return max(0, min(255, code))


def _solve_clock(mclk_hz, fs, dvdd_volts):
    """Return (0x02, 0x03, 0x04, 0x05) register values for the MCLK / fs pair.

    Tries the guide's preset table first (3.3 V only), then searches the pre-divider,
    multiplier and ADC/DAC divider for an allowed MCLK:LRCK ratio.

    Raises:
        ValueError: If no divider set satisfies the ratio and frequency limits.
    """
    low = dvdd_volts < 2.5
    if not low and (mclk_hz, fs) in _CLOCK_PRESETS:
        return _CLOCK_PRESETS[(mclk_hz, fs)]
    pre_limit = 4.5e6 if low else 10e6
    clk_limit = 13e6 if low else 35e6
    best = None
    for dp in range(1, 9):
        if mclk_hz / dp >= pre_limit:
            continue
        for mi, mult in enumerate((1, 2, 4, 8)):
            imclk = mclk_hz / dp * mult
            for div in range(16):
                clk = imclk / (div + 1)
                if clk >= clk_limit:
                    continue
                ratio = clk / fs
                if ratio != int(ratio):
                    continue
                ratio = int(ratio)
                if ratio % 16 or not 256 <= ratio <= 1008:
                    continue
                rank = _PREFERRED_RATIOS.index(ratio) if ratio in _PREFERRED_RATIOS else 99
                key = (rank, dp, mult, div)
                if best is None or key < best[0]:
                    best = (key, ((dp - 1) << 5) | (mi << 3), ratio // 16, div)
    if best is None:
        raise ValueError('no valid ES8311 clock divider for MCLK %d Hz / fs %d Hz' % (mclk_hz, fs))
    _, r02, osr, div = best
    return (r02, osr, osr, (div << 4) | div)


class ES8311Minimal:
    """ES8311 minimal interface — bring-up for voice playback and recording.

    Runs the initialization sequence at construction: software reset, clock
    tree for the given MCLK / sample rate, I²S 16-bit slave, analog path,
    analog microphone at 24 dB, ADC 0 dB, DAC volume −95.5 dB (silent until
    ``set_volume``). The audio stream itself is provided separately over I²S.

    Args:
        connection: RegisterConnection pointing at the device (0x18 / 0x19).
        mclk_hz: Master clock frequency in Hz (default 12.288 MHz).
        sample_rate_hz: Audio sample rate in Hz (default 48 kHz).
        dvdd_volts: DVDD supply, selects the 3.3 V or 1.8 V clock limits.

    Raises:
        ValueError: If the MCLK : sample-rate combination has no valid divider set.
    """

    def __init__(self, connection, mclk_hz=12288000, sample_rate_hz=48000, dvdd_volts=3.3):
        self._conn = connection
        self._mclk_hz = mclk_hz
        self._fs = sample_rate_hz
        self._dvdd = dvdd_volts
        clk = _solve_clock(mclk_hz, sample_rate_hz, dvdd_volts)
        self._soft_reset()
        self._conn.write_reg(_REG_CLK1, 0x3F)
        self._write_clock(clk)
        self._conn.write_reg(_REG_CLK6, 0x03)
        self._conn.write_reg(_REG_CLK7, 0x00)
        self._conn.write_reg(_REG_CLK8, 0xFF)
        self._conn.write_reg(_REG_SDP_IN, 0x0C)
        self._conn.write_reg(_REG_SDP_OUT, 0x0C)
        self._power_up_analog()
        self._conn.write_reg(_REG_ADC_SCALE, 0x04)
        self._conn.write_reg(_REG_SYS_MIC, 0x18)
        self._conn.write_reg(_REG_ADC_VOLUME, 0xBF)
        self._conn.write_reg(_REG_DAC_VOLUME, 0x00)

    def _read(self, reg):
        return self._conn.read_reg(reg, 1)[0]

    def _update(self, reg, mask, value):
        self._conn.write_reg(reg, (self._read(reg) & ~mask & 0xFF) | (value & mask))

    def _soft_reset(self):
        self._conn.write_reg(_REG_RESET, 0x1F)
        time.sleep(0.02)
        self._conn.write_reg(_REG_RESET, 0x00)
        self._conn.write_reg(_REG_RESET, 0x80)

    def _write_clock(self, clk):
        self._conn.write_reg(_REG_CLK2, clk[0])
        self._conn.write_reg(_REG_CLK3, clk[1])
        self._conn.write_reg(_REG_CLK4, clk[2])
        self._conn.write_reg(_REG_CLK5, clk[3])

    def _power_up_analog(self):
        self._conn.write_reg(_REG_SYS_0B, 0x00)
        self._conn.write_reg(_REG_SYS_0C, 0x00)
        self._conn.write_reg(_REG_SYS_10, 0x1F)
        self._conn.write_reg(_REG_SYS_11, 0x7F)
        self._conn.write_reg(_REG_SYS_PWR, 0x01)
        self._conn.write_reg(_REG_SYS_ADC, 0x02)
        self._conn.write_reg(_REG_SYS_DAC, 0x00)
        self._conn.write_reg(_REG_SYS_HP, 0x10)
        self._conn.write_reg(_REG_ADC_HPF2_EQ, 0x6A)
        self._conn.write_reg(_REG_DAC_RAMP_EQ, 0x08)

    def set_volume(self, db):
        """Set the DAC digital volume.

        Args:
            db: −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
        """
        self._conn.write_reg(_REG_DAC_VOLUME, _volume_to_reg(db))

    def set_mute(self, muted):
        """Mute or unmute the DAC (DSM and DEM mute together, 0x31 bits 6 and 5).

        Args:
            muted: True to mute.
        """
        self._update(_REG_DAC_MUTE, 0x60, 0x60 if muted else 0x00)

    def set_mic_gain(self, db):
        """Set the analog microphone PGA gain.

        Args:
            db: 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
        """
        self._update(_REG_SYS_MIC, 0x0F, max(0, min(10, int(round(db / 3.0)))))


class ES8311Full(ES8311Minimal):
    """ES8311 full interface — adds clock tree, serial port, microphone / ADC / DAC
    dynamics, equalizers, routing, power control and raw register access.

    Args:
        connection: RegisterConnection pointing at the device.
        mclk_hz: Master clock frequency in Hz (default 12.288 MHz).
        sample_rate_hz: Audio sample rate in Hz (default 48 kHz).
        dvdd_volts: DVDD supply, selects the 3.3 V or 1.8 V clock limits.
    """

    def get_volume(self):
        """Read back the DAC volume.

        Returns:
            float: dB (−95.5 … +32).
        """
        return (self._read(_REG_DAC_VOLUME) - 191) * 0.5

    def get_mic_gain(self):
        """Read back the analog microphone PGA gain.

        Returns:
            float: dB (0 … 30).
        """
        return (self._read(_REG_SYS_MIC) & 0x0F) * 3.0

    def reset(self):
        """Software reset; registers return to their reset values. Run configuration again."""
        self._soft_reset()

    def power_up(self):
        """Re-enable the analog, reference and clock blocks (vendor values) and set CSM_ON."""
        self._update(_REG_RESET, 0x9F, 0x00)
        self._update(_REG_RESET, 0x80, 0x80)
        self._conn.write_reg(_REG_CLK1, 0x3F)
        self._power_up_analog()

    def power_down(self):
        """Standby: mute the DAC, power down the analog blocks, reset all and clear CSM_ON."""
        self.set_mute(True)
        self._update(_REG_SYS_PWR, 0xFC, 0xFC)
        self._update(_REG_SYS_ADC, 0x70, 0x70)
        self._update(_REG_SYS_PWR, 0x03, 0x00)
        self._conn.write_reg(_REG_RESET, 0x1F)

    def set_low_power(self, dac=False, pga=False, pga_out=False, adc=False,
                      adc_ref=False, dac_ref=False, flash=False, integrator=False):
        """Set the low-power bits of register 0x0F (slightly lower THD+N / SNR).

        Args:
            dac: LPDAC.
            pga: LPPGA.
            pga_out: LPPGAOUT.
            adc: LPVCMMOD (ADC).
            adc_ref: LPADCVRP.
            dac_ref: LPDACVRP.
            flash: LPFLASH.
            integrator: LPINT1.
        """
        flags = (dac, pga, pga_out, adc, adc_ref, dac_ref, flash, integrator)
        value = 0
        for i, f in enumerate(flags):
            if f:
                value |= 0x80 >> i
        self._conn.write_reg(_REG_SYS_LP, value)

    def configure_clock(self, mclk_hz, sample_rate_hz, mclk_from_bclk=False, mclk_invert=False):
        """Program the clock tree (registers 0x01–0x05) from a preset or computed dividers.

        Args:
            mclk_hz: Master clock frequency in Hz.
            sample_rate_hz: Audio sample rate in Hz.
            mclk_from_bclk: True to take the master clock from the SCLK (BCLK) pin.
            mclk_invert: True to invert the master clock.

        Raises:
            ValueError: If no valid divider set exists for the combination.
        """
        clk = _solve_clock(mclk_hz, sample_rate_hz, self._dvdd)
        self._mclk_hz = mclk_hz
        self._fs = sample_rate_hz
        self._conn.write_reg(_REG_CLK1, 0x3F | (0x80 if mclk_from_bclk else 0) | (0x40 if mclk_invert else 0))
        self._write_clock(clk)

    def configure_serial(self, format='i2s', word_length=16, master=False, bclk_invert=False,
                         lrck_invert=False, dac_channel='left', bclk_div=None, lrck_div=None,
                         continuous_bclk=True):
        """Configure the digital audio port (0x09, 0x0A, RESET.MSC, 0x06–0x08).

        Args:
            format: "i2s", "left_justified", "dsp_a" or "dsp_b".
            word_length: 16, 18, 20, 24 or 32 bit.
            master: True for master mode (chip drives BCLK / LRCK).
            bclk_invert: Invert BCLK.
            lrck_invert: Invert the LRCK polarity (DSP: MSB on the first BCLK edge).
            dac_channel: "left" or "right" data to the DAC.
            bclk_div: Master BCLK divider (None = computed from the clock configuration).
            lrck_div: Master LRCK divider (None = computed).
            continuous_bclk: Keep BCLK running when idle (master mode).

        Raises:
            ValueError: For an unknown format or word length.
        """
        if format not in _FORMATS:
            raise ValueError('unknown format')
        if word_length not in _WORD_LENGTHS:
            raise ValueError('word_length must be 16, 18, 20, 24 or 32')
        lrp = (1 if lrck_invert else 0) ^ (1 if format == 'dsp_b' else 0)
        base = (_WORD_LENGTHS[word_length] << 2) | _FORMATS[format] | (lrp << 5)
        self._conn.write_reg(_REG_SDP_IN, base | (0x80 if dac_channel == 'right' else 0x00)
                             | (self._read(_REG_SDP_IN) & 0x40))
        self._conn.write_reg(_REG_SDP_OUT, base | (self._read(_REG_SDP_OUT) & 0x40))
        if master:
            imclk = self._internal_clock()
            if bclk_div is None:
                bclk_div = imclk // (self._fs * 2 * word_length)
            if lrck_div is None:
                lrck_div = imclk // self._fs
            code = bclk_div - 1 if bclk_div <= 20 else _BCLK_DIVIDERS.index(bclk_div) + 20
            self._conn.write_reg(_REG_CLK6, (0x00 if continuous_bclk else 0x40) | (0x20 if bclk_invert else 0)
                                 | code)
            self._update(_REG_CLK7, 0x0F, ((lrck_div - 1) >> 8) & 0x0F)
            self._conn.write_reg(_REG_CLK8, (lrck_div - 1) & 0xFF)
            self._update(_REG_RESET, 0x44, 0x40)
        else:
            self._update(_REG_CLK6, 0x20, 0x20 if bclk_invert else 0)
            self._update(_REG_RESET, 0x44, 0x04)

    def _internal_clock(self):
        r02 = self._read(_REG_CLK2)
        return int(self._mclk_hz / (((r02 >> 5) & 7) + 1) * (1, 2, 4, 8)[(r02 >> 3) & 3])

    def set_tristate(self, bclk_lrck=False, adcdat=False):
        """Tri-state the BCLK / LRCK pins and / or ADCDAT (0x07 bits 5:4).

        Args:
            bclk_lrck: Tri-state BCLK and LRCK.
            adcdat: Tri-state ADCDAT.
        """
        self._update(_REG_CLK7, 0x30, (0x20 if bclk_lrck else 0) | (0x10 if adcdat else 0))

    def set_input(self, source='analog'):
        """Select the microphone input.

        Args:
            source: "analog" (MIC1P/MIC1N), "digital" (PDM on MIC1P) or "none".

        Raises:
            ValueError: For an unknown source.
        """
        if source == 'analog':
            self._update(_REG_SYS_MIC, 0x70, 0x10)
            self._update(_REG_SYS_ADC, 0x40, 0x00)
        elif source == 'digital':
            self._update(_REG_SYS_MIC, 0x70, 0x40)
            self._update(_REG_SYS_ADC, 0x40, 0x40)
        elif source == 'none':
            self._update(_REG_SYS_MIC, 0x70, 0x00)
            self._update(_REG_SYS_ADC, 0x40, 0x40)
        else:
            raise ValueError('source must be analog, digital or none')

    def configure_dmic(self, latch_falling_edge=False):
        """Select the PDM data latch edge (0x15 bit 0).

        Args:
            latch_falling_edge: True to latch on the falling clock edge.
        """
        self._update(_REG_ADC_RAMP, 0x01, 0x01 if latch_falling_edge else 0x00)

    def set_adc_volume(self, db):
        """Set the ADC digital volume (ALC maximum gain when the ALC is on).

        Args:
            db: −95.5 … +32 dB in 0.5 dB steps.
        """
        self._conn.write_reg(_REG_ADC_VOLUME, _volume_to_reg(db))

    def configure_adc(self, scale_db=24, osr=64, double_speed=False, hpf=True, hpf_coeffs=(0x0C, 0x0C)):
        """Configure the ADC digital path.

        Args:
            scale_db: Digital gain scale-up 0 … 42 dB in 6 dB steps.
            osr: Oversampling ratio in × fs (single speed 60–252, double speed 32–126).
            double_speed: Select the double-speed ADC.
            hpf: True for the dynamic high-pass filter, False to freeze the DC offset.
            hpf_coeffs: (stage-1, stage-2) high-pass coefficients 0–31.

        Raises:
            ValueError: If osr is outside the allowed range.
        """
        code = osr // 2 if double_speed else osr // 4
        if not 15 <= code <= 63 or (double_speed and code < 16):
            raise ValueError('osr out of range')
        self._update(_REG_ADC_SCALE, 0x07, max(0, min(7, int(round(scale_db / 6.0)))))
        self._conn.write_reg(_REG_CLK3, (0x40 if double_speed else 0x00) | code)
        self._update(_REG_ADC_HPF2_EQ, 0x3F, (0x20 if hpf else 0x00) | (hpf_coeffs[1] & 0x1F))
        self._update(_REG_ADC_HPF1, 0x1F, hpf_coeffs[0] & 0x1F)

    def set_adc_ramp(self, rate):
        """Set the ADC volume soft-ramp rate (0x15 bits 7:4).

        Args:
            rate: 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
        """
        self._update(_REG_ADC_RAMP, 0xF0, (max(0, min(15, rate))) << 4)

    def configure_alc(self, enabled, max_level_db=-6.0, min_level_db=-30.1, window=0,
                      automute=False, automute_window=0, noise_gate_db=-96):
        """Configure the automatic level control, noise gate and automute (0x18–0x1A).

        Args:
            enabled: Enable the ALC.
            max_level_db: Upper target level (rounded to the nearest of 16 levels).
            min_level_db: Lower target level.
            window: ALC window size 0–15.
            automute: Enable automute.
            automute_window: Automute detection window 0–15 (2048 × (n+1) samples).
            noise_gate_db: Noise gate threshold (rounded to the nearest of 16).
        """
        self._conn.write_reg(_REG_ADC_ALC1, (0x80 if enabled else 0) | (0x40 if automute else 0)
                             | (window & 0x0F))
        self._conn.write_reg(_REG_ADC_ALC2, (_nearest(ALC_LEVELS_DB, max_level_db) << 4)
                             | _nearest(ALC_LEVELS_DB, min_level_db))
        self._conn.write_reg(_REG_ADC_AUTOMUTE, ((automute_window & 0x0F) << 4)
                             | _nearest(NOISE_GATE_DB, noise_gate_db))

    def _write_eq(self, base, coefficients, count):
        if len(coefficients) != count:
            raise ValueError('expected %d coefficients' % count)
        data = bytearray()
        for c in coefficients:
            if not 0 <= c < (1 << 30):
                raise ValueError('coefficient must be a 30-bit value')
            data += bytes([(c >> 24) & 0x3F, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF])
        self._conn.write_reg(base, bytes(data))

    def set_adc_eq(self, coefficients=None):
        """Bypass the ADC equalizer or load its coefficients.

        Args:
            coefficients: None to bypass, else (b0, a1, a2, b1, b2) as 30-bit values.

        Raises:
            ValueError: If the coefficient count or range is wrong.
        """
        if coefficients is None:
            self._update(_REG_ADC_HPF2_EQ, 0x40, 0x40)
            return
        self._write_eq(_REG_ADCEQ, coefficients, 5)
        self._update(_REG_ADC_HPF2_EQ, 0x40, 0x00)

    def set_dac_ramp(self, rate):
        """Set the DAC volume / DRC soft-ramp rate (0x37 bits 7:4).

        Args:
            rate: 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
        """
        self._update(_REG_DAC_RAMP_EQ, 0xF0, (max(0, min(15, rate))) << 4)

    def configure_drc(self, enabled, max_level_db=-30.1, min_level_db=-30.1, window=0):
        """Configure the DAC dynamic range control (0x34, 0x35).

        Args:
            enabled: Enable the DRC (the DAC volume becomes its maximum gain).
            max_level_db: Upper target level.
            min_level_db: Lower target level.
            window: Window size 0–15.
        """
        self._conn.write_reg(_REG_DAC_DRC1, (0x80 if enabled else 0) | (window & 0x0F))
        self._conn.write_reg(_REG_DAC_DRC2, (_nearest(ALC_LEVELS_DB, max_level_db) << 4)
                             | _nearest(ALC_LEVELS_DB, min_level_db))

    def set_dac_eq(self, coefficients=None):
        """Bypass the DAC equalizer or load its coefficients.

        Args:
            coefficients: None to bypass, else (b0, b1, a1) as 30-bit values.

        Raises:
            ValueError: If the coefficient count or range is wrong.
        """
        if coefficients is None:
            self._update(_REG_DAC_RAMP_EQ, 0x08, 0x08)
            return
        self._write_eq(_REG_DACEQ, coefficients, 3)
        self._update(_REG_DAC_RAMP_EQ, 0x08, 0x00)

    def set_dac_mute_options(self, dsm=True, dem=True, dsm_target=False):
        """Set the three DAC mute bits individually (0x31 bits 7:5).

        Args:
            dsm: DSM mute (bit 6).
            dem: DEM mute (bit 5).
            dsm_target: DSM mute target (bit 7).
        """
        self._update(_REG_DAC_MUTE, 0xE0, (0x80 if dsm_target else 0) | (0x40 if dsm else 0)
                     | (0x20 if dem else 0))

    def set_loopback(self, enabled):
        """Feed the ADC output to the DAC (0x44 bit 7).

        Args:
            enabled: True to enable the loopback.
        """
        self._update(_REG_GPIO, 0x80, 0x80 if enabled else 0x00)

    def set_adcdat_mode(self, mode):
        """Select the ADCDAT content (0x44 bits 6:4).

        Args:
            mode: "adc_adc", "adc_zero", "zero_adc", "dac_adc", "adc_dac" or "dac_dac".

        Raises:
            ValueError: For an unknown mode.
        """
        if mode not in _ADCDAT_MODES:
            raise ValueError('unknown ADCDAT mode')
        self._update(_REG_GPIO, 0x70, _ADCDAT_MODES[mode] << 4)

    def set_sdp_mute(self, input=False, output=False):
        """Mute the digital audio input and / or output (0x09 / 0x0A bit 6).

        Args:
            input: Mute the serial input (DAC data).
            output: Mute the serial output (ADC data).
        """
        self._update(_REG_SDP_IN, 0x40, 0x40 if input else 0x00)
        self._update(_REG_SDP_OUT, 0x40, 0x40 if output else 0x00)

    def read_register(self, register):
        """Raw register read.

        Args:
            register: Register address 0x00–0xFF.

        Returns:
            int: Register value.
        """
        return self._read(register)

    def write_register(self, register, value):
        """Raw register write.

        Args:
            register: Register address 0x00–0xFF.
            value: Byte value.
        """
        self._conn.write_reg(register, value & 0xFF)
