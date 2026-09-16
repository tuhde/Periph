"""ADE7953 single-phase multifunction metering IC.

The ADE7953 is a high-accuracy single-phase electrical energy metering
IC with one voltage channel and two current channels (phase A and neutral
B), producing RMS voltage/current, instantaneous and accumulated active,
reactive and apparent power/energy, power factor, phase angle, line period
and frequency. It supports three host transports — SPI, I²C and UART —
all of which address the same on-chip register bank; only the framing
differs (see Register Access). The active transport is selected by the
hardware wiring of the bus pins (CS, SCLK, MOSI/SCL/Rx, MISO/SDA/Tx) at
power-up — the driver takes a ``bus_type`` string at construction and
chooses the matching register-access framing.

Default configuration (baked in at construction):
    - PGA gains all 1 (unity)
    - HPF on, digital integrators off (shunt/CT mode)
    - ``RSTREAD`` = 1 (energy registers reset on read)
    - Calibration/offset registers at power-on defaults
    - ``DISNOLOAD`` all 0 (no-load detection enabled)

Args:
    connection: Configured I²C, SPI or UART connection bound to the
        device (address / CS / port set by the caller).
    voltage_gain: Real volts at the mains per volt present at ``VP``–``VN``
        (i.e. inverse of the external voltage-divider ratio). Must be
        supplied — depends on the external divider network, which varies
        per PCB design.
    current_gain: Real amperes per volt present at ``IAP``–``IAN`` for
        Current Channel A (i.e. transconductance of the external CT+burden,
        shunt or Rogowski front end). Must be supplied — same reason.
    bus_type: ``'i2c'`` (default), ``'spi'`` or ``'uart'``. The active
        transport must match how the chip's four bus pins are wired.
"""

import struct
import time


_BUS_I2C  = 'i2c'
_BUS_SPI  = 'spi'
_BUS_UART = 'uart'

# --- 8-bit registers ---
_REG_SAGCYC          = 0x000
_REG_DISNOLOAD       = 0x001
_REG_LCYCMODE        = 0x004
_REG_PGA_V           = 0x007
_REG_PGA_IA          = 0x008
_REG_PGA_IB          = 0x009
_REG_WRITE_PROTECT   = 0x040
_REG_LAST_OP         = 0x0FD
_REG_LAST_RWDATA_8   = 0x0FF
_REG_VERSION         = 0x702
_REG_EX_REF          = 0x800

# --- 16-bit registers ---
_REG_ZXTOUT          = 0x100
_REG_LINECYC         = 0x101
_REG_CONFIG          = 0x102
_REG_CF1DEN          = 0x103
_REG_CF2DEN          = 0x104
_REG_CFMODE          = 0x107
_REG_PHCALA          = 0x108
_REG_PHCALB          = 0x109
_REG_PFA             = 0x10A
_REG_PFB             = 0x10B
_REG_ANGLE_A         = 0x10C
_REG_ANGLE_B         = 0x10D
_REG_PERIOD          = 0x10E
_REG_ALT_OUTPUT      = 0x110
_REG_INTERNAL_RES    = 0x120
_REG_LAST_ADD        = 0x1FE
_REG_LAST_RWDATA_16  = 0x1FF

# --- 24-bit / 32-bit registers (24-bit addresses listed, 32-bit aliases +0x100) ---
_REG_SAGLVL          = 0x200
_REG_ACCMODE         = 0x201
_REG_AP_NOLOAD       = 0x203
_REG_VAR_NOLOAD      = 0x204
_REG_VA_NOLOAD       = 0x205
_REG_AVA             = 0x210
_REG_BVA             = 0x211
_REG_AWATT           = 0x212
_REG_BWATT           = 0x213
_REG_AVAR            = 0x214
_REG_BVAR            = 0x215
_REG_IA              = 0x216
_REG_IB              = 0x217
_REG_V               = 0x218
_REG_IRMSA           = 0x21A
_REG_IRMSB           = 0x21B
_REG_VRMS            = 0x21C
_REG_AENERGYA        = 0x21E
_REG_AENERGYB        = 0x21F
_REG_RENERGYA        = 0x220
_REG_RENERGYB        = 0x221
_REG_APENERGYA       = 0x222
_REG_APENERGYB       = 0x223
_REG_OVLVL           = 0x224
_REG_OILVL           = 0x225
_REG_VPEAK           = 0x226
_REG_RSTVPEAK        = 0x227
_REG_IAPEAK          = 0x228
_REG_RSTIAPEAK       = 0x229
_REG_IBPEAK          = 0x22A
_REG_RSTIBPEAK       = 0x22B
_REG_IRQENA          = 0x22C
_REG_IRQSTATA        = 0x22D
_REG_RSTIRQSTATA     = 0x22E
_REG_IRQENB          = 0x22F
_REG_IRQSTATB        = 0x230
_REG_RSTIRQSTATB     = 0x231
_REG_CRC             = 0x37F
_REG_AIGAIN          = 0x280
_REG_AVGAIN          = 0x281
_REG_AWGAIN          = 0x282
_REG_AVARGAIN        = 0x283
_REG_AVAGAIN         = 0x284
_REG_AIRMSOS         = 0x286
_REG_VRMSOS          = 0x288
_REG_AWATTOS         = 0x289
_REG_AVAROS          = 0x28A
_REG_AVAOS           = 0x28B
_REG_BIGAIN          = 0x28C
_REG_BWGAIN          = 0x28E
_REG_BVARGAIN        = 0x28F
_REG_BVAGAIN         = 0x290
_REG_BIRMSOS         = 0x292
_REG_BWATTOS         = 0x295
_REG_BVAROS          = 0x296
_REG_BVAOS           = 0x297
_REG_LAST_RWDATA_32  = 0x3FF
_REG_120_UNLOCK_ADDR = 0x0FE

# Required Power-Up Register Setting
_REG_120_UNLOCK      = 0xAD
_REG_120_VALUE       = 0x30

# IRQ source bit positions (channel A group)
_AEHFA      = 1 << 0
_VAREHFA    = 1 << 1
_VAEHFA     = 1 << 2
_AEOFA      = 1 << 3
_VAREOFA    = 1 << 4
_VAEOFA     = 1 << 5
_AP_NOLOADA = 1 << 6
_VAR_NOLOADA= 1 << 7
_VA_NOLOADA = 1 << 8
_APSIGN_A   = 1 << 9
_VARSIGN_A  = 1 << 10
_ZXTO_IA    = 1 << 11
_ZXIA       = 1 << 12
_OIA        = 1 << 13
_ZXTO       = 1 << 14
_ZXV        = 1 << 15
_OV         = 1 << 16
_WSMP       = 1 << 17
_CYCEND     = 1 << 18
_SAG        = 1 << 19
_RESET      = 1 << 20
_CRC        = 1 << 21

# ADE7953 ADC scaling constants (PGA gain = 1).
_ADC_FS_VOLTS = 0.5 / 1.4142135623730951    # ≈ 0.353553 V rms
_ADC_FS_CODE  = 9032007
_POWER_FS_CODE = 4862401
_T_SAMPLE = 1.0 / 206900.0                  # ≈ 4.83e-6 s

_PF_LSB = 1.0 / 32768.0
_ANGLE_LSB = 1.0 / 223750.0                 # 1 LSB = 4.47 µs
_PHASE_LSB = 1.0 / 895000.0                 # 1 LSB = 1.117 µs


def _twos(value, width):
    mask = (1 << width) - 1
    sign = 1 << (width - 1)
    value &= mask
    if value & sign:
        value -= mask + 1
    return value


def _addr_bytes(addr):
    return bytes(((addr >> 8) & 0xFF, addr & 0xFF))


def _u16_msb_first(data, offset=0):
    return (data[offset] << 8) | data[offset + 1]


def _s16_msb_first(data, offset=0):
    return _twos(_u16_msb_first(data, offset), 16)


def _u24_msb_first(data, offset=0):
    return (data[offset] << 16) | (data[offset + 1] << 8) | data[offset + 2]


def _s24_msb_first(data, offset=0):
    return _twos(_u24_msb_first(data, offset), 24)


def _u32_msb_first(data, offset=0):
    return (data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) | data[offset + 3]


def _s32_msb_first(data, offset=0):
    return _twos(_u32_msb_first(data, offset), 32)


def _u32_lsb_first(data, offset=0):
    return (data[offset + 3] << 24) | (data[offset + 2] << 16) | (data[offset + 1] << 8) | data[offset]


def _s32_lsb_first(data, offset=0):
    return _twos(_u32_lsb_first(data, offset), 32)


def _delay_ms(ms):
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class ADE7953Minimal:
    """ADE7953 single-phase metering IC — minimal interface.

    Reads voltage, current and active power/energy for Current Channel A
    (the phase input). No register configuration beyond the mandatory
    power-up sequence and the caller's sensor scaling is performed.

    Args:
        connection: Configured I²C, SPI or UART connection bound to the
            device.
        voltage_gain: Real volts at the mains per volt at ``VP``–``VN``.
        current_gain: Real amperes per volt at ``IAP``–``IAN`` (Channel A).
        bus_type: ``'i2c'`` (default), ``'spi'`` or ``'uart'``. Must
            match how the chip's bus pins are wired (autodetected by the
            chip at power-up).
    """

    def __init__(self, connection, voltage_gain, current_gain, bus_type='i2c'):
        if bus_type not in (_BUS_I2C, _BUS_SPI, _BUS_UART):
            raise ValueError("bus_type must be 'i2c', 'spi' or 'uart'")
        self._connection = connection
        self._bus_type = bus_type
        self._voltage_gain = float(voltage_gain)
        self._current_gain_a = float(current_gain)
        self._current_gain_b = float(current_gain)
        self._pga_a = 1
        self._pga_b = 1
        self._pga_v = 1
        self._init_chip()

    def _init_chip(self):
        # Wait for the 100 ms power-up hold-off, then issue the mandatory
        # power-up register setting.
        _delay_ms(110)
        self._write_u8(_REG_120_UNLOCK_ADDR, _REG_120_UNLOCK)
        self._write_u16(_REG_INTERNAL_RES, _REG_120_VALUE)

    def _addr_for_read(self, addr, n):
        if self._bus_type == _BUS_SPI:
            # SPI: leading byte = (addr_msb & 0x7F) | 0x80 (READ); discard 2
            # bytes back; keep n bytes.
            return bytes(((addr >> 8) & 0x7F)) + bytes(((addr & 0xFF) | 0x80,)) + b'\x00\x00'
        if self._bus_type == _BUS_UART:
            return bytes((0x35, (addr >> 8) & 0xFF, addr & 0xFF))
        return _addr_bytes(addr)

    def _addr_for_write(self, addr, payload):
        if self._bus_type == _BUS_UART:
            return bytes((0xCA, (addr >> 8) & 0xFF, addr & 0xFF)) + bytes(reversed(payload))
        return _addr_bytes(addr) + payload

    def _read(self, addr, n):
        if self._bus_type == _BUS_SPI:
            tx = self._addr_for_read(addr, n)
            raw = self._connection.write_read(tx, n + 2)
            return raw[2:]
        if self._bus_type == _BUS_UART:
            # ≥ 0.1 ms gap between header and data read
            self._connection.write(self._addr_for_read(addr, n))
            _delay_ms(1)
            raw = self._connection.read(n)
            return bytes(reversed(raw))
        raw = self._connection.write_read(self._addr_for_read(addr, n), n)
        return raw

    def _write_payload(self, addr, payload):
        self._connection.write(self._addr_for_write(addr, payload))

    def _write_u8(self, reg, value):
        self._write_payload(reg, bytes((value & 0xFF,)))

    def _write_u16(self, reg, value):
        self._write_payload(reg, bytes((value >> 8, value & 0xFF)))

    def _write_u24(self, reg, value):
        self._write_payload(reg, bytes(((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)))

    def _write_u32(self, reg, value):
        self._write_payload(reg, bytes(((value >> 24) & 0xFF, (value >> 16) & 0xFF,
                                         (value >> 8) & 0xFF, value & 0xFF)))

    def _read_u8(self, reg):
        return self._read(reg, 1)[0]

    def _read_u16(self, reg):
        raw = self._read(reg, 2)
        return (raw[0] << 8) | raw[1]

    def _read_s16(self, reg):
        raw = self._read(reg, 2)
        v = (raw[0] << 8) | raw[1]
        if v & 0x8000:
            v -= 0x10000
        return v

    def _read_u24(self, reg):
        raw = self._read(reg, 3)
        return (raw[0] << 16) | (raw[1] << 8) | raw[2]

    def _read_s24(self, reg):
        raw = self._read(reg, 3)
        v = (raw[0] << 16) | (raw[1] << 8) | raw[2]
        if v & 0x800000:
            v -= 0x1000000
        return v

    def _read_u32(self, reg):
        raw = self._read(reg, 4)
        return (raw[0] << 24) | (raw[1] << 16) | (raw[2] << 8) | raw[3]

    def _read_s32(self, reg):
        raw = self._read(reg, 4)
        v = (raw[0] << 24) | (raw[1] << 16) | (raw[2] << 8) | raw[3]
        if v & 0x80000000:
            v -= 0x100000000
        return v

    def _voltage_scale(self):
        return (_ADC_FS_VOLTS * self._voltage_gain) / (_ADC_FS_CODE * self._pga_v)

    def _current_scale(self, gain):
        return (_ADC_FS_VOLTS * gain) / (_ADC_FS_CODE * 1)

    def _power_scale(self, gain):
        return ((_ADC_FS_VOLTS * _ADC_FS_VOLTS) * self._voltage_gain * gain) / _POWER_FS_CODE

    def _energy_scale(self, gain):
        return ((_ADC_FS_VOLTS * _ADC_FS_VOLTS) * self._voltage_gain * gain * _T_SAMPLE) / 3600.0

    def voltage(self):
        """Read the RMS voltage on the voltage channel.

        Returns:
            float: Voltage in volts.
        """
        raw = self._read_u24(_REG_VRMS)
        return raw * self._voltage_scale()

    def current(self):
        """Read the RMS current on Current Channel A.

        Returns:
            float: Current in amperes.
        """
        raw = self._read_u24(_REG_IRMSA)
        return raw * self._current_scale(self._current_gain_a)

    def active_power(self):
        """Read instantaneous active power on Current Channel A.

        Returns:
            float: Active power in watts. Negative values indicate power
                flowing back to the source (the ``APSIGN_A`` bit in
                ``ACCMODE``).
        """
        raw = self._read_s24(_REG_AWATT)
        return raw * self._power_scale(self._current_gain_a)

    def active_energy(self):
        """Read the active-energy accumulator for Current Channel A.

        Reads ``AENERGYA``, which by default (``RSTREAD`` = 1) resets to
        zero after the read; therefore this returns the energy accumulated
        *since the previous call*.

        Returns:
            float: Active energy in watt-hours.
        """
        raw = self._read_s24(_REG_AENERGYA)
        return raw * self._energy_scale(self._current_gain_a)


class ADE7953Source:
    """ADE7953 interrupt source bit values (matched to the IRQENA/IRQENB layout)."""

    AEHFA       = _AEHFA
    VAREHFA     = _VAREHFA
    VAEHFA      = _VAEHFA
    AEOFA       = _AEOFA
    VAREOFA     = _VAREOFA
    VAEOFA      = _VAEOFA
    AP_NOLOADA  = _AP_NOLOADA
    VAR_NOLOADA = _VAR_NOLOADA
    VA_NOLOADA  = _VA_NOLOADA
    APSIGN_A    = _APSIGN_A
    VARSIGN_A   = _VARSIGN_A
    ZXTO_IA     = _ZXTO_IA
    ZXIA        = _ZXIA
    OIA         = _OIA
    ZXTO        = _ZXTO
    ZXV         = _ZXV
    OV          = _OV
    WSMP        = _WSMP
    CYCEND      = _CYCEND
    SAG         = _SAG
    CRC_CHANGED = _CRC

    AEHFB       = _AEHFA
    VAREHFB     = _VAREHFA
    VAEHFB      = _VAEHFA
    AEOFB       = _AEOFA
    VAREOFB     = _VAREOFA
    VAEOFB      = _VAEOFA
    AP_NOLOADB  = _AP_NOLOADA
    VAR_NOLOADB = _VAR_NOLOADA
    VA_NOLOADB  = _VA_NOLOADA
    APSIGN_B    = _APSIGN_A
    VARSIGN_B   = _VARSIGN_A
    ZXTO_IB     = _ZXTO_IA
    ZXIB        = _ZXIA
    OIB         = _OIA


class ADE7953AltOutput:
    """ADE7953 alternate-output pin function codes (same encoding for ZX/ZXI/REVP)."""

    DEFAULT                = 0x0
    SAG                    = 0x1
    AP_NOLOAD_A            = 0x5
    AP_NOLOAD_B            = 0x6
    VAR_NOLOAD_A           = 0x7
    VAR_NOLOAD_B           = 0x8
    WSMP_UNLATCHED         = 0x9
    IRQ                    = 0xA
    ZX_OR_ZXI              = 0xB
    REVP                   = 0xC


class ADE7953CFSource:
    """ADE7953 CF output source codes (CF1SEL/CF2SEL)."""

    ACTIVE_A    = 0x0
    REACTIVE_A  = 0x1
    APPARENT_A  = 0x2
    IRMS_A      = 0x3
    ACTIVE_B    = 0x4
    REACTIVE_B  = 0x5
    APPARENT_B  = 0x6
    IRMS_B      = 0x7
    IRMS_A_B    = 0x8
    ACTIVE_A_B  = 0x9


class ADE7953Full(ADE7953Minimal):
    """ADE7953 full interface — extends ADE7953Minimal with Channel B,
    reactive/apparent measurements, calibration, accumulation modes,
    power-quality features (no-load, sag, peak, overcurrent/overvoltage),
    zero-crossing, REVP, alternate outputs, CF pulses, interrupts,
    checksum, write protection, reset and last-operation diagnostics.

    Args:
        connection: Configured I²C, SPI or UART connection bound to the
            device.
        voltage_gain: Real volts at the mains per volt at ``VP``–``VN``.
        current_gain: Real amperes per volt at ``IAP``–``IAN`` (Channel A).
        bus_type: ``'i2c'`` (default), ``'spi'`` or ``'uart'``.
    """

    ACC_NORMAL          = 0x0
    ACC_POSITIVE_ONLY   = 0x1
    ACC_ABSOLUTE        = 0x2
    ACC_ANTITAMPER      = 0x1

    APPARENT_AMP_HOUR   = 0x1

    def __init__(self, connection, voltage_gain, current_gain, bus_type='i2c'):
        super().__init__(connection, voltage_gain, current_gain, bus_type)

    # --- Channel B and aggregate measurements -----------------------------

    def configure_channel_b(self, current_gain_b):
        """Override the calibration constant used by every Channel B
        current/power/energy method. Defaults to Channel A's current_gain
        if never called.
        """
        self._current_gain_b = float(current_gain_b)

    def current_b(self):
        """Read the RMS current on Current Channel B (neutral).

        Returns:
            float: Current in amperes.
        """
        raw = self._read_u24(_REG_IRMSB)
        return raw * self._current_scale(self._current_gain_b)

    def active_power_b(self):
        """Read instantaneous active power on Current Channel B.

        Returns:
            float: Active power in watts (signed).
        """
        raw = self._read_s24(_REG_BWATT)
        return raw * self._power_scale(self._current_gain_b)

    def active_energy_b(self):
        """Read the active-energy accumulator for Current Channel B.

        Returns:
            float: Active energy in watt-hours accumulated since the
                previous call.
        """
        raw = self._read_s24(_REG_AENERGYB)
        return raw * self._energy_scale(self._current_gain_b)

    def reactive_power(self):
        """Read instantaneous reactive power on Current Channel A.

        Returns:
            float: Reactive power in VAR (signed).
        """
        raw = self._read_s24(_REG_AVAR)
        return raw * self._power_scale(self._current_gain_a)

    def reactive_power_b(self):
        """Read instantaneous reactive power on Current Channel B."""
        raw = self._read_s24(_REG_BVAR)
        return raw * self._power_scale(self._current_gain_b)

    def reactive_energy(self):
        """Read reactive energy accumulator for Current Channel A."""
        raw = self._read_s24(_REG_RENERGYA)
        return raw * self._energy_scale(self._current_gain_a)

    def reactive_energy_b(self):
        """Read reactive energy accumulator for Current Channel B."""
        raw = self._read_s24(_REG_RENERGYB)
        return raw * self._energy_scale(self._current_gain_b)

    def apparent_power(self):
        """Read instantaneous apparent power on Current Channel A."""
        raw = self._read_s24(_REG_AVA)
        return raw * self._power_scale(self._current_gain_a)

    def apparent_power_b(self):
        """Read instantaneous apparent power on Current Channel B."""
        raw = self._read_s24(_REG_BVA)
        return raw * self._power_scale(self._current_gain_b)

    def apparent_energy(self):
        """Read apparent-energy accumulator for Current Channel A."""
        raw = self._read_s24(_REG_APENERGYA)
        return raw * self._energy_scale(self._current_gain_a)

    def apparent_energy_b(self):
        """Read apparent-energy accumulator for Current Channel B."""
        raw = self._read_s24(_REG_APENERGYB)
        return raw * self._energy_scale(self._current_gain_b)

    def waveform_sample(self):
        """One coherent 6.99 kHz snapshot of the instantaneous registers.

        Returns:
            dict: All eight instantaneous readings in engineering units.
        """
        v = self._read_s24(_REG_V) * self._voltage_scale()
        ia = self._read_s24(_REG_IA) * self._current_scale(self._current_gain_a)
        ib = self._read_s24(_REG_IB) * self._current_scale(self._current_gain_b)
        awatt = self._read_s24(_REG_AWATT) * self._power_scale(self._current_gain_a)
        bwatt = self._read_s24(_REG_BWATT) * self._power_scale(self._current_gain_b)
        avar = self._read_s24(_REG_AVAR) * self._power_scale(self._current_gain_a)
        bvar = self._read_s24(_REG_BVAR) * self._power_scale(self._current_gain_b)
        ava = self._read_s24(_REG_AVA) * self._power_scale(self._current_gain_a)
        bva = self._read_s24(_REG_BVA) * self._power_scale(self._current_gain_b)
        return {
            'voltage': v,
            'current_a': ia,
            'current_b': ib,
            'active_power_a': awatt,
            'active_power_b': bwatt,
            'reactive_power_a': avar,
            'reactive_power_b': bvar,
            'apparent_power_a': ava,
            'apparent_power_b': bva,
        }

    # --- Power factor, angle and line frequency ----------------------------

    def power_factor(self):
        """Read power factor for Current Channel A.

        Returns:
            float: Power factor, range −1.0 … +1.0.
        """
        raw = self._read_s16(_REG_PFA)
        return raw * _PF_LSB

    def power_factor_b(self):
        """Read power factor for Current Channel B."""
        raw = self._read_s16(_REG_PFB)
        return raw * _PF_LSB

    def phase_angle(self, line_frequency_hz=50.0):
        """Voltage-to-current phase angle on Current Channel A.

        Args:
            line_frequency_hz: Nominal mains frequency in Hz.

        Returns:
            float: Phase angle in degrees.
        """
        raw = self._read_s16(_REG_ANGLE_A)
        return raw * (360.0 * line_frequency_hz) * _ANGLE_LSB

    def phase_angle_b(self, line_frequency_hz=50.0):
        """Voltage-to-current phase angle on Current Channel B."""
        raw = self._read_s16(_REG_ANGLE_B)
        return raw * (360.0 * line_frequency_hz) * _ANGLE_LSB

    def line_period(self):
        """Read the line period.

        Returns:
            float: Line period in seconds.
        """
        raw = self._read_u16(_REG_PERIOD)
        return (raw + 1) * _ANGLE_LSB

    def line_frequency(self):
        """Read the line frequency.

        Returns:
            float: Line frequency in Hertz.
        """
        return 1.0 / self.line_period()

    # --- Calibration -------------------------------------------------------

    def set_pga(self, channel, gain):
        """Write PGA gain for a channel and update cached scale factors.

        Args:
            channel: ``'a'``, ``'b'`` or ``'v'``.
            gain: One of 1, 2, 4, 8, 16 (and 22 valid only for ``'a'``).
        """
        allowed = (1, 2, 4, 8, 16)
        if channel == 'a':
            allowed = allowed + (22,)
        if gain not in allowed:
            raise ValueError('invalid PGA gain')
        bits = {1: 0, 2: 1, 4: 2, 8: 3, 16: 4, 22: 5}[gain]
        if channel == 'a':
            self._write_u8(_REG_PGA_IA, bits)
            self._pga_a = gain
        elif channel == 'b':
            self._write_u8(_REG_PGA_IB, bits)
            self._pga_b = gain
        elif channel == 'v':
            self._write_u8(_REG_PGA_V, bits)
            self._pga_v = gain
        else:
            raise ValueError("channel must be 'a', 'b' or 'v'")

    def _phase_cal_raw(self, delay_s):
        # sign-magnitude: bit 9 = sign (1 = advance), [8:0] = magnitude
        mag = int(abs(delay_s) / _PHASE_LSB + 0.5)
        if mag > 0x1FF:
            raise ValueError('phase calibration magnitude out of range')
        if delay_s < 0:
            return mag & 0x1FF
        return (mag & 0x1FF) | 0x200

    def set_phase_calibration(self, channel, delay_s):
        """Write phase calibration register.

        Args:
            channel: ``'a'`` or ``'b'``.
            delay_s: Phase shift in seconds; negative = delay,
                positive = advance.
        """
        raw = self._phase_cal_raw(delay_s)
        if channel == 'a':
            self._write_u16(_REG_PHCALA, raw)
        elif channel == 'b':
            self._write_u16(_REG_PHCALB, raw)
        else:
            raise ValueError("channel must be 'a' or 'b'")

    def set_gain_calibration(self, register, value):
        """Write one of the gain-calibration registers.

        Args:
            register: One of ``AIGAIN``, ``AVGAIN``, ``AWGAIN``,
                ``AVARGAIN``, ``AVAGAIN``, ``BIGAIN``, ``BWGAIN``,
                ``BVARGAIN``, ``BVAGAIN``.
            value: Raw 24-bit value; valid range ``0x200000``–``0x600000``.
        """
        if value < 0 or value > 0xFFFFFF:
            raise ValueError('gain calibration value out of range')
        self._write_u24(register, value)

    def gain_calibration(self, register):
        """Read a gain-calibration register.

        Returns:
            int: Raw 24-bit register value.
        """
        return self._read_u24(register)

    def set_offset_calibration(self, register, value):
        """Write one of the offset-calibration registers.

        Args:
            register: One of ``AIRMSOS``, ``VRMSOS``, ``AWATTOS``,
                ``AVAROS``, ``AVAOS``, ``BIRMSOS``, ``BWATTOS``,
                ``BVAROS``, ``BVAOS``.
            value: Signed 24-bit value.
        """
        if value < -(1 << 23) or value > ((1 << 23) - 1):
            raise ValueError('offset calibration value out of range')
        v = value & 0xFFFFFF
        self._write_u24(register, v)

    def offset_calibration(self, register):
        """Read an offset-calibration register.

        Returns:
            int: Signed 24-bit register value.
        """
        return self._read_s24(register)

    def checksum(self):
        """Read the 32-bit CRC/checksum over the configuration registers."""
        return self._read_u32(_REG_CRC)

    def enable_checksum(self, enabled):
        """Enable or disable the CRC/checksum (CONFIG.CRC_ENABLE)."""
        cfg = self._read_u16(_REG_CONFIG)
        if enabled:
            cfg |= (1 << 8)
        else:
            cfg &= ~(1 << 8)
        self._write_u16(_REG_CONFIG, cfg)

    # --- Accumulation modes -----------------------------------------------

    def set_active_energy_mode(self, channel, mode):
        """Set the active-energy accumulation mode.

        Args:
            channel: ``'a'`` or ``'b'``.
            mode: ``'normal'``, ``'positive_only'`` or ``'absolute'``.
        """
        m = {'normal': 0, 'positive_only': 1, 'absolute': 2}[mode]
        acc = self._read_u24(_REG_ACCMODE)
        if channel == 'a':
            acc = (acc & ~(0x3)) | m
        elif channel == 'b':
            acc = (acc & ~(0x3 << 2)) | (m << 2)
        else:
            raise ValueError("channel must be 'a' or 'b'")
        self._write_u24(_REG_ACCMODE, acc)

    def set_reactive_energy_mode(self, channel, mode):
        """Set the reactive-energy accumulation mode.

        Args:
            channel: ``'a'`` or ``'b'``.
            mode: ``'normal'``, ``'antitamper'`` or ``'absolute'``.
        """
        m = {'normal': 0, 'antitamper': 1, 'absolute': 2}[mode]
        acc = self._read_u24(_REG_ACCMODE)
        if channel == 'a':
            acc = (acc & ~(0x3 << 4)) | (m << 4)
        elif channel == 'b':
            acc = (acc & ~(0x3 << 6)) | (m << 6)
        else:
            raise ValueError("channel must be 'a' or 'b'")
        self._write_u24(_REG_ACCMODE, acc)

    def set_apparent_energy_mode(self, channel, mode):
        """Set the apparent-energy accumulation mode.

        Args:
            channel: ``'a'`` or ``'b'``.
            mode: ``'normal'`` or ``'ampere_hour'``.
        """
        if mode not in ('normal', 'ampere_hour'):
            raise ValueError("mode must be 'normal' or 'ampere_hour'")
        acc = self._read_u24(_REG_ACCMODE)
        bit = 8 if channel == 'a' else 9
        if mode == 'ampere_hour':
            acc |= (1 << bit)
        else:
            acc &= ~(1 << bit)
        self._write_u24(_REG_ACCMODE, acc)

    def configure_line_cycle_accumulation(self, channels, half_cycles):
        """Enable line-cycle accumulation.

        Args:
            channels: Iterable of identifiers — any of ``'a_active'``,
                ``'b_active'``, ``'a_reactive'``, ``'b_reactive'``,
                ``'a_apparent'``, ``'b_apparent'``. Empty disables
                line-cycle accumulation entirely.
            half_cycles: Number of half-line-cycles (1–65535).
        """
        self._write_u16(_REG_LINECYC, half_cycles & 0xFFFF)
        lcyc = self._read_u8(_REG_LCYCMODE)
        mapping = {
            'a_active': 0,
            'b_active': 1,
            'a_reactive': 2,
            'b_reactive': 3,
            'a_apparent': 4,
            'b_apparent': 5,
        }
        for name, bit in mapping.items():
            if name in channels:
                lcyc |= (1 << bit)
            else:
                lcyc &= ~(1 << bit)
        self._write_u8(_REG_LCYCMODE, lcyc)

    def set_read_with_reset(self, enabled):
        """Enable or disable read-with-reset for the energy registers."""
        lcyc = self._read_u8(_REG_LCYCMODE)
        if enabled:
            lcyc |= (1 << 6)
        else:
            lcyc &= ~(1 << 6)
        self._write_u8(_REG_LCYCMODE, lcyc)

    def power_sign(self):
        """Read the sign bits from ACCMODE.

        Returns:
            dict: Mapping ``{active_a, active_b, reactive_a, reactive_b}``
                to ``True`` for negative.
        """
        acc = self._read_u24(_REG_ACCMODE)
        return {
            'active_a': bool(acc & (1 << 10)),
            'active_b': bool(acc & (1 << 11)),
            'reactive_a': bool(acc & (1 << 12)),
            'reactive_b': bool(acc & (1 << 13)),
        }

    def no_load_status(self):
        """Read the six unlatched no-load bits from ACCMODE.

        Returns:
            dict: Mapping ``{active_a, apparent_a, reactive_a, active_b,
                apparent_b, reactive_b}`` to ``True`` when in no-load.
        """
        acc = self._read_u24(_REG_ACCMODE)
        return {
            'active_a': bool(acc & (1 << 16)),
            'apparent_a': bool(acc & (1 << 17)),
            'reactive_a': bool(acc & (1 << 18)),
            'active_b': bool(acc & (1 << 19)),
            'apparent_b': bool(acc & (1 << 20)),
            'reactive_b': bool(acc & (1 << 21)),
        }

    # --- No-load, sag, peak, overcurrent/overvoltage ----------------------

    def configure_no_load(self, active=None, reactive=None, apparent=None):
        """Write one or more no-load thresholds.

        Args:
            active: Raw 24-bit active-power no-load threshold, or None
                to leave unchanged.
            reactive: Raw 24-bit reactive-power no-load threshold, or None.
            apparent: Raw 24-bit apparent-power no-load threshold, or None.
        """
        if active is not None:
            self._write_u24(_REG_AP_NOLOAD, active & 0xFFFFFF)
        if reactive is not None:
            self._write_u24(_REG_VAR_NOLOAD, reactive & 0xFFFFFF)
        if apparent is not None:
            self._write_u24(_REG_VA_NOLOAD, apparent & 0xFFFFFF)

    def disable_no_load(self, active=False, reactive=False, apparent=False):
        """Disable one or more no-load features via DISNOLOAD."""
        reg = self._read_u8(_REG_DISNOLOAD)
        if active:
            reg |= (1 << 0)
        else:
            reg &= ~(1 << 0)
        if reactive:
            reg |= (1 << 1)
        else:
            reg &= ~(1 << 1)
        if apparent:
            reg |= (1 << 2)
        else:
            reg &= ~(1 << 2)
        self._write_u8(_REG_DISNOLOAD, reg)

    def configure_sag(self, half_cycles, level):
        """Configure sag detection.

        Args:
            half_cycles: 0–255 (0 disables).
            level: Raw 24-bit sag voltage level.
        """
        self._write_u8(_REG_SAGCYC, half_cycles & 0xFF)
        self._write_u24(_REG_SAGLVL, level & 0xFFFFFF)

    def peak_voltage(self):
        """Read peak voltage (does not reset)."""
        raw = self._read_u24(_REG_VPEAK)
        return raw * self._voltage_scale()

    def peak_current_a(self):
        """Read peak Current Channel A (does not reset)."""
        raw = self._read_u24(_REG_IAPEAK)
        return raw * self._current_scale(self._current_gain_a)

    def peak_current_b(self):
        """Read peak Current Channel B (does not reset)."""
        raw = self._read_u24(_REG_IBPEAK)
        return raw * self._current_scale(self._current_gain_b)

    def read_reset_peak_voltage(self):
        """Read-and-reset peak voltage."""
        raw = self._read_u24(_REG_RSTVPEAK)
        return raw * self._voltage_scale()

    def read_reset_peak_current_a(self):
        """Read-and-reset peak Current Channel A."""
        raw = self._read_u24(_REG_RSTIAPEAK)
        return raw * self._current_scale(self._current_gain_a)

    def read_reset_peak_current_b(self):
        """Read-and-reset peak Current Channel B."""
        raw = self._read_u24(_REG_RSTIBPEAK)
        return raw * self._current_scale(self._current_gain_b)

    def configure_overvoltage(self, threshold):
        """Set the overvoltage threshold.

        Args:
            threshold: Threshold in volts (same scale as ``voltage()``).
        """
        raw = int((threshold * (_ADC_FS_CODE * self._pga_v)) /
                  (_ADC_FS_VOLTS * self._voltage_gain))
        if raw < 0:
            raw = 0
        if raw > 0xFFFFFF:
            raw = 0xFFFFFF
        self._write_u24(_REG_OVLVL, raw)

    def configure_overcurrent(self, threshold):
        """Set the overcurrent threshold (shared by both current channels).

        Args:
            threshold: Threshold in amperes.
        """
        raw = int((threshold * _ADC_FS_CODE) / _ADC_FS_VOLTS)
        if raw < 0:
            raw = 0
        if raw > 0xFFFFFF:
            raw = 0xFFFFFF
        self._write_u24(_REG_OILVL, raw)

    # --- Zero-crossing, REVP, alternate outputs, CF pulses ----------------

    def configure_zero_crossing(self, timeout_half_cycles=0xFFFF,
                                edge='both', current_channel='a'):
        """Configure zero-crossing detection.

        Args:
            timeout_half_cycles: ZXTOUT half-line-cycle timeout.
            edge: ``'both'``, ``'positive'`` or ``'negative'``.
            current_channel: ``'a'`` or ``'b'`` for ZX_I.
        """
        self._write_u16(_REG_ZXTOUT, timeout_half_cycles & 0xFFFF)
        cfg = self._read_u16(_REG_CONFIG)
        cfg &= ~(0x3 << 12)
        if edge == 'positive':
            cfg |= (0x2 << 12)
        elif edge == 'negative':
            cfg |= (0x1 << 12)
        if current_channel == 'b':
            cfg |= (1 << 11)
        else:
            cfg &= ~(1 << 11)
        self._write_u16(_REG_CONFIG, cfg)

    def configure_revp(self, pulse_mode=False, track_cf2=False):
        """Configure the REVP pin behaviour."""
        cfg = self._read_u16(_REG_CONFIG)
        cfg &= ~((1 << 5) | (1 << 4))
        if pulse_mode:
            cfg |= (1 << 5)
        if track_cf2:
            cfg |= (1 << 4)
        self._write_u16(_REG_CONFIG, cfg)

    def configure_alt_output(self, pin, function):
        """Configure the alternate output function on a pin.

        Args:
            pin: ``'zx'``, ``'zx_i'`` or ``'revp'``.
            function: 4-bit code (one of ADE7953AltOutput's constants).
        """
        if not 0 <= function <= 0xF:
            raise ValueError('function must be a 4-bit value')
        alt = self._read_u16(_REG_ALT_OUTPUT)
        if pin == 'zx':
            alt = (alt & ~0x000F) | (function & 0xF)
        elif pin == 'zx_i':
            alt = (alt & ~0x00F0) | ((function & 0xF) << 4)
        elif pin == 'revp':
            alt = (alt & ~0x0F00) | ((function & 0xF) << 8)
        else:
            raise ValueError("pin must be 'zx', 'zx_i' or 'revp'")
        self._write_u16(_REG_ALT_OUTPUT, alt)

    def configure_cf(self, cf, source, denominator=0x3F):
        """Configure a CF output.

        Args:
            cf: ``1`` or ``2``.
            source: Source code (one of ADE7953CFSource's constants).
            denominator: 0–0xFFFF. The denominator register must be
                written twice in succession per the datasheet.
        """
        if cf not in (1, 2):
            raise ValueError('cf must be 1 or 2')
        if not 0 <= source <= 0xF:
            raise ValueError('source must be a 4-bit value')
        if denominator < 0 or denominator > 0xFFFF:
            raise ValueError('denominator must be 0–0xFFFF')
        mode = self._read_u16(_REG_CFMODE)
        if cf == 1:
            mode = (mode & ~0x000F) | (source & 0xF)
            # Re-enable CF1 (clear CF1DIS bit 8).
            mode &= ~(1 << 8)
        else:
            mode = (mode & ~0x00F0) | ((source & 0xF) << 4)
            mode &= ~(1 << 9)
        self._write_u16(_REG_CFMODE, mode)
        den_reg = _REG_CF1DEN if cf == 1 else _REG_CF2DEN
        self._write_u16(den_reg, denominator)
        self._write_u16(den_reg, denominator)

    def enable_cf(self, cf):
        """Enable a previously disabled CF output."""
        mode = self._read_u16(_REG_CFMODE)
        bit = 8 if cf == 1 else 9
        mode &= ~(1 << bit)
        self._write_u16(_REG_CFMODE, mode)

    def disable_cf(self, cf):
        """Disable a CF output."""
        mode = self._read_u16(_REG_CFMODE)
        bit = 8 if cf == 1 else 9
        mode |= (1 << bit)
        self._write_u16(_REG_CFMODE, mode)

    # --- Interrupts -------------------------------------------------------

    def _is_b_source(self, source):
        return source in (
            ADE7953Source.AEHFB,
            ADE7953Source.VAREHFB,
            ADE7953Source.VAEHFB,
            ADE7953Source.AEOFB,
            ADE7953Source.VAREOFB,
            ADE7953Source.VAEOFB,
            ADE7953Source.AP_NOLOADB,
            ADE7953Source.VAR_NOLOADB,
            ADE7953Source.VA_NOLOADB,
            ADE7953Source.APSIGN_B,
            ADE7953Source.VARSIGN_B,
            ADE7953Source.ZXTO_IB,
            ADE7953Source.ZXIB,
            ADE7953Source.OIB,
        )

    def enable_interrupt(self, source):
        """Enable one interrupt source."""
        if self._is_b_source(source):
            reg = self._read_u24(_REG_IRQENB)
            reg |= source
            self._write_u24(_REG_IRQENB, reg)
        else:
            reg = self._read_u24(_REG_IRQENA)
            reg |= source
            self._write_u24(_REG_IRQENA, reg)

    def disable_interrupt(self, source):
        """Disable one interrupt source (Reset cannot be disabled)."""
        if source == _RESET:
            return
        if self._is_b_source(source):
            reg = self._read_u24(_REG_IRQENB)
            reg &= ~source
            self._write_u24(_REG_IRQENB, reg)
        else:
            reg = self._read_u24(_REG_IRQENA)
            reg &= ~source
            self._write_u24(_REG_IRQENA, reg)

    def interrupt_status(self, group='a'):
        """Read the interrupt status register (does not clear).

        Args:
            group: ``'a'`` (default) or ``'b'``.
        """
        if group == 'b':
            return self._read_u24(_REG_IRQSTATB)
        return self._read_u24(_REG_IRQSTATA)

    def clear_interrupts(self, group='a'):
        """Read-and-clear the interrupt status register.

        Returns:
            int: The pre-clear status value.
        """
        if group == 'b':
            return self._read_u24(_REG_RSTIRQSTATB)
        return self._read_u24(_REG_RSTIRQSTATA)

    # --- Communication, reset, miscellaneous ------------------------------

    def lock_communication_interface(self):
        """Lock the active interface; subsequent comm uses whichever was first."""
        cfg = self._read_u16(_REG_CONFIG)
        cfg &= ~(1 << 15)
        self._write_u16(_REG_CONFIG, cfg)

    def set_write_protection(self, protect_8bit, protect_16bit, protect_24_32bit):
        """Write WRITE_PROTECT. The WRITE_PROTECT register itself always
        remains writable so protection can be lifted again."""
        value = 0
        if protect_8bit:
            value |= (1 << 0)
        if protect_16bit:
            value |= (1 << 1)
        if protect_24_32bit:
            value |= (1 << 2)
        self._write_u8(_REG_WRITE_PROTECT, value)

    def last_operation(self):
        """Return a dict describing the last successful bus operation."""
        op = self._read_u8(_REG_LAST_OP)
        addr = self._read_u16(_REG_LAST_ADD)
        data = self._read_u32(_REG_LAST_RWDATA_32)
        return {
            'op': 'read' if op == 0x35 else ('write' if op == 0xCA else 'unknown'),
            'address': addr,
            'data': data,
        }

    def set_external_reference(self, enabled):
        """Enable or disable an external voltage reference applied to REF."""
        self._write_u8(_REG_EX_REF, 1 if enabled else 0)

    def version(self):
        """Read the silicon version register."""
        return self._read_u8(_REG_VERSION)

    def reset(self):
        """Software-reset the chip, wait for restart, re-apply the
        mandatory power-up register setting. All calibration/
        configuration registers revert to power-on defaults."""
        cfg = self._read_u16(_REG_CONFIG)
        cfg |= (1 << 7)
        self._write_u16(_REG_CONFIG, cfg)
        _delay_ms(110)
        # Reset clears every other register; re-apply the mandatory
        # power-up sequence.
        self._write_u8(_REG_120_UNLOCK_ADDR, _REG_120_UNLOCK)
        self._write_u16(_REG_INTERNAL_RES, _REG_120_VALUE)
        self._pga_a = 1
        self._pga_b = 1
        self._pga_v = 1