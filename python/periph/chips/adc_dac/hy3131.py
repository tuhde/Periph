"""HYCON HY3131 50,000-count DMM analog front end (SPI).

Minimal reads AD1 as a differential voltage; Full exposes AD1/AD2/AD3 configuration,
low-pass filter, RMS, peak hold, frequency counter, interrupt flags and raw register
access. See specs/adc_dac/hy3131.md.

The chip's SPI framing is non-standard (7-bit address + R/W bit, one blank clock on
reads, auto-increment), so the driver uses the bare connection's ``write`` and
``write_read`` rather than RegisterConnection.
"""
import time


def _sleep_ms(ms):
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000)


def _ticks_ms():
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms()
    return int(time.monotonic() * 1000)


def _elapsed_ms(start):
    if hasattr(time, 'ticks_diff'):
        return time.ticks_diff(time.ticks_ms(), start)
    return _ticks_ms() - start


def _sext(value, bits):
    if value & (1 << (bits - 1)):
        value -= 1 << bits
    return value


class HY3131Minimal:
    """HY3131 — minimal interface: AD1 differential voltage.

    Args:
        connection: Configured SPI connection (mode 0, MSB first, CS active low).
        vref: Voltage on REFO in volts (1.2 V from the internal bandgap).
    """

    REG_AD1 = 0x00
    REG_AD2 = 0x03
    REG_LPF = 0x06
    REG_RMS = 0x09
    REG_PKHMIN = 0x0E
    REG_PKHMAX = 0x11
    REG_CTSTA = 0x14
    REG_CTC = 0x15
    REG_CTB = 0x18
    REG_CTA = 0x1B
    REG_INTF = 0x1E
    REG_INTE = 0x1F
    REG_R20 = 0x20
    REG_R22 = 0x22
    REG_R23 = 0x23
    REG_R24 = 0x24
    REG_R25 = 0x25
    REG_R26 = 0x26
    REG_R27 = 0x27
    REG_R28 = 0x28
    REG_R29 = 0x29
    REG_R2A = 0x2A
    REG_R2F = 0x2F
    REG_R30 = 0x30
    REG_R31 = 0x31
    REG_R32 = 0x32
    REG_R33 = 0x33
    REG_R34 = 0x34
    REG_R35 = 0x35
    REG_R36 = 0x36
    REG_R37 = 0x37

    FLAG_CTF = 0x01
    FLAG_AD2F = 0x02
    FLAG_AD1F = 0x04
    FLAG_LPFF = 0x08
    FLAG_RMSF = 0x10
    FLAG_BORF = 0x80

    _AD1_GAINS = (0.9, 1.8, 2.7, 3.6)
    _TIMEOUT_MS = 2000

    def __init__(self, connection, vref=1.2):
        self._conn = connection
        self._vref = vref
        self._flags = 0
        self._ig = 0
        self._rg = 0
        self._dcos = 0.0
        self._init_chip()

    # -- raw SPI framing ---------------------------------------------------

    def read_register(self, reg, n=1):
        """Read ``n`` consecutive registers starting at ``reg`` (auto-increment).

        Args:
            reg: Start address 0x00–0x37.
            n: Number of bytes.

        Returns:
            bytes: Register contents.
        """
        rx = self._conn.write_read(bytes([((reg & 0x7F) << 1) | 1]), n + 1)
        return bytes(((rx[i] << 1) | (rx[i + 1] >> 7)) & 0xFF for i in range(n))

    def write_register(self, reg, data):
        """Write one byte or a bytes-like to ``reg`` (auto-increment).

        Args:
            reg: Start address.
            data: int or bytes.
        """
        payload = bytes([data]) if isinstance(data, int) else bytes(data)
        self._conn.write(bytes([(reg & 0x7F) << 1]) + payload)

    # -- init --------------------------------------------------------------

    def _init_chip(self):
        self.write_register(self.REG_R37, 0x60)
        _sleep_ms(5)
        self.write_register(self.REG_R33, 0x28)
        _sleep_ms(10)
        self.write_register(self.REG_R31, 0xC0)
        self.write_register(self.REG_R30, 0x00)
        _sleep_ms(5)
        self.write_register(self.REG_R2F, 0x80)
        self.write_register(self.REG_R2A, bytes(5))
        for reg in (self.REG_R32, self.REG_R20, 0x21, self.REG_R34, self.REG_R35, self.REG_R36,
                    self.REG_R29, self.REG_R26, self.REG_R27, self.REG_INTE):
            self.write_register(reg, 0x00)
        self.write_register(self.REG_R24, 0xA3)   # SAD1FP=PB2 (1010), SDIO=0, SAD1FN=AGND (011)
        self.write_register(self.REG_R28, 0x11)   # SAD1RH=REFO (001), SAD1RL=AGND (001)
        self._ig = 0
        self._rg = 0
        self._dcos = 0.0
        self.write_register(self.REG_R25, 0x00)   # AD1IG=0.9, SACM=0
        self.write_register(self.REG_R22, 0x07)   # DCOS=0, no chop, OSR1=32768
        self.write_register(self.REG_R23, 0x80)   # ENAD1, RG=1.0, buffers off
        self.read_register(self.REG_INTF)
        self._flags = 0

    # -- helpers -----------------------------------------------------------

    def _wait_flag(self, mask):
        """Wait until INTF reports ``mask``; latch every flag seen. Returns False on timeout."""
        start = _ticks_ms()
        while True:
            self._flags |= self.read_register(self.REG_INTF)[0]
            if self._flags & mask:
                self._flags &= ~mask
                return True
            if _elapsed_ms(start) > self._TIMEOUT_MS:
                return False
            _sleep_ms(1)

    def _read24(self, reg):
        b = self.read_register(reg, 3)
        return b[0] | (b[1] << 8) | (b[2] << 16)

    # -- public API --------------------------------------------------------

    def data_ready(self):
        """Return True if a new AD1 conversion is pending (latches INTF).

        Returns:
            bool: True when AD1F is set.
        """
        self._flags |= self.read_register(self.REG_INTF)[0]
        return bool(self._flags & self.FLAG_AD1F)

    def read_raw(self):
        """Wait for a conversion and return the signed 24-bit AD1 code.

        Returns:
            int: -8388608 … 8388607.

        Raises:
            OSError: If no conversion completes within 2 s.
        """
        if not self._wait_flag(self.FLAG_AD1F):
            raise OSError('HY3131 AD1 timeout')
        return _sext(self._read24(self.REG_AD1), 24)

    def read_voltage(self):
        """Read AD1 as a differential input voltage.

        Returns:
            float: Volts.
        """
        code = self.read_raw()
        return (code / 8388608.0 * (1.0 if self._rg == 0 else 0.3333333333) - self._dcos) \
            * self._vref / self._AD1_GAINS[self._ig]


class HY3131Full(HY3131Minimal):
    """HY3131 — full interface: AD1/AD2/AD3, LPF, RMS, peak hold, counter, interrupts."""

    def reset(self):
        """Software-reset the chip and re-apply the Minimal defaults."""
        self._init_chip()

    def configure_ad1(self, osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0):
        """Configure AD1.

        Args:
            osr: 0–7 → OSR1 256 … 32768.
            chop: 0–3 (1x enables the chopper and halves the rate).
            input_gain: 0–3 → 0.9, 1.8, 2.7, 3.6.
            ref_gain: 0 → 1.0, 1 → 0.333.
            offset: 0–7 (DCOS code, see spec).
            buffers: 4-bit mask — bit3 RH, bit2 RL, bit1 IP, bit0 IN.
        """
        r25 = self.read_register(self.REG_R25)[0]
        self.write_register(self.REG_R25, (r25 & 0xCF) | ((input_gain & 3) << 4))
        self.write_register(self.REG_R22, ((offset & 7) << 5) | ((chop & 3) << 3) | (osr & 7))
        self.write_register(self.REG_R23, 0x80 | ((ref_gain & 1) << 4) | (buffers & 0x0F))
        self._ig = input_gain & 3
        self._rg = ref_gain & 1
        self._dcos = (0.0, 0.25, 0.5, 0.75, 0.0, -0.25, -0.5, -0.75)[offset & 7]
        self.read_register(self.REG_INTF)
        self._flags = 0

    def set_ad1_input(self, pos, neg, filter_r=2):
        """Select AD1 inputs: ``pos`` 0–15 (SAD1FP), ``neg`` 0–7 (SAD1FN), ``filter_r`` 0–3 (SFT1)."""
        self.write_register(self.REG_R24, ((pos & 15) << 4) | (neg & 7))
        r33 = self.read_register(self.REG_R33)[0]
        self.write_register(self.REG_R33, (r33 & 0xF3) | ((filter_r & 3) << 2))

    def set_ad1_reference(self, high, low):
        """Select AD1 reference: ``high`` 0–6 (SAD1RH), ``low`` 0–6 (SAD1RL)."""
        self.write_register(self.REG_R28, ((high & 7) << 4) | (low & 7))

    def read_temperature_code(self):
        """Return TCode = (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2 from the on-chip sensor.

        Returns:
            int: Temperature code (store the value at 25 °C as ``tcode_25``).
        """
        r33 = self.read_register(self.REG_R33)[0]
        codes = []
        for sel in (2, 3):
            self.write_register(self.REG_R33, (r33 & 0xFC) | sel)
            self.read_register(self.REG_INTF)
            self._flags = 0
            self._wait_flag(self.FLAG_AD1F)   # discard settling sample
            codes.append(self.read_raw())
        self.write_register(self.REG_R33, r33)
        self._flags = 0
        return (codes[1] - codes[0]) // 2

    def read_temperature(self, tcode_25):
        """Return the die temperature using a one-point calibration.

        Args:
            tcode_25: Value of ``read_temperature_code()`` measured at 25 °C.

        Returns:
            float: °C.
        """
        t_os = -3.15
        g = tcode_25 / (25 + 273.15 + t_os)
        return self.read_temperature_code() / g - 273.15 - t_os

    def enable_ad2(self, osr=5, input_gain=1, ref_gain=0, chop=False, clk_div4=False,
                   source=0, use_ad3=False):
        """Enable AD2 (and AD3 if ``use_ad3``).

        Args:
            osr: 0–7 (OSR 32 … 1024).
            input_gain: 0–3 → 0.5, 1.0, 1.5, 2.0.
            ref_gain: 0 → 1.0, 1 → 0.333.
            chop: Enable the input chopper.
            clk_div4: Modulator clock SYSCLK/4 instead of /2.
            source: SAD2IP 0–3 (OP1O, OP2O, PB4, PB7).
            use_ad3: Show AD3 instead of AD2 in the data registers (SDO23=1).
        """
        r25 = self.read_register(self.REG_R25)[0]
        self.write_register(self.REG_R25, (r25 & 0x3F) | ((input_gain & 3) << 6))
        self.write_register(self.REG_R27, ((source & 3) << 6) | 0x15)  # SAD2IN=AGND, SAD2RH=REFO, SAD2RL=AGND
        r26 = 0x80 | (0x20 if chop else 0) | ((ref_gain & 1) << 4) | (0x08 if clk_div4 else 0) | (osr & 7)
        self.write_register(self.REG_R26, r26)
        if use_ad3:
            self.write_register(self.REG_R35, ((source & 3) << 6) | (1 << 4) | ((input_gain & 3) << 2))
            self.write_register(self.REG_R34, 0x80 | (0x20 if chop else 0) | ((ref_gain & 1) << 4) | 0x04)
        else:
            self.write_register(self.REG_R34, 0x00)
        self.read_register(self.REG_INTF)
        self._flags = 0

    def read_ad2_raw(self):
        """Wait for AD2 and return the signed 19-bit code.

        Returns:
            int: -262144 … 262143.
        """
        if not self._wait_flag(self.FLAG_AD2F):
            raise OSError('HY3131 AD2 timeout')
        return _sext(self._read24(self.REG_AD2), 24)

    def enable_lpf(self, bw=0):
        """Enable the low-pass filter with OSR4 code ``bw`` (0–7 → 128 … 16384)."""
        r29 = self.read_register(self.REG_R29)[0]
        self.write_register(self.REG_R29, (r29 & 0x87) | 0x40 | ((bw & 7) << 3))

    def read_lpf_raw(self):
        """Wait for LPF and return the signed 19-bit code."""
        if not self._wait_flag(self.FLAG_LPFF):
            raise OSError('HY3131 LPF timeout')
        return _sext(self._read24(self.REG_LPF), 24)

    def enable_rms(self):
        """Enable the RMS converter (requires AD2 and the LPF)."""
        r29 = self.read_register(self.REG_R29)[0]
        self.write_register(self.REG_R29, r29 | 0x80)

    def read_rms(self):
        """Wait for RMS and return ``sqrt(RMS_reg)`` in AD2 LSB units.

        Returns:
            float: RMS in AD2 LSBs.
        """
        if not self._wait_flag(self.FLAG_RMSF):
            raise OSError('HY3131 RMS timeout')
        b = self.read_register(self.REG_RMS, 5)
        value = _sext(b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24) | (b[4] << 32), 40)
        return max(value, 0) ** 0.5

    def enable_peak_hold(self, source=0):
        """Enable peak hold; ``source`` 0–3 → AD2, AD1<23:5>, LPF, LPF. Re-enabling resets min/max."""
        r29 = self.read_register(self.REG_R29)[0]
        self.write_register(self.REG_R29, r29 & ~0x07)
        self.write_register(self.REG_R29, (r29 & ~0x07) | 0x04 | (source & 3))

    def read_peak(self):
        """Return ``(min, max)`` signed 19-bit peak-hold codes."""
        lo = _sext(self._read24(self.REG_PKHMIN), 24)
        hi = _sext(self._read24(self.REG_PKHMAX), 24)
        return lo, hi

    def start_counter(self, gate_preload=0):
        """Start the frequency counter; ``gate_preload`` 0–0xFFFF is written to CTA<23:8>."""
        r20 = self.read_register(self.REG_R20)[0]
        self.write_register(self.REG_R20, r20 & ~0x02)
        self.write_register(self.REG_CTA + 1, bytes([gate_preload & 0xFF, (gate_preload >> 8) & 0xFF]))
        self.read_register(self.REG_INTF)
        self._flags = 0
        self.write_register(self.REG_R20, r20 | 0x0A)

    def read_counter(self):
        """Wait for the counter and return ``(cta, ctb, ctc, overflow)``."""
        if not self._wait_flag(self.FLAG_CTF):
            raise OSError('HY3131 counter timeout')
        overflow = bool(self.read_register(self.REG_CTSTA)[0] & 0x01)
        return (self._read24(self.REG_CTA), self._read24(self.REG_CTB),
                self._read24(self.REG_CTC), overflow)

    def read_flags(self):
        """Return INTF (clears it on the chip); latched flags are included and cleared.

        Returns:
            int: Flag bits (see FLAG_*).
        """
        flags = self._flags | self.read_register(self.REG_INTF)[0]
        self._flags = 0
        return flags

    def enable_interrupts(self, mask):
        """Write INTE (bits: RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0)."""
        self.write_register(self.REG_INTE, mask & 0x1F)
