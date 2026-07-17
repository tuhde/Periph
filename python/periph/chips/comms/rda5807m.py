import struct
import time

_BAND_BASE_KHZ = (87000, 76000, 76000, 65000)
_SPACE_KHZ = (100, 200, 50, 25)

_STC_TIMEOUT_S = 0.5
_STC_POLL_S = 0.001

# Undocumented, measured on real hardware: after standby wake-up or a soft
# reset, the chip needs this long before it will lock onto a subsequent TUNE
# (FM_READY otherwise never asserts, even after minutes). Same requirement as
# the datasheet's power-up sequencing, just not called out for these two cases.
_RESET_RECOVERY_S = 0.25
# Undocumented, measured on real hardware: FM_READY lags STC by up to ~20 ms
# after any register write.
_READY_SETTLE_S = 0.03


def _freq_to_chan(band, space, east_europe_50m, frequency_mhz):
    base = 50000 if (band == 3 and east_europe_50m) else _BAND_BASE_KHZ[band]
    freq_khz = round(frequency_mhz * 1000)
    chan = round((freq_khz - base) / _SPACE_KHZ[space])
    if chan < 0:
        chan = 0
    if chan > 1023:
        chan = 1023
    return chan


def _chan_to_freq(band, space, east_europe_50m, chan):
    base = 50000 if (band == 3 and east_europe_50m) else _BAND_BASE_KHZ[band]
    return (base + chan * _SPACE_KHZ[space]) / 1000.0


class RDA5807MMinimal:
    """RDA5807M single-chip FM stereo radio tuner — minimal interface.

    Tunes to a station, adjusts volume, mutes, and seeks the next station.
    No configuration required beyond the transport.

    Unlike most chips in this project, the RDA5807M has no register-pointer
    byte: writes always start at the fixed register 0x02 and reads always
    start at the fixed register 0x0A. This driver keeps an in-memory shadow
    of registers 0x02-0x07 (6 big-endian 16-bit words) and rewrites all of
    them on every change, since the chip cannot be told to start a write
    anywhere else.

    Args:
        transport: Configured I²C transport bound to address 0x10.
        frequency_mhz: Initial frequency in MHz (default 100.0).
        volume: Initial volume, 0 (mute) to 15 (max) (default 8).
    """

    BAND_US_EUROPE = 0
    BAND_JAPAN = 1
    BAND_WORLD = 2
    BAND_EAST_EUROPE = 3

    SPACE_100K = 0
    SPACE_200K = 1
    SPACE_50K = 2
    SPACE_25K = 3

    _DHIZ = 0x8000
    _DMUTE = 0x4000
    _MONO = 0x2000
    _BASS = 0x1000
    _SEEKUP = 0x0200
    _SEEK = 0x0100
    _SKMODE = 0x0080
    _RDS_EN = 0x0008
    _NEW_METHOD = 0x0004
    _SOFT_RESET = 0x0002
    _ENABLE = 0x0001

    _TUNE = 0x0010

    _DE = 0x0800
    _SOFTMUTE_EN = 0x0200
    _AFCD = 0x0100

    _INT_MODE = 0x8000

    _BAND_65M_50M = 0x0200

    _RDSR = 0x8000
    _STC = 0x4000
    _SF = 0x2000
    _ST = 0x0400

    _FM_TRUE = 0x0100
    _FM_READY = 0x0080

    def __init__(self, transport, frequency_mhz=100.0, volume=8):
        self._transport = transport
        self._band = self.BAND_WORLD
        self._space = self.SPACE_100K
        self._east_europe_50m = False

        ctrl = self._DHIZ | self._DMUTE | self._SKMODE | self._NEW_METHOD | self._ENABLE
        chan = _freq_to_chan(self._band, self._space, self._east_europe_50m, frequency_mhz)
        chan_reg = (chan << 6) | self._TUNE | (self._band << 2) | self._space
        r4 = self._SOFTMUTE_EN | self._DE
        r5 = self._INT_MODE | (8 << 8) | (volume & 0x0F)
        r6 = 0x0000
        r7 = (16 << 10) | self._BAND_65M_50M | 0x0002

        self._regs = [ctrl, chan_reg, r4, r5, r6, r7]
        self._current_freq = frequency_mhz
        self._write_regs()
        self._wait_stc()
        self._regs[1] &= ~self._TUNE

    def _write_regs(self):
        self._transport.write(struct.pack('>6H', *self._regs))

    def _read_status(self, n=2):
        return struct.unpack('>{}H'.format(n // 2), self._transport.read(n))

    def _wait_stc(self):
        elapsed = 0.0
        while elapsed < _STC_TIMEOUT_S:
            (status_a,) = self._read_status(2)
            if status_a & self._STC:
                return status_a
            time.sleep(_STC_POLL_S)
            elapsed += _STC_POLL_S
        return 0

    def set_frequency(self, frequency_mhz):
        """Tune to a frequency, blocking until the tune completes.

        Args:
            frequency_mhz: Target frequency in MHz.
        """
        chan = _freq_to_chan(self._band, self._space, self._east_europe_50m, frequency_mhz)
        self._regs[1] = (chan << 6) | self._TUNE | (self._band << 2) | self._space
        self._current_freq = frequency_mhz
        self._write_regs()
        self._wait_stc()
        self._regs[1] &= ~self._TUNE

    def frequency(self):
        """Read the currently tuned frequency.

        Returns:
            float: Frequency in MHz, derived from READCHAN.
        """
        (status_a,) = self._read_status(2)
        readchan = status_a & 0x03FF
        return _chan_to_freq(self._band, self._space, self._east_europe_50m, readchan)

    def set_volume(self, level):
        """Set the output volume.

        Args:
            level: Volume 0 (mute) to 15 (max), logarithmic scale.
        """
        self._regs[3] = (self._regs[3] & ~0x000F) | (level & 0x0F)
        self._write_regs()

    def mute(self, enable):
        """Mute or unmute the audio output.

        Args:
            enable: True to mute, False for normal operation.
        """
        if enable:
            self._regs[0] &= ~self._DMUTE
        else:
            self._regs[0] |= self._DMUTE
        self._write_regs()

    def seek(self, up=True):
        """Seek to the next station, blocking until the seek completes.

        Args:
            up: True to seek upward in frequency, False to seek downward.

        Returns:
            float or None: The new frequency in MHz, or None if the seek
            failed to find a station (SF flag set).
        """
        if up:
            self._regs[0] |= self._SEEKUP
        else:
            self._regs[0] &= ~self._SEEKUP
        self._regs[0] |= self._SEEK
        self._write_regs()
        status_a = self._wait_stc()
        self._regs[0] &= ~self._SEEK
        self._write_regs()

        if status_a & self._SF:
            return None
        readchan = status_a & 0x03FF
        freq = _chan_to_freq(self._band, self._space, self._east_europe_50m, readchan)
        self._current_freq = freq
        return freq


class RDA5807MFull(RDA5807MMinimal):
    """RDA5807M full interface — extends RDA5807MMinimal with band/spacing
    configuration, RDS, status, and power management.

    Args:
        transport: Configured I²C transport bound to address 0x10.
        frequency_mhz: Initial frequency in MHz (default 100.0).
        volume: Initial volume, 0 (mute) to 15 (max) (default 8).
    """

    def configure(self, band=None, space=None, de_emphasis=None, seek_threshold=None,
                  seek_mode=None, clk_mode=None, afc_disable=None, east_europe_50m=None):
        """Reconfigure tuner-level settings.

        Only parameters that are not None are changed. Changing band or space
        re-tunes to the current frequency, since CHAN's meaning depends on both.

        Args:
            band: One of BAND_US_EUROPE, BAND_JAPAN, BAND_WORLD, BAND_EAST_EUROPE.
            space: One of SPACE_100K, SPACE_200K, SPACE_50K, SPACE_25K.
            de_emphasis: True for 50 µs (Europe/world), False for 75 µs (US).
            seek_threshold: Seek SNR threshold, 0-15 (default 8, ~32 dB).
            seek_mode: True to stop seeking at the band limit, False to wrap.
            clk_mode: Reference clock select, 0-7 (see CLK_MODE in the spec).
            afc_disable: True to disable AFC.
            east_europe_50m: When band is BAND_EAST_EUROPE, True selects the
                65-76 MHz sub-band (default), False selects 50-... MHz.
        """
        retune = False
        current_freq = self.frequency()

        if band is not None and band != self._band:
            self._band = band
            retune = True
        if space is not None and space != self._space:
            self._space = space
            retune = True
        if east_europe_50m is not None and east_europe_50m != self._east_europe_50m:
            self._east_europe_50m = east_europe_50m
            retune = True

        self._regs[1] = (self._regs[1] & ~0x000F) | (self._band << 2) | self._space

        if de_emphasis is not None:
            if de_emphasis:
                self._regs[2] |= self._DE
            else:
                self._regs[2] &= ~self._DE
        if afc_disable is not None:
            if afc_disable:
                self._regs[2] |= self._AFCD
            else:
                self._regs[2] &= ~self._AFCD
        if seek_threshold is not None:
            self._regs[3] = (self._regs[3] & ~0x0F00) | ((seek_threshold & 0x0F) << 8)
        if seek_mode is not None:
            if seek_mode:
                self._regs[0] |= self._SKMODE
            else:
                self._regs[0] &= ~self._SKMODE
        if clk_mode is not None:
            self._regs[0] = (self._regs[0] & ~0x0070) | ((clk_mode & 0x07) << 4)
        if east_europe_50m is not None:
            if east_europe_50m:
                self._regs[5] &= ~self._BAND_65M_50M
            else:
                self._regs[5] |= self._BAND_65M_50M

        if retune:
            self.set_frequency(current_freq)
        else:
            self._write_regs()

    def set_bass_boost(self, enable):
        """Enable or disable bass boost.

        Args:
            enable: True to enable bass boost.
        """
        if enable:
            self._regs[0] |= self._BASS
        else:
            self._regs[0] &= ~self._BASS
        self._write_regs()

    def set_mono(self, enable):
        """Force mono or allow stereo.

        Args:
            enable: True to force mono, False to allow stereo.
        """
        if enable:
            self._regs[0] |= self._MONO
        else:
            self._regs[0] &= ~self._MONO
        self._write_regs()

    def set_softmute(self, enable):
        """Enable or disable soft mute (smooth volume reduction on weak signal).

        Args:
            enable: True to enable soft mute (chip default).
        """
        if enable:
            self._regs[2] |= self._SOFTMUTE_EN
        else:
            self._regs[2] &= ~self._SOFTMUTE_EN
        self._write_regs()

    def enable_rds(self, enable):
        """Enable or disable the RDS/RBDS decoder.

        Args:
            enable: True to enable RDS/RBDS.
        """
        if enable:
            self._regs[0] |= self._RDS_EN
        else:
            self._regs[0] &= ~self._RDS_EN
        self._write_regs()

    def rds_ready(self):
        """Check whether a new RDS/RBDS group is available.

        Returns:
            bool: True if RDSR is set.
        """
        (status_a,) = self._read_status(2)
        return bool(status_a & self._RDSR)

    def read_rds_group(self):
        """Read the four raw RDS/RBDS blocks, if a new group is ready.

        Does not decode group content (PI, PS, RadioText, ...) — the caller
        interprets the raw blocks per the RDS/RBDS standard.

        Returns:
            tuple(int, int, int, int) or None: Raw blocks (A, B, C, D), or
            None if no new group is ready.
        """
        status_a, status_b, block_a, block_b, block_c, block_d = self._read_status(12)
        if not (status_a & self._RDSR):
            return None
        return (block_a, block_b, block_c, block_d)

    def is_stereo(self):
        """Check the stereo indicator.

        Returns:
            bool: True if the current station is being received in stereo.
        """
        (status_a,) = self._read_status(2)
        return bool(status_a & self._ST)

    def is_station(self):
        """Check whether the current channel is a real station.

        Returns:
            bool: True if FM_TRUE is set.
        """
        _, status_b = self._read_status(4)
        return bool(status_b & self._FM_TRUE)

    def is_ready(self):
        """Check whether the tuner is ready.

        Returns:
            bool: True if FM_READY is set.
        """
        _, status_b = self._read_status(4)
        return bool(status_b & self._FM_READY)

    def signal_strength(self):
        """Read the received signal strength indicator.

        Returns:
            int: Raw RSSI, 0 (weakest) to 127 (strongest), logarithmic.
            No absolute dBµV mapping is published by the datasheet.
        """
        _, status_b = self._read_status(4)
        return (status_b >> 9) & 0x7F

    def standby(self, enable):
        """Power the chip down or up.

        Powering back up clears the tuner's PLL lock, so waking from standby
        blocks briefly for the chip to recover, then re-tunes to the last
        known frequency (mirroring the datasheet's power-up sequencing, which
        the chip otherwise never recovers from on its own).

        Args:
            enable: True to power down, False to power up.
        """
        if enable:
            self._regs[0] &= ~self._ENABLE
        else:
            self._regs[0] |= self._ENABLE
        self._write_regs()
        if not enable:
            time.sleep(_RESET_RECOVERY_S)
            self.set_frequency(self._current_freq)
            time.sleep(_READY_SETTLE_S)

    def soft_reset(self):
        """Pulse the soft-reset bit, then re-apply the current configuration.

        A soft reset restores the chip's power-on register defaults and
        clears the tuner's PLL lock, so this blocks briefly for the chip to
        recover, then re-tunes to the last known frequency (the chip never
        reacquires lock on its own otherwise).
        """
        self._regs[0] |= self._SOFT_RESET
        self._write_regs()
        self._regs[0] &= ~self._SOFT_RESET
        self._write_regs()
        time.sleep(_RESET_RECOVERY_S)
        self.set_frequency(self._current_freq)
        time.sleep(_READY_SETTLE_S)
