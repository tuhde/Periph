//! RDA5807M — single-chip FM stereo radio tuner (RDA Microelectronics).
//!
//! Communicates over I²C at a fixed 7-bit address (`0x10`). Unlike most
//! chips in this project, the RDA5807M has no register-pointer byte:
//! writes always start at the fixed register `0x02` and reads always start
//! at the fixed register `0x0A`. This driver keeps an in-memory shadow of
//! registers `0x02`-`0x07` (6 big-endian 16-bit words) and rewrites all of
//! them on every change, since the chip cannot be told to start a write
//! anywhere else.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const BAND_BASE_KHZ: [u32; 4] = [87000, 76000, 76000, 65000];
const SPACE_KHZ: [u32; 4] = [100, 200, 50, 25];

const STC_TIMEOUT_ITERS: u32 = 500;

// Undocumented, measured on real hardware: after standby wake-up or a soft
// reset, the chip needs this long before it will lock onto a subsequent TUNE
// (FM_READY otherwise never asserts, even after minutes). Same requirement as
// the datasheet's power-up sequencing, just not called out for these two cases.
const RESET_RECOVERY_MS: u32 = 250;
// Undocumented, measured on real hardware: FM_READY lags STC by up to ~20 ms
// after any register write.
const READY_SETTLE_MS: u32 = 30;

/// Band select: `00` = 87-108 MHz (US/Europe).
pub const BAND_US_EUROPE: u8 = 0;
/// Band select: `01` = 76-91 MHz (Japan).
pub const BAND_JAPAN: u8 = 1;
/// Band select: `10` = 76-108 MHz (world wide).
pub const BAND_WORLD: u8 = 2;
/// Band select: `11` = 65-76 MHz (East Europe) or a 50 MHz-based sub-band.
pub const BAND_EAST_EUROPE: u8 = 3;

/// Channel spacing: 100 kHz.
pub const SPACE_100K: u8 = 0;
/// Channel spacing: 200 kHz.
pub const SPACE_200K: u8 = 1;
/// Channel spacing: 50 kHz.
pub const SPACE_50K: u8 = 2;
/// Channel spacing: 25 kHz.
pub const SPACE_25K: u8 = 3;

const DHIZ: u16 = 0x8000;
const DMUTE: u16 = 0x4000;
const MONO: u16 = 0x2000;
const BASS: u16 = 0x1000;
const SEEKUP: u16 = 0x0200;
const SEEK: u16 = 0x0100;
const SKMODE: u16 = 0x0080;
const RDS_EN: u16 = 0x0008;
const NEW_METHOD: u16 = 0x0004;
const SOFT_RESET: u16 = 0x0002;
const ENABLE: u16 = 0x0001;

const TUNE: u16 = 0x0010;

const DE: u16 = 0x0800;
const SOFTMUTE_EN: u16 = 0x0200;
const AFCD: u16 = 0x0100;

const INT_MODE: u16 = 0x8000;

const BAND_65M_50M: u16 = 0x0200;

const RDSR: u16 = 0x8000;
const STC: u16 = 0x4000;
const SF: u16 = 0x2000;
const ST: u16 = 0x0400;

const FM_TRUE: u16 = 0x0100;
const FM_READY: u16 = 0x0080;

fn freq_to_chan(band: u8, space: u8, east_europe_50m: bool, frequency_mhz: f32) -> u16 {
    let base = if band == 3 && east_europe_50m { 50000 } else { BAND_BASE_KHZ[band as usize] };
    let freq_khz = (frequency_mhz * 1000.0 + 0.5) as i32;
    let mut chan = (freq_khz - base as i32) / SPACE_KHZ[space as usize] as i32;
    if chan < 0 { chan = 0; }
    if chan > 1023 { chan = 1023; }
    chan as u16
}

fn chan_to_freq(band: u8, space: u8, east_europe_50m: bool, chan: u16) -> f32 {
    let base = if band == 3 && east_europe_50m { 50000 } else { BAND_BASE_KHZ[band as usize] };
    (base + chan as u32 * SPACE_KHZ[space as usize]) as f32 / 1000.0
}

fn write_regs<I2C: I2c>(i2c: &mut I2C, addr: u8, regs: &[u16; 6]) -> Result<(), I2C::Error> {
    let mut buf = [0u8; 12];
    for i in 0..6 {
        buf[i * 2] = (regs[i] >> 8) as u8;
        buf[i * 2 + 1] = (regs[i] & 0xFF) as u8;
    }
    i2c.write(addr, &buf)
}

fn read_status<I2C: I2c>(i2c: &mut I2C, addr: u8, words: &mut [u16]) -> Result<(), I2C::Error> {
    let mut buf = [0u8; 12];
    let n = words.len() * 2;
    i2c.read(addr, &mut buf[..n])?;
    for i in 0..words.len() {
        words[i] = ((buf[i * 2] as u16) << 8) | buf[i * 2 + 1] as u16;
    }
    Ok(())
}

fn wait_stc<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<u16, I2C::Error> {
    let mut status_a = [0u16; 1];
    for _ in 0..STC_TIMEOUT_ITERS {
        read_status(i2c, addr, &mut status_a)?;
        if status_a[0] & STC != 0 {
            return Ok(status_a[0]);
        }
    }
    Ok(0)
}

/// RDA5807M minimal driver — tune, volume, mute, and seek.
///
/// No configuration required beyond the transport. Sensible defaults:
/// world-wide band (76-108 MHz), 100 kHz spacing, DHIZ/DMUTE/NEW_METHOD
/// enabled, SKMODE=1 (stop seeking at the band limit).
pub struct Rda5807mMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    regs: [u16; 6],
    band: u8,
    space: u8,
    east_europe_50m: bool,
    current_freq: f32,
}

impl<I2C: I2c> Rda5807mMinimal<I2C> {
    /// Create a new `Rda5807mMinimal` and tune to the initial frequency.
    ///
    /// # Arguments
    /// * `i2c`            — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`           — 7-bit device address (fixed at `0x10` on this chip).
    /// * `frequency_mhz`  — Initial frequency in MHz.
    /// * `volume`         — Initial volume, 0 (mute) to 15 (max).
    pub fn new(mut i2c: I2C, addr: u8, frequency_mhz: f32, volume: u8) -> Result<Self, I2C::Error> {
        let band = BAND_WORLD;
        let space = SPACE_100K;
        let east_europe_50m = false;

        let ctrl = DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE;
        let chan = freq_to_chan(band, space, east_europe_50m, frequency_mhz);
        let chan_reg = (chan << 6) | TUNE | ((band as u16) << 2) | space as u16;
        let r4 = SOFTMUTE_EN | DE;
        let r5 = INT_MODE | (8 << 8) | (volume as u16 & 0x0F);
        let r6 = 0x0000;
        let r7 = (16 << 10) | BAND_65M_50M | 0x0002;

        let mut regs = [ctrl, chan_reg, r4, r5, r6, r7];
        write_regs(&mut i2c, addr, &regs)?;
        wait_stc(&mut i2c, addr)?;
        regs[1] &= !TUNE;

        Ok(Self { i2c, addr, regs, band, space, east_europe_50m, current_freq: frequency_mhz })
    }

    /// Tune to a frequency, blocking until the tune completes.
    pub fn set_frequency(&mut self, frequency_mhz: f32) -> Result<(), I2C::Error> {
        let chan = freq_to_chan(self.band, self.space, self.east_europe_50m, frequency_mhz);
        self.regs[1] = (chan << 6) | TUNE | ((self.band as u16) << 2) | self.space as u16;
        self.current_freq = frequency_mhz;
        write_regs(&mut self.i2c, self.addr, &self.regs)?;
        wait_stc(&mut self.i2c, self.addr)?;
        self.regs[1] &= !TUNE;
        Ok(())
    }

    /// Read the currently tuned frequency in MHz, derived from READCHAN.
    pub fn frequency(&mut self) -> Result<f32, I2C::Error> {
        let mut status_a = [0u16; 1];
        read_status(&mut self.i2c, self.addr, &mut status_a)?;
        let readchan = status_a[0] & 0x03FF;
        Ok(chan_to_freq(self.band, self.space, self.east_europe_50m, readchan))
    }

    /// Set the output volume, 0 (mute) to 15 (max), logarithmic scale.
    pub fn set_volume(&mut self, level: u8) -> Result<(), I2C::Error> {
        self.regs[3] = (self.regs[3] & !0x000F) | (level as u16 & 0x0F);
        write_regs(&mut self.i2c, self.addr, &self.regs)
    }

    /// Mute (`true`) or unmute (`false`) the audio output.
    pub fn mute(&mut self, enable: bool) -> Result<(), I2C::Error> {
        if enable { self.regs[0] &= !DMUTE; } else { self.regs[0] |= DMUTE; }
        write_regs(&mut self.i2c, self.addr, &self.regs)
    }

    /// Seek to the next station, blocking until the seek completes.
    ///
    /// Returns `Some(frequency_mhz)` if a station was found, or `None` if
    /// the SF (seek fail) flag was set.
    pub fn seek(&mut self, up: bool) -> Result<Option<f32>, I2C::Error> {
        if up { self.regs[0] |= SEEKUP; } else { self.regs[0] &= !SEEKUP; }
        self.regs[0] |= SEEK;
        write_regs(&mut self.i2c, self.addr, &self.regs)?;
        let status_a = wait_stc(&mut self.i2c, self.addr)?;
        self.regs[0] &= !SEEK;
        write_regs(&mut self.i2c, self.addr, &self.regs)?;

        if status_a & SF != 0 {
            return Ok(None);
        }
        let readchan = status_a & 0x03FF;
        let freq = chan_to_freq(self.band, self.space, self.east_europe_50m, readchan);
        self.current_freq = freq;
        Ok(Some(freq))
    }
}

/// RDA5807M full driver — extends [`Rda5807mMinimal`] with band/spacing
/// configuration, RDS, status, and power management.
pub struct Rda5807mFull<I2C> {
    inner: Rda5807mMinimal<I2C>,
}

impl<I2C: I2c> Rda5807mFull<I2C> {
    /// Create a new `Rda5807mFull`. Same arguments as [`Rda5807mMinimal::new`].
    pub fn new(i2c: I2C, addr: u8, frequency_mhz: f32, volume: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Rda5807mMinimal::new(i2c, addr, frequency_mhz, volume)? })
    }

    /// Tune to a frequency. Delegates to the inner [`Rda5807mMinimal`].
    pub fn set_frequency(&mut self, frequency_mhz: f32) -> Result<(), I2C::Error> {
        self.inner.set_frequency(frequency_mhz)
    }

    /// Read the currently tuned frequency. Delegates to the inner [`Rda5807mMinimal`].
    pub fn frequency(&mut self) -> Result<f32, I2C::Error> {
        self.inner.frequency()
    }

    /// Set the output volume. Delegates to the inner [`Rda5807mMinimal`].
    pub fn set_volume(&mut self, level: u8) -> Result<(), I2C::Error> {
        self.inner.set_volume(level)
    }

    /// Mute or unmute. Delegates to the inner [`Rda5807mMinimal`].
    pub fn mute(&mut self, enable: bool) -> Result<(), I2C::Error> {
        self.inner.mute(enable)
    }

    /// Seek to the next station. Delegates to the inner [`Rda5807mMinimal`].
    pub fn seek(&mut self, up: bool) -> Result<Option<f32>, I2C::Error> {
        self.inner.seek(up)
    }

    /// Reconfigure tuner-level settings. `None` leaves a field unchanged.
    /// Changing `band` or `space` re-tunes to the current frequency.
    #[allow(clippy::too_many_arguments)]
    pub fn configure(
        &mut self,
        band: Option<u8>,
        space: Option<u8>,
        de_emphasis: Option<bool>,
        seek_threshold: Option<u8>,
        seek_mode: Option<bool>,
        clk_mode: Option<u8>,
        afc_disable: Option<bool>,
        east_europe_50m: Option<bool>,
    ) -> Result<(), I2C::Error> {
        let mut retune = false;
        let current_freq = self.inner.frequency()?;

        if let Some(b) = band {
            if b != self.inner.band { self.inner.band = b; retune = true; }
        }
        if let Some(s) = space {
            if s != self.inner.space { self.inner.space = s; retune = true; }
        }
        if let Some(e) = east_europe_50m {
            if e != self.inner.east_europe_50m { self.inner.east_europe_50m = e; retune = true; }
        }

        self.inner.regs[1] = (self.inner.regs[1] & !0x000F)
            | ((self.inner.band as u16) << 2) | self.inner.space as u16;

        if let Some(de) = de_emphasis {
            if de { self.inner.regs[2] |= DE; } else { self.inner.regs[2] &= !DE; }
        }
        if let Some(afc) = afc_disable {
            if afc { self.inner.regs[2] |= AFCD; } else { self.inner.regs[2] &= !AFCD; }
        }
        if let Some(th) = seek_threshold {
            self.inner.regs[3] = (self.inner.regs[3] & !0x0F00) | ((th as u16 & 0x0F) << 8);
        }
        if let Some(sm) = seek_mode {
            if sm { self.inner.regs[0] |= SKMODE; } else { self.inner.regs[0] &= !SKMODE; }
        }
        if let Some(ck) = clk_mode {
            self.inner.regs[0] = (self.inner.regs[0] & !0x0070) | ((ck as u16 & 0x07) << 4);
        }
        if let Some(e) = east_europe_50m {
            if e { self.inner.regs[5] &= !BAND_65M_50M; } else { self.inner.regs[5] |= BAND_65M_50M; }
        }

        if retune {
            self.inner.set_frequency(current_freq)
        } else {
            write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)
        }
    }

    /// Enable or disable bass boost.
    pub fn set_bass_boost(&mut self, enable: bool) -> Result<(), I2C::Error> {
        if enable { self.inner.regs[0] |= BASS; } else { self.inner.regs[0] &= !BASS; }
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)
    }

    /// Force mono or allow stereo.
    pub fn set_mono(&mut self, enable: bool) -> Result<(), I2C::Error> {
        if enable { self.inner.regs[0] |= MONO; } else { self.inner.regs[0] &= !MONO; }
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)
    }

    /// Enable or disable soft mute (chip default: enabled).
    pub fn set_softmute(&mut self, enable: bool) -> Result<(), I2C::Error> {
        if enable { self.inner.regs[2] |= SOFTMUTE_EN; } else { self.inner.regs[2] &= !SOFTMUTE_EN; }
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)
    }

    /// Enable or disable the RDS/RBDS decoder.
    pub fn enable_rds(&mut self, enable: bool) -> Result<(), I2C::Error> {
        if enable { self.inner.regs[0] |= RDS_EN; } else { self.inner.regs[0] &= !RDS_EN; }
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)
    }

    /// Check whether a new RDS/RBDS group is available (RDSR flag).
    pub fn rds_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut status_a = [0u16; 1];
        read_status(&mut self.inner.i2c, self.inner.addr, &mut status_a)?;
        Ok(status_a[0] & RDSR != 0)
    }

    /// Read the four raw RDS/RBDS blocks, if a new group is ready.
    ///
    /// Does not decode group content (PI, PS, RadioText, ...) — the caller
    /// interprets the raw blocks per the RDS/RBDS standard.
    pub fn read_rds_group(&mut self) -> Result<Option<(u16, u16, u16, u16)>, I2C::Error> {
        let mut words = [0u16; 6];
        read_status(&mut self.inner.i2c, self.inner.addr, &mut words)?;
        if words[0] & RDSR == 0 {
            return Ok(None);
        }
        Ok(Some((words[2], words[3], words[4], words[5])))
    }

    /// Check the stereo indicator.
    pub fn is_stereo(&mut self) -> Result<bool, I2C::Error> {
        let mut status_a = [0u16; 1];
        read_status(&mut self.inner.i2c, self.inner.addr, &mut status_a)?;
        Ok(status_a[0] & ST != 0)
    }

    /// Check whether the current channel is a real station (FM_TRUE flag).
    pub fn is_station(&mut self) -> Result<bool, I2C::Error> {
        let mut words = [0u16; 2];
        read_status(&mut self.inner.i2c, self.inner.addr, &mut words)?;
        Ok(words[1] & FM_TRUE != 0)
    }

    /// Check whether the tuner is ready (FM_READY flag).
    pub fn is_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut words = [0u16; 2];
        read_status(&mut self.inner.i2c, self.inner.addr, &mut words)?;
        Ok(words[1] & FM_READY != 0)
    }

    /// Read the received signal strength indicator: 0 (weakest) to 127
    /// (strongest), logarithmic. No absolute dBµV mapping is published.
    pub fn signal_strength(&mut self) -> Result<u8, I2C::Error> {
        let mut words = [0u16; 2];
        read_status(&mut self.inner.i2c, self.inner.addr, &mut words)?;
        Ok(((words[1] >> 9) & 0x7F) as u8)
    }

    /// Power the chip down (`true`) or up (`false`). Powering back up clears
    /// the tuner's PLL lock, so waking from standby blocks briefly for the
    /// chip to recover, then re-tunes to the last known frequency (mirroring
    /// the datasheet's power-up sequencing, which the chip otherwise never
    /// recovers from on its own).
    pub fn standby<D: DelayNs>(&mut self, enable: bool, delay: &mut D) -> Result<(), I2C::Error> {
        if enable { self.inner.regs[0] &= !ENABLE; } else { self.inner.regs[0] |= ENABLE; }
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)?;
        if !enable {
            delay.delay_ms(RESET_RECOVERY_MS);
            let freq = self.inner.current_freq;
            self.inner.set_frequency(freq)?;
            delay.delay_ms(READY_SETTLE_MS);
        }
        Ok(())
    }

    /// Pulse the soft-reset bit, then re-apply the current configuration. A
    /// soft reset restores the chip's power-on register defaults and clears
    /// the tuner's PLL lock, so this blocks briefly for the chip to recover,
    /// then re-tunes to the last known frequency (the chip never reacquires
    /// lock on its own otherwise).
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.regs[0] |= SOFT_RESET;
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)?;
        self.inner.regs[0] &= !SOFT_RESET;
        write_regs(&mut self.inner.i2c, self.inner.addr, &self.inner.regs)?;
        delay.delay_ms(RESET_RECOVERY_MS);
        let freq = self.inner.current_freq;
        self.inner.set_frequency(freq)?;
        delay.delay_ms(READY_SETTLE_MS);
        Ok(())
    }
}
