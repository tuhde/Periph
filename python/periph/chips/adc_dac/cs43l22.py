"""CS43L22 — low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic).

The CS43L22 is configured entirely through its I²C control port (7-bit address
0x4A with AD0 = DGND, 0x4B with AD0 = VL; 100 kHz maximum). Audio data travels
over a separate I²S / PCM serial port (SDIN, SCLK, LRCK, MCLK) that this driver
does not touch — the application supplies MCLK and the audio stream and calls
``start()`` once the clocks are running and ``stop()`` before they go away.

Register access uses a one-byte memory address pointer, so the driver needs a
``RegisterConnection`` with ``reg_bytes = 1``. Registers are written one at a
time (the optional MAP auto-increment bit is not used). The same driver file
is used on MicroPython, CircuitPython and Linux hosts.

Order matters: all configuration is written while ``POWER_CTL_1`` is 0x01
(powered down), then the required initialization block (registers 0x00, 0x47
and 0x32) runs, and only ``start()`` writes 0x9E (powered up).

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x4A / 0x4B).
    res_pin: Optional OutputPin wired to the active-low RESET pin.
"""

import time

I2C_ADDRESS = 0x4A

# Registers.
_REG_ID = 0x01
_REG_POWER_CTL_1 = 0x02
_REG_POWER_CTL_2 = 0x04
_REG_CLOCKING_CTL = 0x05
_REG_INTERFACE_CTL_1 = 0x06
_REG_INTERFACE_CTL_2 = 0x07
_REG_PASSTHRU_A_SEL = 0x08
_REG_PASSTHRU_B_SEL = 0x09
_REG_ANALOG_ZC_SR = 0x0A
_REG_PLAYBACK_CTL_1 = 0x0D
_REG_MISC_CTL = 0x0E
_REG_PLAYBACK_CTL_2 = 0x0F
_REG_PASSTHRU_A_VOL = 0x14
_REG_PASSTHRU_B_VOL = 0x15
_REG_PCM_A_VOL = 0x1A
_REG_PCM_B_VOL = 0x1B
_REG_BEEP_FREQ_ON = 0x1C
_REG_BEEP_VOL_OFF = 0x1D
_REG_BEEP_TONE_CFG = 0x1E
_REG_TONE_CTL = 0x1F
_REG_MASTER_A_VOL = 0x20
_REG_MASTER_B_VOL = 0x21
_REG_HP_A_VOL = 0x22
_REG_HP_B_VOL = 0x23
_REG_SPK_A_VOL = 0x24
_REG_SPK_B_VOL = 0x25
_REG_PCM_MIXER_SWAP = 0x26
_REG_LIMIT_CTL_1 = 0x27
_REG_LIMIT_CTL_2 = 0x28
_REG_LIMIT_ATTACK = 0x29
_REG_STATUS = 0x2E
_REG_BATTERY_COMP = 0x2F
_REG_VP_LEVEL = 0x30
_REG_SPEAKER_STATUS = 0x31
_REG_CHARGE_PUMP_FREQ = 0x34

# Registers of the required initialization block (datasheet §4.11).
_REG_INIT_00 = 0x00
_REG_INIT_32 = 0x32
_REG_INIT_47 = 0x47

_CHIP_ID_MASK = 0xF8
_CHIP_ID = 0xE0

_POWER_DOWN_1 = 0x01
_POWER_DOWN_2 = 0x9F
_POWER_UP = 0x9E

# Documented registers reachable through read_register / write_register.
_READABLE = frozenset(
    [0x00, 0x01, 0x02, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F,
     0x14, 0x15, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25,
     0x26, 0x27, 0x28, 0x29, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x34, 0x47])
_READ_ONLY = frozenset([0x01, 0x2E, 0x30, 0x31])

# Output power modes for set_outputs.
OUT_FOLLOW_LOW = 0
OUT_FOLLOW_HIGH = 1
OUT_ON = 2
OUT_OFF = 3

# Serial port formats for set_interface.
FMT_LJ = 0
FMT_I2S = 1
FMT_RJ = 2

# Beep modes for set_beep.
BEEP_OFF = 0
BEEP_SINGLE = 1
BEEP_MULTIPLE = 2
BEEP_CONTINUOUS = 3

# PCM channel sources for set_pcm_swap.
SRC_NORMAL = 0
SRC_MIX = 1
SRC_SWAPPED = 3

# Headphone gain indices for set_headphone_gain.
GAIN_0_3959 = 0
GAIN_0_4571 = 1
GAIN_0_5111 = 2
GAIN_0_6047 = 3
GAIN_0_7099 = 4
GAIN_0_8399 = 5
GAIN_1_000 = 6
GAIN_1_143 = 7

_HP_GAINS = (0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430)
_LIMIT_DB = (0, -3, -6, -9, -12, -18, -24, -30)

# Datasheet Table 1: (mclk_hz, video_clk, mclk_div2, ratio, [(fs_hz, ratio_override)]) rows are
# expanded in clocking_for(); the nominal sample rates are grouped here.
_FS_ROUND = (8000.0, 12000.0, 16000.0, 24000.0, 32000.0, 48000.0, 96000.0)
_FS_44K = (11025.0, 22050.0, 44100.0, 88200.0)
_FS_FRAC = (12000000.0 / 1088, 12000000.0 / 544, 12000000.0 / 272, 12000000.0 / 136)
_FS_27M = (8000.0, 12000.0, 16000.0, 24000.0, 32000.0, 48000.0)
_FS_27M_FRAC = _FS_FRAC[:3]


def _floor(x):
    i = int(x)
    return i - 1 if x < i else i


def _round_half_up(x):
    return _floor(x + 0.5)


def _clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


def _half_db_code(db, lo, hi):
    """Two's-complement 0.5 dB code for ``db`` clamped to [lo, hi] dB."""
    return _round_half_up(_clamp(db, lo, hi) * 2) & 0xFF


def _signed(code):
    return code - 256 if code > 127 else code


def _limited_volume_code(db):
    """Headphone / speaker volume code: 0 … −96 dB, 0x01 below that (muted)."""
    if db < -96.0:
        return 0x01
    return _round_half_up(_clamp(db, -96.0, 0.0) * 2) & 0xFF


def _speed_group(fs_hz):
    group = any(abs(fs_hz - r) < 1.0 for r in (8000.0, 16000.0, 32000.0))
    if fs_hz <= 12100:
        speed = 3
    elif fs_hz <= 24100:
        speed = 2
    elif fs_hz <= 48100:
        speed = 1
    else:
        speed = 0
    return speed, group


def _near(value, candidates):
    for c in candidates:
        if abs(value - c) <= c * 1e-4:
            return True
    return False


def clocking_for(mclk_hz, fs_hz):
    """Datasheet Table 1 clocking settings for an MCLK / sample-rate pair.

    Args:
        mclk_hz: MCLK in Hz — 12.288, 11.2896, 18.432, 16.9344, 12.0, 24.0 or 27.0 MHz.
        fs_hz: Sample rate in Hz (the rates listed for that MCLK in Table 1).

    Returns:
        tuple: ``(auto, speed, group_32k, video_clk, ratio, mclk_div2)`` — the
        arguments of ``CS43L22Full.set_clocking`` with ``auto = False``.

    Raises:
        ValueError: If the combination is not in the datasheet table.
    """
    video = False
    div2 = False
    ratio = None
    if _near(mclk_hz, (12288000.0, 18432000.0)):
        if _near(fs_hz, _FS_ROUND):
            ratio = 0
    elif _near(mclk_hz, (11289600.0,)):
        if _near(fs_hz, _FS_44K):
            ratio = 0
    elif _near(mclk_hz, (16934400.0,)):
        if _near(fs_hz, _FS_44K):
            ratio = 0
        elif _near(fs_hz, (16934400.0 / 2112,)):
            ratio = 2
    elif _near(mclk_hz, (12000000.0, 24000000.0)):
        div2 = mclk_hz > 18000000.0
        if _near(fs_hz, _FS_ROUND):
            ratio = 1
        elif _near(fs_hz, _FS_FRAC):
            ratio = 3
    elif _near(mclk_hz, (27000000.0,)):
        video = True
        if _near(fs_hz, _FS_27M):
            ratio = 1
        elif _near(fs_hz, _FS_27M_FRAC):
            ratio = 3
    if ratio is None:
        raise ValueError('MCLK / sample-rate combination is not in the datasheet table')
    speed, group = _speed_group(fs_hz)
    return (False, speed, group, video, ratio, div2)


class CS43L22Minimal:
    """CS43L22 stereo DAC — minimal interface.

    Brings the DAC to a playing state for an I²S source and controls volume and
    mute of the headphone output. Defaults: I²S slave, 24-bit, clock speed
    auto-detected from the MCLK / LRCK ratio (12.288, 11.2896, 18.432 and
    16.9344 MHz MCLK at the 256·Fs family), headphone amplifiers always on,
    speaker amplifiers off, headphone gain 0.6047, master volume 0 dB, digital
    soft ramp on. The constructor resets the chip (when ``res_pin`` is given),
    checks its ID, loads these defaults and runs the required initialization;
    the chip stays powered down until ``start()`` is called with MCLK running.

    Args:
        connection: RegisterConnection (I²C) pointing at the device (0x4A / 0x4B).
        res_pin: Optional OutputPin for the active-low RESET line.

    Raises:
        OSError: If the chip ID register does not read 0xE0 in bits 7:3.
    """

    def __init__(self, connection, res_pin=None):
        self._conn = connection
        self._res_pin = res_pin
        self._init_chip()

    def _pulse_reset(self):
        self._res_pin.set(False)
        time.sleep(0.002)
        self._res_pin.set(True)
        time.sleep(0.002)

    def _read(self, reg):
        return self._conn.read_reg(reg, 1)[0]

    def _write(self, reg, value):
        self._conn.write_reg(reg, value & 0xFF)

    def _update(self, reg, mask, value):
        self._write(reg, (self._read(reg) & ~mask & 0xFF) | (value & mask))

    def _set_bit(self, reg, bit, on):
        self._update(reg, 1 << bit, (1 << bit) if on else 0)

    def _init_chip(self):
        if self._res_pin is not None:
            self._pulse_reset()
        if (self._read(_REG_ID) & _CHIP_ID_MASK) != _CHIP_ID:
            raise OSError('CS43L22 not found: chip ID mismatch')
        self._write(_REG_POWER_CTL_1, _POWER_DOWN_1)
        self._write(_REG_POWER_CTL_2, 0xAF)
        self._write(_REG_CLOCKING_CTL, 0xA0)
        self._write(_REG_INTERFACE_CTL_1, 0x04)
        self._write(_REG_PLAYBACK_CTL_1, 0x60)
        self._write(_REG_MASTER_A_VOL, 0x00)
        self._write(_REG_MASTER_B_VOL, 0x00)
        # Required initialization settings (datasheet §4.11).
        self._write(_REG_INIT_00, 0x99)
        self._write(_REG_INIT_47, 0x80)
        v = self._read(_REG_INIT_32)
        self._write(_REG_INIT_32, v | 0x80)
        self._write(_REG_INIT_32, v & 0x7F)
        self._write(_REG_INIT_00, 0x00)

    def start(self):
        """Power the chip up (``POWER_CTL_1`` = 0x9E).

        Call after MCLK, SCLK and LRCK are running.
        """
        self._write(_REG_POWER_CTL_1, _POWER_UP)

    def stop(self):
        """Run the power-down sequence.

        Mutes the DAC and speaker outputs, disables digital and analog soft
        ramp and zero-cross, writes ``POWER_CTL_1`` = 0x9F and waits for the
        100 µs the charge pump needs. MCLK may be removed after it returns.
        """
        self._update(_REG_PLAYBACK_CTL_2, 0xF0, 0xF0)
        self._update(_REG_MISC_CTL, 0x03, 0x00)
        self._update(_REG_ANALOG_ZC_SR, 0x0F, 0x00)
        self._write(_REG_POWER_CTL_1, _POWER_DOWN_2)
        time.sleep(0.001)

    def set_volume(self, db):
        """Set the master volume of both channels.

        Args:
            db: −102.0 … +12.0 dB, rounded to the nearest 0.5 dB step (clamped).
        """
        code = _half_db_code(db, -102.0, 12.0)
        self._write(_REG_MASTER_A_VOL, code)
        self._write(_REG_MASTER_B_VOL, code)

    def get_volume(self):
        """Return the master volume of channel A in dB."""
        return _signed(self._read(_REG_MASTER_A_VOL)) / 2.0

    def mute(self, on=True):
        """Mute or unmute both headphone channels.

        Args:
            on: True to mute (default), False to unmute.
        """
        self._update(_REG_PLAYBACK_CTL_2, 0xC0, 0xC0 if on else 0x00)


class CS43L22Full(CS43L22Minimal):
    """CS43L22 stereo DAC — full interface.

    Adds per-channel volumes, output power selection, clocking and serial
    interface configuration, tone control, the beep generator, the peak
    limiter, soft ramp / zero-cross, analog passthrough, battery compensation,
    status reads and raw register access.

    Args:
        connection: RegisterConnection (I²C) pointing at the device (0x4A / 0x4B).
        res_pin: Optional OutputPin for the active-low RESET line.
    """

    OUT_FOLLOW_LOW = OUT_FOLLOW_LOW
    OUT_FOLLOW_HIGH = OUT_FOLLOW_HIGH
    OUT_ON = OUT_ON
    OUT_OFF = OUT_OFF
    FMT_LJ = FMT_LJ
    FMT_I2S = FMT_I2S
    FMT_RJ = FMT_RJ
    BEEP_OFF = BEEP_OFF
    BEEP_SINGLE = BEEP_SINGLE
    BEEP_MULTIPLE = BEEP_MULTIPLE
    BEEP_CONTINUOUS = BEEP_CONTINUOUS
    SRC_NORMAL = SRC_NORMAL
    SRC_MIX = SRC_MIX
    SRC_SWAPPED = SRC_SWAPPED
    GAIN_0_3959 = GAIN_0_3959
    GAIN_0_4571 = GAIN_0_4571
    GAIN_0_5111 = GAIN_0_5111
    GAIN_0_6047 = GAIN_0_6047
    GAIN_0_7099 = GAIN_0_7099
    GAIN_0_8399 = GAIN_0_8399
    GAIN_1_000 = GAIN_1_000
    GAIN_1_143 = GAIN_1_143

    def read_id(self):
        """Read the chip ID and revision.

        Returns:
            tuple: ``(chip_id, revision)`` — 0x1C and the 3-bit revision.
        """
        v = self._read(_REG_ID)
        return (v >> 3, v & 0x07)

    def reset(self):
        """Pulse RESET and re-run the Minimal initialization.

        Raises:
            RuntimeError: If no ``res_pin`` was given (use ``stop()`` then a new instance).
        """
        if self._res_pin is None:
            raise RuntimeError('reset requires a res_pin')
        self._init_chip()

    def set_outputs(self, headphone, speaker):
        """Select the power mode of the headphone and speaker amplifiers.

        Args:
            headphone: ``OUT_FOLLOW_LOW`` / ``OUT_FOLLOW_HIGH`` / ``OUT_ON`` / ``OUT_OFF`` (0–3).
            speaker: Same coding for the speaker amplifiers.

        Raises:
            ValueError: If a mode is not 0–3.
        """
        if not (0 <= headphone <= 3 and 0 <= speaker <= 3):
            raise ValueError('output mode must be 0-3')
        self._write(_REG_POWER_CTL_2, (headphone << 6) | (headphone << 4) | (speaker << 2) | speaker)

    def set_volume_channels(self, left_db, right_db):
        """Set the master volume of channel A (left) and B (right) independently.

        Args:
            left_db: −102 … +12 dB.
            right_db: −102 … +12 dB.
        """
        self._write(_REG_MASTER_A_VOL, _half_db_code(left_db, -102.0, 12.0))
        self._write(_REG_MASTER_B_VOL, _half_db_code(right_db, -102.0, 12.0))

    def set_headphone_volume(self, left_db, right_db=None):
        """Set the headphone volume.

        Args:
            left_db: 0 … −96 dB; below −96 dB the channel is muted (code 0x01).
            right_db: Right channel; None uses ``left_db``.
        """
        self._write(_REG_HP_A_VOL, _limited_volume_code(left_db))
        self._write(_REG_HP_B_VOL, _limited_volume_code(left_db if right_db is None else right_db))

    def set_speaker_volume(self, left_db, right_db=None):
        """Set the speaker volume.

        Args:
            left_db: 0 … −96 dB; below −96 dB the channel is muted.
            right_db: Right channel; None uses ``left_db``.
        """
        self._write(_REG_SPK_A_VOL, _limited_volume_code(left_db))
        self._write(_REG_SPK_B_VOL, _limited_volume_code(left_db if right_db is None else right_db))

    def set_pcm_volume(self, left_db, right_db=None):
        """Set the PCM mixer volume (the mute bits are kept).

        Args:
            left_db: −51.5 … +12 dB.
            right_db: Right channel; None uses ``left_db``.
        """
        if right_db is None:
            right_db = left_db
        self._update(_REG_PCM_A_VOL, 0x7F, _half_db_code(left_db, -51.5, 12.0))
        self._update(_REG_PCM_B_VOL, 0x7F, _half_db_code(right_db, -51.5, 12.0))

    def mute_pcm(self, left, right=None):
        """Mute or unmute the PCM channels.

        Args:
            left: True to mute channel A.
            right: Channel B; None uses ``left``.
        """
        self._set_bit(_REG_PCM_A_VOL, 7, left)
        self._set_bit(_REG_PCM_B_VOL, 7, left if right is None else right)

    def set_headphone_gain(self, index):
        """Select the headphone / line analog gain.

        Args:
            index: 0–7 (``GAIN_0_3959`` … ``GAIN_1_143``).

        Raises:
            ValueError: If the index is not 0–7.
        """
        if not 0 <= index <= 7:
            raise ValueError('gain index must be 0-7')
        self._update(_REG_PLAYBACK_CTL_1, 0xE0, index << 5)

    def headphone_gain(self):
        """Return the headphone analog gain multiplier (0.3959 … 1.143)."""
        return _HP_GAINS[self._read(_REG_PLAYBACK_CTL_1) >> 5]

    def mute_outputs(self, headphone=None, speaker=None):
        """Mute or unmute the headphone and speaker outputs (both channels).

        Args:
            headphone: True to mute, False to unmute, None to keep.
            speaker: Same for the speaker.
        """
        if headphone is not None:
            self._update(_REG_PLAYBACK_CTL_2, 0xC0, 0xC0 if headphone else 0)
        if speaker is not None:
            self._update(_REG_PLAYBACK_CTL_2, 0x30, 0x30 if speaker else 0)

    def set_speaker_mode(self, mono, swap, mute_50_50=False):
        """Configure the speaker channels.

        Args:
            mono: Mix both channels to mono (SPKMONO).
            swap: Swap the speaker channels (SPKSWAP).
            mute_50_50: Mute as an exact 50 % duty cycle (MUTE50/50).
        """
        self._update(_REG_PLAYBACK_CTL_2, 0x07,
                     (0x02 if mono else 0) | (0x04 if swap else 0) | (0x01 if mute_50_50 else 0))

    def set_clocking(self, auto=True, speed=1, group_32k=False, video_clk=False, ratio=0, mclk_div2=False):
        """Write ``CLOCKING_CTL``.

        Args:
            auto: Auto-detect the speed mode from MCLK / LRCK (slave mode).
            speed: Speed mode 0–3 (double, single, half, quarter) when ``auto`` is False.
            group_32k: True for 8, 16 and 32 kHz sample rates.
            video_clk: True if MCLK is 27 MHz.
            ratio: Internal MCLK / LRCK ratio code 0–3 (128, 125, 132, 136).
            mclk_div2: Divide MCLK by 2.

        Raises:
            ValueError: If ``speed`` or ``ratio`` is not 0–3.
        """
        if not (0 <= speed <= 3 and 0 <= ratio <= 3):
            raise ValueError('speed and ratio must be 0-3')
        self._write(_REG_CLOCKING_CTL,
                    (0x80 if auto else 0) | (speed << 5) | (0x10 if group_32k else 0)
                    | (0x08 if video_clk else 0) | (ratio << 1) | (1 if mclk_div2 else 0))

    def clocking_for(self, mclk_hz, fs_hz):
        """See the module-level ``clocking_for``."""
        return clocking_for(mclk_hz, fs_hz)

    def set_interface(self, master=False, fmt=FMT_I2S, word_length=0, dsp=False,
                      invert_sclk=False, sclk_eq_mclk=False, invert_switch=False):
        """Configure the serial audio port (``INTERFACE_CTL_1`` / ``INTERFACE_CTL_2``).

        Args:
            master: True for master mode (clocks are outputs).
            fmt: ``FMT_LJ`` (0), ``FMT_I2S`` (1) or ``FMT_RJ`` (2).
            word_length: AWL code 0–3 (DSP: 32/24/20/16 bit; right-justified: 24/20/18/16 bit).
            dsp: DSP (data-packed) mode; requires ``FMT_LJ``.
            invert_sclk: Invert the SCLK polarity.
            sclk_eq_mclk: SCLK = MCLK (master mode).
            invert_switch: Invert the SPKR/HP_SW pin sense.

        Raises:
            ValueError: If an argument is out of range or ``dsp`` is used without ``FMT_LJ``.
        """
        if not (0 <= fmt <= 2 and 0 <= word_length <= 3):
            raise ValueError('fmt must be 0-2 and word_length 0-3')
        if dsp and fmt != FMT_LJ:
            raise ValueError('DSP mode requires FMT_LJ')
        self._write(_REG_INTERFACE_CTL_1,
                    (0x80 if master else 0) | (0x40 if invert_sclk else 0) | (0x10 if dsp else 0)
                    | (fmt << 2) | word_length)
        self._write(_REG_INTERFACE_CTL_2, (0x40 if sclk_eq_mclk else 0) | (0x08 if invert_switch else 0))

    def set_tone(self, treble_db, bass_db, treble_corner=0, bass_corner=0, enabled=True):
        """Configure the treble / bass shelving filters.

        Args:
            treble_db: −10.5 … +12 dB (nearest 1.5 dB step).
            bass_db: −10.5 … +12 dB (nearest 1.5 dB step).
            treble_corner: Corner 0–3 (5 / 7 / 10 / 15 kHz).
            bass_corner: Corner 0–3 (50 / 100 / 200 / 250 Hz).
            enabled: Enable the tone control (TCEN).

        Raises:
            ValueError: If a corner index is not 0–3.
        """
        if not (0 <= treble_corner <= 3 and 0 <= bass_corner <= 3):
            raise ValueError('corner must be 0-3')
        treb = _clamp(8 - _round_half_up(treble_db / 1.5), 0, 15)
        bass = _clamp(8 - _round_half_up(bass_db / 1.5), 0, 15)
        self._write(_REG_TONE_CTL, (treb << 4) | bass)
        self._update(_REG_BEEP_TONE_CFG, 0x1F, (treble_corner << 3) | (bass_corner << 1) | (1 if enabled else 0))

    def set_beep(self, frequency, on_time, off_time, volume_db, mode, mix=True):
        """Program and start the beep generator.

        With ``mode`` = ``BEEP_OFF`` only the beep is stopped. Otherwise the
        timing registers are written first and the mode last.

        Args:
            frequency: Frequency code 0–15 (260.87 Hz … 2181.82 Hz).
            on_time: On-time code 0–15 (~86 ms … ~5.2 s).
            off_time: Off-time code 0–7 (~1.23 s … ~10.8 s).
            volume_db: +6 … −56 dB in 2 dB steps (nearest).
            mode: ``BEEP_OFF`` / ``BEEP_SINGLE`` / ``BEEP_MULTIPLE`` / ``BEEP_CONTINUOUS``.
            mix: Mix the beep with the PCM stream (BEEPMIXDIS = 0).

        Raises:
            ValueError: If an argument is out of range.
            RuntimeError: If a beep is active and ``mode`` is not ``BEEP_OFF``.
        """
        if not (0 <= frequency <= 15 and 0 <= on_time <= 15 and 0 <= off_time <= 7 and 0 <= mode <= 3):
            raise ValueError('beep argument out of range')
        if mode == BEEP_OFF:
            self.stop_beep()
            return
        if (self._read(_REG_BEEP_TONE_CFG) & 0xC0) != 0:
            raise RuntimeError('beep active: call stop_beep() before changing the timing')
        n = _clamp(_round_half_up((6.0 - volume_db) / 2.0), 0, 31)
        self._write(_REG_BEEP_FREQ_ON, (frequency << 4) | on_time)
        self._write(_REG_BEEP_VOL_OFF, (off_time << 5) | ((6 - n) & 0x1F))
        self._update(_REG_BEEP_TONE_CFG, 0xE0, (mode << 6) | (0 if mix else 0x20))

    def stop_beep(self):
        """Stop the beep generator (``BEEP[1:0]`` = 0)."""
        self._update(_REG_BEEP_TONE_CFG, 0xC0, 0)

    def set_limiter(self, enabled, max_db=0, cushion_db=0, release=0x3F, attack=0, limit_all=True,
                    soft_ramp_disable=False, zero_cross_disable=False):
        """Configure the peak limiter.

        Args:
            enabled: Enable the limiter.
            max_db: Maximum threshold, nearest of 0, −3, −6, −9, −12, −18, −24, −30 dB.
            cushion_db: Cushion threshold, same steps.
            release: Release rate 0 (fastest) … 63 (slowest).
            attack: Attack rate 0 … 63.
            limit_all: Limit both channels when either exceeds the threshold.
            soft_ramp_disable: Disable soft ramp while limiting.
            zero_cross_disable: Disable zero-cross while limiting.

        Raises:
            ValueError: If ``release`` or ``attack`` is not 0–63.
        """
        if not (0 <= release <= 63 and 0 <= attack <= 63):
            raise ValueError('release and attack must be 0-63')
        self._write(_REG_LIMIT_CTL_1,
                    (_nearest_index(max_db) << 5) | (_nearest_index(cushion_db) << 2)
                    | (0x02 if soft_ramp_disable else 0) | (1 if zero_cross_disable else 0))
        self._write(_REG_LIMIT_CTL_2, (0x80 if enabled else 0) | (0x40 if limit_all else 0) | release)
        self._write(_REG_LIMIT_ATTACK, attack)

    def set_soft_ramp(self, digital, analog_left=False, analog_right=False):
        """Enable or disable soft-ramp volume transitions.

        Args:
            digital: Digital volume soft ramp (DIGSFT).
            analog_left: Analog (passthrough) channel A soft ramp (ANLGSFTA).
            analog_right: Analog channel B soft ramp (ANLGSFTB).
        """
        self._set_bit(_REG_MISC_CTL, 1, digital)
        self._update(_REG_ANALOG_ZC_SR, 0x0A, (0x02 if analog_left else 0) | (0x08 if analog_right else 0))

    def set_zero_cross(self, digital, analog_left=False, analog_right=False):
        """Enable or disable zero-cross volume transitions.

        Args:
            digital: Digital volume zero-cross (DIGZC).
            analog_left: Analog channel A zero-cross (ANLGZCA).
            analog_right: Analog channel B zero-cross (ANLGZCB).
        """
        self._set_bit(_REG_MISC_CTL, 0, digital)
        self._update(_REG_ANALOG_ZC_SR, 0x05, (0x01 if analog_left else 0) | (0x04 if analog_right else 0))

    def set_deemphasis(self, enabled):
        """Enable or disable the digital de-emphasis filter (DEEMPH).

        Args:
            enabled: True to enable.
        """
        self._set_bit(_REG_MISC_CTL, 2, enabled)

    def set_pcm_swap(self, left_source, right_source):
        """Select the source of each PCM channel (``PCM_MIXER_SWAP``).

        Args:
            left_source: ``SRC_NORMAL`` (0), ``SRC_MIX`` (1, (L+R)/2) or ``SRC_SWAPPED`` (3).
            right_source: Same coding.

        Raises:
            ValueError: If a source is not 0–3.
        """
        if not (0 <= left_source <= 3 and 0 <= right_source <= 3):
            raise ValueError('source must be 0-3')
        self._write(_REG_PCM_MIXER_SWAP, (left_source << 6) | (right_source << 4))

    def invert_pcm(self, left, right):
        """Invert the PCM polarity of each channel.

        Args:
            left: Invert channel A (INV_PCMA).
            right: Invert channel B (INV_PCMB).
        """
        self._update(_REG_PLAYBACK_CTL_1, 0x0C, (0x04 if left else 0) | (0x08 if right else 0))

    def set_passthrough(self, enabled, left_input=1, right_input=1):
        """Route the analog line inputs to the headphone outputs.

        Args:
            enabled: Enable analog passthrough on both channels.
            left_input: Input AIN1A … AIN4A for channel A (1–4).
            right_input: Input AIN1B … AIN4B for channel B (1–4).

        Raises:
            ValueError: If an input is not 1–4.
        """
        if not (1 <= left_input <= 4 and 1 <= right_input <= 4):
            raise ValueError('input must be 1-4')
        self._write(_REG_PASSTHRU_A_SEL, 0x80 | (1 << (left_input - 1)))
        self._write(_REG_PASSTHRU_B_SEL, 0x80 | (1 << (right_input - 1)))
        self._update(_REG_MISC_CTL, 0xC0, 0xC0 if enabled else 0)

    def set_passthrough_volume(self, left_db, right_db=None):
        """Set the passthrough volume.

        Args:
            left_db: −60 … +12 dB.
            right_db: Right channel; None uses ``left_db``.
        """
        self._write(_REG_PASSTHRU_A_VOL, _half_db_code(left_db, -60.0, 12.0))
        self._write(_REG_PASSTHRU_B_VOL, _half_db_code(left_db if right_db is None else right_db, -60.0, 12.0))

    def mute_passthrough(self, left, right=None):
        """Mute or unmute the passthrough channels.

        Args:
            left: True to mute channel A.
            right: Channel B; None uses ``left``.
        """
        self._set_bit(_REG_MISC_CTL, 4, left)
        self._set_bit(_REG_MISC_CTL, 5, left if right is None else right)

    def freeze(self, on):
        """Hold (True) or apply (False) register changes (FREEZE).

        Set it, change several registers, clear it to apply them together.

        Args:
            on: True to freeze.
        """
        self._set_bit(_REG_MISC_CTL, 3, on)

    def set_battery_compensation(self, enabled, vp_ref_index=0, monitor=False):
        """Configure battery compensation for the speaker supply VP.

        Args:
            enabled: Enable battery compensation (BATTCMP).
            vp_ref_index: VPREF code 0–15 (1.5 … 5.0 V in 0.5 V steps; 0–7 for VA = 1.8 V, 8–15 for 2.5 V).
            monitor: Enable the VP monitor (VPMONITOR).

        Raises:
            ValueError: If ``vp_ref_index`` is not 0–15.
        """
        if not 0 <= vp_ref_index <= 15:
            raise ValueError('vp_ref_index must be 0-15')
        self._write(_REG_BATTERY_COMP, (0x80 if enabled else 0) | (0x40 if monitor else 0) | vp_ref_index)

    def read_vp_voltage(self, va_volts):
        """Read the VP supply voltage.

        Args:
            va_volts: The analog supply VA in volts (1.8 or 2.5).

        Returns:
            float: ``VP_LEVEL × va_volts / 63.3`` in volts.
        """
        return self._read(_REG_VP_LEVEL) * va_volts / 63.3

    def read_status(self):
        """Read the status register — **reading clears the flags**.

        Returns:
            dict: ``clock_error``, ``dsp_a_overflow``, ``dsp_b_overflow``,
            ``pcm_a_overflow``, ``pcm_b_overflow`` (bool).
        """
        v = self._read(_REG_STATUS)
        return {
            'clock_error': bool(v & 0x40),
            'dsp_a_overflow': bool(v & 0x20),
            'dsp_b_overflow': bool(v & 0x10),
            'pcm_a_overflow': bool(v & 0x08),
            'pcm_b_overflow': bool(v & 0x04),
        }

    def read_speaker_status(self):
        """Read the speaker status register.

        Returns:
            dict: ``speaker_a_short``, ``speaker_b_short``, ``spkr_hp_pin`` (bool).
        """
        v = self._read(_REG_SPEAKER_STATUS)
        return {
            'speaker_a_short': bool(v & 0x20),
            'speaker_b_short': bool(v & 0x10),
            'spkr_hp_pin': bool(v & 0x08),
        }

    def set_charge_pump_frequency(self, n):
        """Set the charge-pump frequency divider (frequency = 64·Fs / (N + 2)).

        Args:
            n: 0–15.

        Raises:
            ValueError: If ``n`` is not 0–15.
        """
        if not 0 <= n <= 15:
            raise ValueError('n must be 0-15')
        self._update(_REG_CHARGE_PUMP_FREQ, 0xF0, n << 4)

    def read_register(self, reg):
        """Read any documented register.

        Args:
            reg: Register address.

        Returns:
            int: The register value (reading ``STATUS`` clears it).

        Raises:
            ValueError: If ``reg`` is reserved.
        """
        if reg not in _READABLE:
            raise ValueError('register is reserved')
        return self._read(reg)

    def write_register(self, reg, value):
        """Write any documented writable register.

        Args:
            reg: Register address.
            value: Byte to write.

        Raises:
            ValueError: If ``reg`` is reserved or read-only.
        """
        if reg not in _READABLE or reg in _READ_ONLY:
            raise ValueError('register is reserved or read-only')
        self._write(reg, value)


def _nearest_index(db):
    best = 0
    best_err = abs(db - _LIMIT_DB[0])
    for i in range(1, 8):
        err = abs(db - _LIMIT_DB[i])
        if err < best_err:
            best = i
            best_err = err
    return best
