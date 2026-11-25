//! ES8311 — low-power mono audio codec, I²C control port (Everest Semiconductor).
//!
//! Mono ADC (analog differential or PDM microphone, 0–30 dB PGA, ALC, equalizer)
//! and mono DAC (digital volume, DRC, equalizer) with one I²S / left-justified /
//! DSP digital audio port. This driver covers the I²C control port only: the audio
//! stream itself runs over a separate I²S port that this library does not provide.
//!
//! Address `0x18` (CE low) or `0x19` (CE high); 8-bit registers with a 1-byte address.
//! The user guide leaves the start-up sequence and registers `0x04`, `0x0B`–`0x0C` and
//! `0x10`–`0x13` undocumented; the vendor reference-driver values are used — verify
//! on hardware.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// Default 7-bit I²C address (CE low).
pub const ES8311_I2C_ADDRESS: u8 = 0x18;

const REG_RESET: u8 = 0x00;
const REG_CLK1: u8 = 0x01;
const REG_CLK2: u8 = 0x02;
const REG_CLK3: u8 = 0x03;
const REG_CLK4: u8 = 0x04;
const REG_CLK5: u8 = 0x05;
const REG_CLK6: u8 = 0x06;
const REG_CLK7: u8 = 0x07;
const REG_CLK8: u8 = 0x08;
const REG_SDP_IN: u8 = 0x09;
const REG_SDP_OUT: u8 = 0x0A;
const REG_SYS_PWR: u8 = 0x0D;
const REG_SYS_ADC: u8 = 0x0E;
const REG_SYS_LP: u8 = 0x0F;
const REG_SYS_MIC: u8 = 0x14;
const REG_ADC_RAMP: u8 = 0x15;
const REG_ADC_SCALE: u8 = 0x16;
const REG_ADC_VOLUME: u8 = 0x17;
const REG_ADC_ALC1: u8 = 0x18;
const REG_ADC_ALC2: u8 = 0x19;
const REG_ADC_AUTOMUTE: u8 = 0x1A;
const REG_ADC_HPF1: u8 = 0x1B;
const REG_ADC_HPF2_EQ: u8 = 0x1C;
const REG_ADCEQ: u8 = 0x1D;
const REG_DAC_MUTE: u8 = 0x31;
const REG_DAC_VOLUME: u8 = 0x32;
const REG_DAC_DRC1: u8 = 0x34;
const REG_DAC_DRC2: u8 = 0x35;
const REG_DAC_RAMP_EQ: u8 = 0x37;
const REG_DACEQ: u8 = 0x38;
const REG_GPIO: u8 = 0x44;

/// Guide clock table: (MCLK Hz, fs Hz, 0x02, 0x03, 0x04, 0x05).
const CLOCK_PRESETS: [(u32, u32, u8, u8, u8, u8); 14] = [
    (24_576_000, 32_000, 0x40, 0x10, 0x10, 0x00),
    (24_576_000, 48_000, 0x20, 0x10, 0x10, 0x00),
    (12_288_000, 32_000, 0x48, 0x10, 0x10, 0x00),
    (12_288_000, 48_000, 0x00, 0x10, 0x10, 0x00),
    (18_432_000, 32_000, 0x20, 0x12, 0x12, 0x00),
    (18_432_000, 48_000, 0x48, 0x10, 0x10, 0x00),
    (11_289_600, 44_100, 0x00, 0x10, 0x10, 0x00),
    (12_000_000, 32_000, 0x00, 0x17, 0x17, 0x00),
    (12_000_000, 44_100, 0x00, 0x11, 0x11, 0x00),
    (12_000_000, 48_000, 0x98, 0x19, 0x19, 0x00),
    (26_000_000, 32_000, 0x40, 0x10, 0x10, 0x00),
    (3_000_000, 32_000, 0x18, 0x52, 0x17, 0x41),
    (25_000_000, 32_000, 0x48, 0x55, 0x10, 0x21),
    (25_000_000, 48_000, 0x28, 0x55, 0x10, 0x21),
];
const PREFERRED_RATIOS: [u64; 6] = [256, 384, 512, 768, 1024, 1536];
/// DIV_BCLK codes 20..31.
const BCLK_DIVIDERS: [u16; 12] = [22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72];

/// ALC / DRC target levels in dB, indexed by the 4-bit register code.
pub const ALC_LEVELS_DB: [f32; 16] = [
    -30.1, -24.1, -20.6, -18.1, -16.1, -14.5, -13.2, -12.0,
    -11.0, -10.1, -9.3, -8.5, -7.8, -7.2, -6.6, -6.0,
];
/// Noise-gate thresholds in dB, indexed by the 4-bit register code.
pub const NOISE_GATE_DB: [f32; 16] = [
    -96.0, -90.0, -84.0, -78.0, -72.0, -66.0, -60.0, -54.0,
    -51.0, -48.0, -45.0, -42.0, -39.0, -36.0, -33.0, -30.0,
];

/// Errors from the ES8311 drivers.
#[derive(Debug, PartialEq, Eq)]
pub enum Es8311Error<E> {
    /// I²C bus error.
    Bus(E),
    /// A parameter is out of range or no valid clock divider set exists.
    InvalidArgument,
}

impl<E> From<E> for Es8311Error<E> {
    fn from(e: E) -> Self {
        Es8311Error::Bus(e)
    }
}

/// Digital audio port format.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    /// I²S.
    I2s,
    /// Left-justified.
    LeftJustified,
    /// DSP / PCM mode A.
    DspA,
    /// DSP / PCM mode B.
    DspB,
}

/// Microphone input selection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Input {
    /// Analog differential microphone `MIC1P` / `MIC1N`.
    Analog,
    /// PDM digital microphone on `MIC1P`.
    Digital,
    /// No input (PGA powered down).
    None,
}

/// ADCDAT output content.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdcdatMode {
    /// ADC on both slots.
    AdcAdc,
    /// ADC left, zero right.
    AdcZero,
    /// Zero left, ADC right.
    ZeroAdc,
    /// DAC left, ADC right (digital feedback for echo cancellation).
    DacAdc,
    /// ADC left, DAC right.
    AdcDac,
    /// DAC on both slots.
    DacDac,
}

/// Digital audio port configuration for [`Es8311Full::configure_serial`].
#[derive(Clone, Copy, Debug)]
pub struct SerialConfig {
    /// Port format.
    pub format: Format,
    /// Word length: 16, 18, 20, 24 or 32 bit.
    pub word_length: u8,
    /// Master mode (the chip drives BCLK / LRCK).
    pub master: bool,
    /// Invert BCLK.
    pub bclk_invert: bool,
    /// Invert the LRCK polarity.
    pub lrck_invert: bool,
    /// Send the right channel to the DAC.
    pub dac_right: bool,
    /// Master BCLK divider (`None` = computed).
    pub bclk_div: Option<u16>,
    /// Master LRCK divider (`None` = computed).
    pub lrck_div: Option<u16>,
    /// Keep BCLK running when idle (master mode).
    pub continuous_bclk: bool,
}

impl Default for SerialConfig {
    fn default() -> Self {
        Self {
            format: Format::I2s, word_length: 16, master: false, bclk_invert: false,
            lrck_invert: false, dac_right: false, bclk_div: None, lrck_div: None,
            continuous_bclk: true,
        }
    }
}

/// ADC digital path configuration for [`Es8311Full::configure_adc`].
#[derive(Clone, Copy, Debug)]
pub struct AdcConfig {
    /// Digital gain scale-up 0 … 42 dB in 6 dB steps.
    pub scale_db: u8,
    /// Oversampling ratio in × fs (single speed 60–252, double speed 32–126).
    pub osr: u16,
    /// Select the double-speed ADC.
    pub double_speed: bool,
    /// Dynamic high-pass filter (false freezes the DC offset).
    pub hpf: bool,
    /// Stage-1 high-pass coefficient 0–31.
    pub hpf_coeff1: u8,
    /// Stage-2 high-pass coefficient 0–31.
    pub hpf_coeff2: u8,
}

impl Default for AdcConfig {
    fn default() -> Self {
        Self { scale_db: 24, osr: 64, double_speed: false, hpf: true, hpf_coeff1: 0x0C, hpf_coeff2: 0x0C }
    }
}

/// ALC configuration for [`Es8311Full::configure_alc`].
#[derive(Clone, Copy, Debug)]
pub struct AlcConfig {
    /// Enable the ALC.
    pub enabled: bool,
    /// Upper target level in dB (nearest of 16 levels).
    pub max_level_db: f32,
    /// Lower target level in dB.
    pub min_level_db: f32,
    /// ALC window size 0–15.
    pub window: u8,
    /// Enable automute.
    pub automute: bool,
    /// Automute detection window 0–15 (2048 × (n+1) samples).
    pub automute_window: u8,
    /// Noise gate threshold in dB (nearest of 16).
    pub noise_gate_db: f32,
}

impl Default for AlcConfig {
    fn default() -> Self {
        Self {
            enabled: false, max_level_db: -6.0, min_level_db: -30.1, window: 0,
            automute: false, automute_window: 0, noise_gate_db: -96.0,
        }
    }
}

/// DRC configuration for [`Es8311Full::configure_drc`].
#[derive(Clone, Copy, Debug)]
pub struct DrcConfig {
    /// Enable the DRC (the DAC volume becomes its maximum gain).
    pub enabled: bool,
    /// Upper target level in dB.
    pub max_level_db: f32,
    /// Lower target level in dB.
    pub min_level_db: f32,
    /// Window size 0–15.
    pub window: u8,
}

impl Default for DrcConfig {
    fn default() -> Self {
        Self { enabled: false, max_level_db: -30.1, min_level_db: -30.1, window: 0 }
    }
}

/// Low-power bits of register `0x0F` for [`Es8311Full::set_low_power`].
#[derive(Clone, Copy, Debug, Default)]
pub struct LowPower {
    /// LPDAC.
    pub dac: bool,
    /// LPPGA.
    pub pga: bool,
    /// LPPGAOUT.
    pub pga_out: bool,
    /// LPVCMMOD (ADC).
    pub adc: bool,
    /// LPADCVRP.
    pub adc_ref: bool,
    /// LPDACVRP.
    pub dac_ref: bool,
    /// LPFLASH.
    pub flash: bool,
    /// LPINT1.
    pub integrator: bool,
}

fn round_half_away(x: f32) -> i32 {
    if x >= 0.0 { (x + 0.5) as i32 } else { (x - 0.5) as i32 }
}

fn nearest(table: &[f32; 16], value: f32) -> u8 {
    let dist = |t: f32| if t > value { t - value } else { value - t };
    let mut best = 0usize;
    for i in 1..16 {
        if dist(table[i]) < dist(table[best]) {
            best = i;
        }
    }
    best as u8
}

fn volume_to_reg(db: f32) -> u8 {
    let code = round_half_away(db / 0.5) + 191;
    code.clamp(0, 255) as u8
}

/// Solve the clock registers `[0x02, 0x03, 0x04, 0x05]` for an MCLK / fs pair: the guide's
/// preset table first (3.3 V only), then a search over pre-divider, multiplier and ADC/DAC
/// divider for an allowed MCLK:LRCK ratio.
fn solve_clock(mclk_hz: u32, fs: u32, dvdd_volts: f32) -> Option<[u8; 4]> {
    if fs == 0 {
        return None;
    }
    let low = dvdd_volts < 2.5;
    if !low {
        for p in CLOCK_PRESETS.iter() {
            if p.0 == mclk_hz && p.1 == fs {
                return Some([p.2, p.3, p.4, p.5]);
            }
        }
    }
    let pre_limit: u64 = if low { 4_500_000 } else { 10_000_000 };
    let clk_limit: u64 = if low { 13_000_000 } else { 35_000_000 };
    let mclk = mclk_hz as u64;
    let fs = fs as u64;
    let mut best: Option<((usize, u64, u64, u64), u8, u8, u8)> = None;
    for dp in 1u64..=8 {
        if mclk >= pre_limit * dp {
            continue;
        }
        for (mi, mult) in [1u64, 2, 4, 8].iter().enumerate() {
            for div in 0u64..16 {
                let den = dp * (div + 1);
                if mclk * mult >= clk_limit * den {
                    continue;
                }
                if (mclk * mult) % (den * fs) != 0 {
                    continue;
                }
                let ratio = mclk * mult / (den * fs);
                if ratio % 16 != 0 || ratio < 256 || ratio > 1008 {
                    continue;
                }
                let rank = PREFERRED_RATIOS.iter().position(|r| *r == ratio).unwrap_or(99);
                let key = (rank, dp, *mult, div);
                if best.map_or(true, |b| key < b.0) {
                    best = Some((key, (((dp - 1) as u8) << 5) | ((mi as u8) << 3), (ratio / 16) as u8, div as u8));
                }
            }
        }
    }
    best.map(|(_, r02, osr, div)| [r02, osr, osr, (div << 4) | div])
}

/// ES8311 minimal driver — bring-up for voice playback and recording.
///
/// [`new`](Self::new) runs the initialization sequence: software reset, clock tree for the
/// MCLK / sample rate, I²S 16-bit slave, analog path, microphone 24 dB, ADC 0 dB, DAC
/// volume −95.5 dB (silent until [`set_volume`](Self::set_volume)).
pub struct Es8311Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    mclk_hz: u32,
    fs: u32,
    dvdd: f32,
}

impl<I2C: I2c> Es8311Minimal<I2C> {
    /// Create the driver and run the initialization sequence (3.3 V clock limits).
    ///
    /// # Arguments
    /// * `i2c` — I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit address, `0x18` (CE low) or `0x19` (CE high).
    /// * `mclk_hz` — master clock in Hz (for example `12_288_000`).
    /// * `sample_rate_hz` — audio sample rate in Hz (for example `48_000`).
    /// * `delay` — delay provider for the 20 ms reset wait.
    ///
    /// # Errors
    /// [`Es8311Error::InvalidArgument`] if the MCLK : sample-rate pair has no valid divider set.
    pub fn new<D: DelayNs>(
        i2c: I2C, addr: u8, mclk_hz: u32, sample_rate_hz: u32, delay: &mut D,
    ) -> Result<Self, Es8311Error<I2C::Error>> {
        Self::new_with_dvdd(i2c, addr, mclk_hz, sample_rate_hz, 3.3, delay)
    }

    /// As [`new`](Self::new), with the DVDD supply voltage selecting the 3.3 V or 1.8 V clock limits.
    pub fn new_with_dvdd<D: DelayNs>(
        i2c: I2C, addr: u8, mclk_hz: u32, sample_rate_hz: u32, dvdd_volts: f32, delay: &mut D,
    ) -> Result<Self, Es8311Error<I2C::Error>> {
        let clk = solve_clock(mclk_hz, sample_rate_hz, dvdd_volts).ok_or(Es8311Error::InvalidArgument)?;
        let mut s = Self { i2c, addr, mclk_hz, fs: sample_rate_hz, dvdd: dvdd_volts };
        s.soft_reset(delay)?;
        s.w(REG_CLK1, 0x3F)?;
        s.write_clock(&clk)?;
        s.w(REG_CLK6, 0x03)?;
        s.w(REG_CLK7, 0x00)?;
        s.w(REG_CLK8, 0xFF)?;
        s.w(REG_SDP_IN, 0x0C)?;
        s.w(REG_SDP_OUT, 0x0C)?;
        s.power_up_analog()?;
        s.w(REG_ADC_SCALE, 0x04)?;
        s.w(REG_SYS_MIC, 0x18)?;
        s.w(REG_ADC_VOLUME, 0xBF)?;
        s.w(REG_DAC_VOLUME, 0x00)?;
        Ok(s)
    }

    fn w(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    fn r(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    fn upd(&mut self, reg: u8, mask: u8, value: u8) -> Result<(), I2C::Error> {
        let v = (self.r(reg)? & !mask) | (value & mask);
        self.w(reg, v)
    }

    fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.w(REG_RESET, 0x1F)?;
        delay.delay_ms(20);
        self.w(REG_RESET, 0x00)?;
        self.w(REG_RESET, 0x80)
    }

    fn write_clock(&mut self, clk: &[u8; 4]) -> Result<(), I2C::Error> {
        self.w(REG_CLK2, clk[0])?;
        self.w(REG_CLK3, clk[1])?;
        self.w(REG_CLK4, clk[2])?;
        self.w(REG_CLK5, clk[3])
    }

    fn power_up_analog(&mut self) -> Result<(), I2C::Error> {
        self.w(0x0B, 0x00)?;
        self.w(0x0C, 0x00)?;
        self.w(0x10, 0x1F)?;
        self.w(0x11, 0x7F)?;
        self.w(REG_SYS_PWR, 0x01)?;
        self.w(REG_SYS_ADC, 0x02)?;
        self.w(0x12, 0x00)?;
        self.w(0x13, 0x10)?;
        self.w(REG_ADC_HPF2_EQ, 0x6A)?;
        self.w(REG_DAC_RAMP_EQ, 0x08)
    }

    /// Set the DAC digital volume: −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
    pub fn set_volume(&mut self, db: f32) -> Result<(), I2C::Error> {
        self.w(REG_DAC_VOLUME, volume_to_reg(db))
    }

    /// Mute or unmute the DAC (DSM and DEM mute together, `0x31` bits 6 and 5).
    pub fn set_mute(&mut self, muted: bool) -> Result<(), I2C::Error> {
        self.upd(REG_DAC_MUTE, 0x60, if muted { 0x60 } else { 0x00 })
    }

    /// Set the analog microphone PGA gain: 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
    pub fn set_mic_gain(&mut self, db: f32) -> Result<(), I2C::Error> {
        self.upd(REG_SYS_MIC, 0x0F, round_half_away(db / 3.0).clamp(0, 10) as u8)
    }
}

/// ES8311 full driver — extends [`Es8311Minimal`] with the clock tree, serial port, microphone /
/// ADC / DAC dynamics, equalizers, routing, power control and raw register access.
pub struct Es8311Full<I2C> {
    inner: Es8311Minimal<I2C>,
}

impl<I2C: I2c> Es8311Full<I2C> {
    /// Create the driver and run the initialization sequence. Same arguments as [`Es8311Minimal::new`].
    pub fn new<D: DelayNs>(
        i2c: I2C, addr: u8, mclk_hz: u32, sample_rate_hz: u32, delay: &mut D,
    ) -> Result<Self, Es8311Error<I2C::Error>> {
        Ok(Self { inner: Es8311Minimal::new(i2c, addr, mclk_hz, sample_rate_hz, delay)? })
    }

    /// As [`new`](Self::new), with the DVDD supply voltage selecting the clock limits.
    pub fn new_with_dvdd<D: DelayNs>(
        i2c: I2C, addr: u8, mclk_hz: u32, sample_rate_hz: u32, dvdd_volts: f32, delay: &mut D,
    ) -> Result<Self, Es8311Error<I2C::Error>> {
        Ok(Self { inner: Es8311Minimal::new_with_dvdd(i2c, addr, mclk_hz, sample_rate_hz, dvdd_volts, delay)? })
    }

    /// Set the DAC digital volume. Delegates to the inner [`Es8311Minimal`].
    pub fn set_volume(&mut self, db: f32) -> Result<(), I2C::Error> {
        self.inner.set_volume(db)
    }

    /// Mute or unmute the DAC. Delegates to the inner [`Es8311Minimal`].
    pub fn set_mute(&mut self, muted: bool) -> Result<(), I2C::Error> {
        self.inner.set_mute(muted)
    }

    /// Set the analog microphone PGA gain. Delegates to the inner [`Es8311Minimal`].
    pub fn set_mic_gain(&mut self, db: f32) -> Result<(), I2C::Error> {
        self.inner.set_mic_gain(db)
    }

    /// Read back the DAC volume in dB (−95.5 … +32).
    pub fn get_volume(&mut self) -> Result<f32, I2C::Error> {
        Ok((self.inner.r(REG_DAC_VOLUME)? as i32 - 191) as f32 * 0.5)
    }

    /// Read back the analog microphone PGA gain in dB (0 … 30).
    pub fn get_mic_gain(&mut self) -> Result<f32, I2C::Error> {
        Ok((self.inner.r(REG_SYS_MIC)? & 0x0F) as f32 * 3.0)
    }

    /// Software reset; registers return to their reset values (configure again afterwards).
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.soft_reset(delay)
    }

    /// Re-enable the analog, reference and clock blocks (vendor values) and set `CSM_ON`.
    pub fn power_up(&mut self) -> Result<(), I2C::Error> {
        self.inner.upd(REG_RESET, 0x9F, 0x00)?;
        self.inner.upd(REG_RESET, 0x80, 0x80)?;
        self.inner.w(REG_CLK1, 0x3F)?;
        self.inner.power_up_analog()
    }

    /// Standby: mute the DAC, power down the analog blocks, reset all and clear `CSM_ON`.
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        self.inner.set_mute(true)?;
        self.inner.upd(REG_SYS_PWR, 0xFC, 0xFC)?;
        self.inner.upd(REG_SYS_ADC, 0x70, 0x70)?;
        self.inner.upd(REG_SYS_PWR, 0x03, 0x00)?;
        self.inner.w(REG_RESET, 0x1F)
    }

    /// Set the low-power bits of register `0x0F` (slightly lower THD+N / SNR).
    pub fn set_low_power(&mut self, lp: LowPower) -> Result<(), I2C::Error> {
        let flags = [lp.dac, lp.pga, lp.pga_out, lp.adc, lp.adc_ref, lp.dac_ref, lp.flash, lp.integrator];
        let mut value = 0u8;
        for (i, f) in flags.iter().enumerate() {
            if *f {
                value |= 0x80 >> i;
            }
        }
        self.inner.w(REG_SYS_LP, value)
    }

    /// Program the clock tree (registers `0x01`–`0x05`) from a preset or computed dividers.
    ///
    /// # Errors
    /// [`Es8311Error::InvalidArgument`] if no valid divider set exists (nothing is written).
    pub fn configure_clock(
        &mut self, mclk_hz: u32, sample_rate_hz: u32, mclk_from_bclk: bool, mclk_invert: bool,
    ) -> Result<(), Es8311Error<I2C::Error>> {
        let clk = solve_clock(mclk_hz, sample_rate_hz, self.inner.dvdd).ok_or(Es8311Error::InvalidArgument)?;
        self.inner.mclk_hz = mclk_hz;
        self.inner.fs = sample_rate_hz;
        let flags = 0x3F | if mclk_from_bclk { 0x80 } else { 0 } | if mclk_invert { 0x40 } else { 0 };
        self.inner.w(REG_CLK1, flags)?;
        self.inner.write_clock(&clk)?;
        Ok(())
    }

    fn internal_clock(&mut self) -> Result<u32, I2C::Error> {
        let r02 = self.inner.r(REG_CLK2)?;
        let mult = [1u64, 2, 4, 8][((r02 >> 3) & 3) as usize];
        Ok((self.inner.mclk_hz as u64 * mult / (((r02 >> 5) & 7) as u64 + 1)) as u32)
    }

    /// Configure the digital audio port (`0x09`, `0x0A`, `RESET.MSC`, `0x06`–`0x08`).
    ///
    /// # Errors
    /// [`Es8311Error::InvalidArgument`] for an unknown word length or unsupported BCLK / LRCK divider.
    pub fn configure_serial(&mut self, cfg: SerialConfig) -> Result<(), Es8311Error<I2C::Error>> {
        let wl = match cfg.word_length {
            24 => 0u8,
            20 => 1,
            18 => 2,
            16 => 3,
            32 => 4,
            _ => return Err(Es8311Error::InvalidArgument),
        };
        let fmt = match cfg.format {
            Format::I2s => 0u8,
            Format::LeftJustified => 1,
            Format::DspA | Format::DspB => 3,
        };
        let lrp = (cfg.lrck_invert as u8) ^ ((cfg.format == Format::DspB) as u8);
        let base = (wl << 2) | fmt | (lrp << 5);
        let mut code = 0u8;
        let mut lrck_div = 0u16;
        if cfg.master {
            let imclk = self.internal_clock()?;
            let fs = self.inner.fs;
            let bclk_div = cfg.bclk_div.unwrap_or((imclk / (fs * 2 * cfg.word_length as u32)) as u16);
            lrck_div = cfg.lrck_div.unwrap_or((imclk / fs) as u16);
            if bclk_div == 0 || lrck_div == 0 || lrck_div > 4096 {
                return Err(Es8311Error::InvalidArgument);
            }
            code = if bclk_div <= 20 {
                (bclk_div - 1) as u8
            } else {
                match BCLK_DIVIDERS.iter().position(|d| *d == bclk_div) {
                    Some(i) => 20 + i as u8,
                    None => return Err(Es8311Error::InvalidArgument),
                }
            };
        }
        let sdp_in_mute = self.inner.r(REG_SDP_IN)? & 0x40;
        self.inner.w(REG_SDP_IN, base | if cfg.dac_right { 0x80 } else { 0 } | sdp_in_mute)?;
        let sdp_out_mute = self.inner.r(REG_SDP_OUT)? & 0x40;
        self.inner.w(REG_SDP_OUT, base | sdp_out_mute)?;
        if cfg.master {
            self.inner.w(REG_CLK6, (if cfg.continuous_bclk { 0x00 } else { 0x40 })
                | if cfg.bclk_invert { 0x20 } else { 0 } | code)?;
            self.inner.upd(REG_CLK7, 0x0F, (((lrck_div - 1) >> 8) & 0x0F) as u8)?;
            self.inner.w(REG_CLK8, ((lrck_div - 1) & 0xFF) as u8)?;
            self.inner.upd(REG_RESET, 0x44, 0x40)?;
        } else {
            self.inner.upd(REG_CLK6, 0x20, if cfg.bclk_invert { 0x20 } else { 0x00 })?;
            self.inner.upd(REG_RESET, 0x44, 0x04)?;
        }
        Ok(())
    }

    /// Tri-state the BCLK / LRCK pins and / or ADCDAT (`0x07` bits 5:4).
    pub fn set_tristate(&mut self, bclk_lrck: bool, adcdat: bool) -> Result<(), I2C::Error> {
        self.inner.upd(REG_CLK7, 0x30, (if bclk_lrck { 0x20 } else { 0 }) | if adcdat { 0x10 } else { 0 })
    }

    /// Select the microphone input.
    pub fn set_input(&mut self, source: Input) -> Result<(), I2C::Error> {
        let (sel, pga) = match source {
            Input::Analog => (0x10, 0x00),
            Input::Digital => (0x40, 0x40),
            Input::None => (0x00, 0x40),
        };
        self.inner.upd(REG_SYS_MIC, 0x70, sel)?;
        self.inner.upd(REG_SYS_ADC, 0x40, pga)
    }

    /// Select the PDM data latch edge (`0x15` bit 0).
    pub fn configure_dmic(&mut self, latch_falling_edge: bool) -> Result<(), I2C::Error> {
        self.inner.upd(REG_ADC_RAMP, 0x01, latch_falling_edge as u8)
    }

    /// Set the ADC digital volume, −95.5 … +32 dB in 0.5 dB steps (ALC maximum gain when the ALC is on).
    pub fn set_adc_volume(&mut self, db: f32) -> Result<(), I2C::Error> {
        self.inner.w(REG_ADC_VOLUME, volume_to_reg(db))
    }

    /// Configure the ADC digital path.
    ///
    /// # Errors
    /// [`Es8311Error::InvalidArgument`] if `osr` is out of range.
    pub fn configure_adc(&mut self, cfg: AdcConfig) -> Result<(), Es8311Error<I2C::Error>> {
        let code = if cfg.double_speed { cfg.osr / 2 } else { cfg.osr / 4 };
        if code < 15 || code > 63 || (cfg.double_speed && code < 16) {
            return Err(Es8311Error::InvalidArgument);
        }
        self.inner.upd(REG_ADC_SCALE, 0x07, round_half_away(cfg.scale_db as f32 / 6.0).clamp(0, 7) as u8)?;
        self.inner.w(REG_CLK3, (if cfg.double_speed { 0x40 } else { 0x00 }) | code as u8)?;
        self.inner.upd(REG_ADC_HPF2_EQ, 0x3F, (if cfg.hpf { 0x20 } else { 0x00 }) | (cfg.hpf_coeff2 & 0x1F))?;
        self.inner.upd(REG_ADC_HPF1, 0x1F, cfg.hpf_coeff1 & 0x1F)?;
        Ok(())
    }

    /// Set the ADC volume soft-ramp rate (`0x15` bits 7:4): 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK.
    pub fn set_adc_ramp(&mut self, rate: u8) -> Result<(), I2C::Error> {
        self.inner.upd(REG_ADC_RAMP, 0xF0, rate.min(15) << 4)
    }

    /// Configure the automatic level control, noise gate and automute (`0x18`–`0x1A`).
    pub fn configure_alc(&mut self, cfg: AlcConfig) -> Result<(), I2C::Error> {
        self.inner.w(REG_ADC_ALC1, (if cfg.enabled { 0x80 } else { 0 }) | (if cfg.automute { 0x40 } else { 0 })
            | (cfg.window & 0x0F))?;
        self.inner.w(REG_ADC_ALC2, (nearest(&ALC_LEVELS_DB, cfg.max_level_db) << 4)
            | nearest(&ALC_LEVELS_DB, cfg.min_level_db))?;
        self.inner.w(REG_ADC_AUTOMUTE, ((cfg.automute_window & 0x0F) << 4) | nearest(&NOISE_GATE_DB, cfg.noise_gate_db))
    }

    fn write_eq(&mut self, base: u8, coefficients: &[u32]) -> Result<(), Es8311Error<I2C::Error>> {
        let mut data = [0u8; 20];
        for (i, c) in coefficients.iter().enumerate() {
            if *c >= (1 << 30) {
                return Err(Es8311Error::InvalidArgument);
            }
            data[4 * i] = ((c >> 24) & 0x3F) as u8;
            data[4 * i + 1] = ((c >> 16) & 0xFF) as u8;
            data[4 * i + 2] = ((c >> 8) & 0xFF) as u8;
            data[4 * i + 3] = (c & 0xFF) as u8;
        }
        register::write_register(&mut self.inner.i2c, self.inner.addr, base.into(), 1, &data[..4 * coefficients.len()])?;
        Ok(())
    }

    /// Bypass the ADC equalizer (`0x1C` bit 6).
    pub fn bypass_adc_eq(&mut self) -> Result<(), I2C::Error> {
        self.inner.upd(REG_ADC_HPF2_EQ, 0x40, 0x40)
    }

    /// Load the ADC equalizer coefficients `(b0, a1, a2, b1, b2)` (30-bit values) and enable it.
    ///
    /// # Errors
    /// [`Es8311Error::InvalidArgument`] if a coefficient exceeds 30 bits.
    pub fn set_adc_eq(&mut self, coefficients: [u32; 5]) -> Result<(), Es8311Error<I2C::Error>> {
        self.write_eq(REG_ADCEQ, &coefficients)?;
        self.inner.upd(REG_ADC_HPF2_EQ, 0x40, 0x00)?;
        Ok(())
    }

    /// Set the DAC volume / DRC soft-ramp rate (`0x37` bits 7:4).
    pub fn set_dac_ramp(&mut self, rate: u8) -> Result<(), I2C::Error> {
        self.inner.upd(REG_DAC_RAMP_EQ, 0xF0, rate.min(15) << 4)
    }

    /// Configure the DAC dynamic range control (`0x34`, `0x35`).
    pub fn configure_drc(&mut self, cfg: DrcConfig) -> Result<(), I2C::Error> {
        self.inner.w(REG_DAC_DRC1, (if cfg.enabled { 0x80 } else { 0 }) | (cfg.window & 0x0F))?;
        self.inner.w(REG_DAC_DRC2, (nearest(&ALC_LEVELS_DB, cfg.max_level_db) << 4)
            | nearest(&ALC_LEVELS_DB, cfg.min_level_db))
    }

    /// Bypass the DAC equalizer (`0x37` bit 3).
    pub fn bypass_dac_eq(&mut self) -> Result<(), I2C::Error> {
        self.inner.upd(REG_DAC_RAMP_EQ, 0x08, 0x08)
    }

    /// Load the DAC equalizer coefficients `(b0, b1, a1)` (30-bit values) and enable it.
    ///
    /// # Errors
    /// [`Es8311Error::InvalidArgument`] if a coefficient exceeds 30 bits.
    pub fn set_dac_eq(&mut self, coefficients: [u32; 3]) -> Result<(), Es8311Error<I2C::Error>> {
        self.write_eq(REG_DACEQ, &coefficients)?;
        self.inner.upd(REG_DAC_RAMP_EQ, 0x08, 0x00)?;
        Ok(())
    }

    /// Set the three DAC mute bits individually (`0x31` bits 7:5).
    pub fn set_dac_mute_options(&mut self, dsm: bool, dem: bool, dsm_target: bool) -> Result<(), I2C::Error> {
        self.inner.upd(REG_DAC_MUTE, 0xE0, (if dsm_target { 0x80 } else { 0 }) | (if dsm { 0x40 } else { 0 })
            | if dem { 0x20 } else { 0 })
    }

    /// Feed the ADC output to the DAC (`0x44` bit 7).
    pub fn set_loopback(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.upd(REG_GPIO, 0x80, if enabled { 0x80 } else { 0x00 })
    }

    /// Select the ADCDAT content (`0x44` bits 6:4).
    pub fn set_adcdat_mode(&mut self, mode: AdcdatMode) -> Result<(), I2C::Error> {
        let code = match mode {
            AdcdatMode::AdcAdc => 0u8,
            AdcdatMode::AdcZero => 1,
            AdcdatMode::ZeroAdc => 2,
            AdcdatMode::DacAdc => 4,
            AdcdatMode::AdcDac => 5,
            AdcdatMode::DacDac => 6,
        };
        self.inner.upd(REG_GPIO, 0x70, code << 4)
    }

    /// Mute the digital audio input and / or output (`0x09` / `0x0A` bit 6).
    pub fn set_sdp_mute(&mut self, input: bool, output: bool) -> Result<(), I2C::Error> {
        self.inner.upd(REG_SDP_IN, 0x40, if input { 0x40 } else { 0x00 })?;
        self.inner.upd(REG_SDP_OUT, 0x40, if output { 0x40 } else { 0x00 })
    }

    /// Raw register read.
    pub fn read_register(&mut self, register: u8) -> Result<u8, I2C::Error> {
        self.inner.r(register)
    }

    /// Raw register write.
    pub fn write_register(&mut self, register: u8, value: u8) -> Result<(), I2C::Error> {
        self.inner.w(register, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};
    use std::cell::RefCell;
    use std::rc::Rc;

    const ADDR: u8 = 0x18;

    /// Register-file fake: a write of `[reg, data..]` stores consecutive bytes, a write_read of
    /// `[reg]` returns them. Shares its state so the test can inspect it after the driver owns it.
    struct Regs {
        regs: [u8; 256],
        writes: Vec<Vec<u8>>,
    }

    impl Default for Regs {
        fn default() -> Self {
            Self { regs: [0u8; 256], writes: Vec::new() }
        }
    }

    #[derive(Clone, Default)]
    struct Fake(Rc<RefCell<Regs>>);

    impl ErrorType for Fake {
        type Error = embedded_hal::i2c::ErrorKind;
    }

    impl I2c for Fake {
        fn transaction(&mut self, _address: u8, operations: &mut [Operation<'_>]) -> Result<(), Self::Error> {
            let mut st = self.0.borrow_mut();
            let mut reg = 0usize;
            for op in operations.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        st.writes.push(data.to_vec());
                        reg = data[0] as usize;
                        if data.len() > 1 {
                            for (i, b) in data[1..].iter().enumerate() {
                                st.regs[reg + i] = *b;
                            }
                        }
                    }
                    Operation::Read(buf) => {
                        for (i, b) in buf.iter_mut().enumerate() {
                            *b = st.regs[reg + i];
                        }
                    }
                }
            }
            Ok(())
        }
    }

    fn make_full() -> (Fake, Es8311Full<Fake>) {
        let fake = Fake::default();
        let chip = Es8311Full::new(fake.clone(), ADDR, 12_288_000, 48_000, &mut NoopDelay::new()).unwrap();
        (fake, chip)
    }

    fn reg(f: &Fake, r: u8) -> u8 {
        f.0.borrow().regs[r as usize]
    }

    #[test]
    fn init_sequence_transactions() {
        let expectations = [
            I2cTransaction::write(ADDR, vec![0x00, 0x1F]),
            I2cTransaction::write(ADDR, vec![0x00, 0x00]),
            I2cTransaction::write(ADDR, vec![0x00, 0x80]),
            I2cTransaction::write(ADDR, vec![0x01, 0x3F]),
            I2cTransaction::write(ADDR, vec![0x02, 0x00]),
            I2cTransaction::write(ADDR, vec![0x03, 0x10]),
            I2cTransaction::write(ADDR, vec![0x04, 0x10]),
            I2cTransaction::write(ADDR, vec![0x05, 0x00]),
            I2cTransaction::write(ADDR, vec![0x06, 0x03]),
            I2cTransaction::write(ADDR, vec![0x07, 0x00]),
            I2cTransaction::write(ADDR, vec![0x08, 0xFF]),
            I2cTransaction::write(ADDR, vec![0x09, 0x0C]),
            I2cTransaction::write(ADDR, vec![0x0A, 0x0C]),
            I2cTransaction::write(ADDR, vec![0x0B, 0x00]),
            I2cTransaction::write(ADDR, vec![0x0C, 0x00]),
            I2cTransaction::write(ADDR, vec![0x10, 0x1F]),
            I2cTransaction::write(ADDR, vec![0x11, 0x7F]),
            I2cTransaction::write(ADDR, vec![0x0D, 0x01]),
            I2cTransaction::write(ADDR, vec![0x0E, 0x02]),
            I2cTransaction::write(ADDR, vec![0x12, 0x00]),
            I2cTransaction::write(ADDR, vec![0x13, 0x10]),
            I2cTransaction::write(ADDR, vec![0x1C, 0x6A]),
            I2cTransaction::write(ADDR, vec![0x37, 0x08]),
            I2cTransaction::write(ADDR, vec![0x16, 0x04]),
            I2cTransaction::write(ADDR, vec![0x14, 0x18]),
            I2cTransaction::write(ADDR, vec![0x17, 0xBF]),
            I2cTransaction::write(ADDR, vec![0x32, 0x00]),
            // set_volume(0.0) and set_mic_gain(30.0) (read-modify-write)
            I2cTransaction::write(ADDR, vec![0x32, 0xBF]),
            I2cTransaction::write_read(ADDR, vec![0x14], vec![0x18]),
            I2cTransaction::write(ADDR, vec![0x14, 0x1A]),
        ];
        let mut i2c = I2cMock::new(&expectations);
        let mut chip = Es8311Minimal::new(i2c.clone(), ADDR, 12_288_000, 48_000, &mut NoopDelay::new()).unwrap();
        chip.set_volume(0.0).unwrap();
        chip.set_mic_gain(30.0).unwrap();
        i2c.done();
    }

    #[test]
    fn init_registers_and_minimal_api() {
        let fake = Fake::default();
        let mut chip = Es8311Minimal::new(fake.clone(), ADDR, 12_288_000, 48_000, &mut NoopDelay::new()).unwrap();
        assert_eq!([2u8, 3, 4, 5].map(|r| reg(&fake, r)), [0x00, 0x10, 0x10, 0x00]);
        assert_eq!(reg(&fake, 0x14), 0x18);
        assert_eq!(reg(&fake, 0x17), 0xBF);
        assert_eq!(reg(&fake, 0x32), 0x00);
        chip.set_volume(-95.5).unwrap();
        assert_eq!(reg(&fake, 0x32), 0x00);
        chip.set_volume(32.0).unwrap();
        assert_eq!(reg(&fake, 0x32), 0xFF);
        chip.set_volume(100.0).unwrap();
        assert_eq!(reg(&fake, 0x32), 0xFF);
        chip.set_volume(-10.2).unwrap();
        assert_eq!(reg(&fake, 0x32), 171);
        fake.0.borrow_mut().regs[0x31] = 0x81;
        chip.set_mute(true).unwrap();
        assert_eq!(reg(&fake, 0x31), 0xE1);
        chip.set_mute(false).unwrap();
        assert_eq!(reg(&fake, 0x31), 0x81);
        chip.set_mic_gain(7.4).unwrap();
        assert_eq!(reg(&fake, 0x14), 0x12);
        chip.set_mic_gain(99.0).unwrap();
        assert_eq!(reg(&fake, 0x14) & 0x0F, 10);
    }

    #[test]
    fn clock_solver() {
        assert!(Es8311Minimal::new(Fake::default(), ADDR, 1000, 48_000, &mut NoopDelay::new()).is_err());
        let fake = Fake::default();
        Es8311Minimal::new(fake.clone(), ADDR, 24_576_000, 48_000, &mut NoopDelay::new()).unwrap();
        assert_eq!(reg(&fake, 0x02), 0x20);
        let fake = Fake::default();
        Es8311Minimal::new(fake.clone(), ADDR, 12_288_000, 16_000, &mut NoopDelay::new()).unwrap();
        let r02 = reg(&fake, 0x02);
        let mult = [1u64, 2, 4, 8][((r02 >> 3) & 3) as usize];
        let ratio = 12_288_000u64 * mult / (((r02 >> 5) & 7) as u64 + 1) / ((reg(&fake, 0x05) & 0x0F) as u64 + 1) / 16_000;
        assert!([256, 384, 512, 768].contains(&ratio));
        assert_eq!(reg(&fake, 0x03), reg(&fake, 0x04));
        assert!(reg(&fake, 0x03) >= 16 && reg(&fake, 0x03) <= 63);
    }

    #[test]
    fn full_readback_and_power() {
        let (fake, mut chip) = make_full();
        chip.set_volume(-20.0).unwrap();
        assert_eq!(chip.get_volume().unwrap(), -20.0);
        chip.set_mic_gain(21.0).unwrap();
        assert_eq!(chip.get_mic_gain().unwrap(), 21.0);
        let n = fake.0.borrow().writes.len();
        chip.reset(&mut NoopDelay::new()).unwrap();
        assert_eq!(fake.0.borrow().writes[n..], [vec![0, 0x1F], vec![0, 0], vec![0, 0x80]]);
        chip.power_down().unwrap();
        assert_eq!(reg(&fake, 0x00), 0x1F);
        assert_eq!(reg(&fake, 0x31) & 0x60, 0x60);
        assert_eq!(reg(&fake, 0x0D), 0xFC);
        assert_eq!(reg(&fake, 0x0E) & 0x70, 0x70);
        chip.power_up().unwrap();
        assert_eq!(reg(&fake, 0x00), 0x80);
        assert_eq!(reg(&fake, 0x0D), 0x01);
        chip.set_low_power(LowPower { dac: true, integrator: true, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x0F), 0x81);
        chip.set_low_power(LowPower { pga: true, adc: true, adc_ref: true, flash: true, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x0F), 0x40 | 0x10 | 0x08 | 0x02);
    }

    #[test]
    fn full_clock_and_serial() {
        let (fake, mut chip) = make_full();
        chip.configure_clock(12_288_000, 48_000, true, true).unwrap();
        assert_eq!(reg(&fake, 0x01), 0xFF);
        chip.configure_clock(12_288_000, 48_000, false, false).unwrap();
        assert_eq!(reg(&fake, 0x01), 0x3F);
        assert_eq!(chip.configure_clock(1000, 48_000, false, false), Err(Es8311Error::InvalidArgument));

        chip.configure_serial(SerialConfig { format: Format::LeftJustified, word_length: 24, ..Default::default() }).unwrap();
        assert_eq!((reg(&fake, 0x09), reg(&fake, 0x0A)), (0x01, 0x01));
        chip.configure_serial(SerialConfig { format: Format::DspB, word_length: 32, dac_right: true, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x09), 0x80 | 0x20 | (4 << 2) | 3);
        assert_eq!(reg(&fake, 0x0A), 0x20 | (4 << 2) | 3);
        chip.configure_serial(SerialConfig { format: Format::DspA, lrck_invert: true, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x0A), 0x20 | (3 << 2) | 3);
        assert_eq!(reg(&fake, 0x00) & 0x44, 0x04);
        chip.set_sdp_mute(true, false).unwrap();
        assert!(reg(&fake, 0x09) & 0x40 != 0 && reg(&fake, 0x0A) & 0x40 == 0);
        chip.configure_serial(SerialConfig::default()).unwrap();
        assert!(reg(&fake, 0x09) & 0x40 != 0);
        chip.configure_serial(SerialConfig { master: true, bclk_div: Some(4), lrck_div: Some(256), ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x00) & 0x44, 0x40);
        assert_eq!((reg(&fake, 0x06), reg(&fake, 0x07) & 0x0F, reg(&fake, 0x08)), (0x03, 0x00, 0xFF));
        chip.configure_serial(SerialConfig { master: true, bclk_div: Some(32), lrck_div: Some(64), continuous_bclk: false,
            bclk_invert: true, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x06), 0x40 | 0x20 | 24);
        assert_eq!(chip.configure_serial(SerialConfig { word_length: 8, ..Default::default() }), Err(Es8311Error::InvalidArgument));

        chip.set_tristate(true, false).unwrap();
        assert_eq!(reg(&fake, 0x07) & 0x30, 0x20);
        chip.set_tristate(false, true).unwrap();
        assert_eq!(reg(&fake, 0x07) & 0x30, 0x10);
    }

    #[test]
    fn full_input_and_adc() {
        let (fake, mut chip) = make_full();
        chip.set_input(Input::Digital).unwrap();
        assert!(reg(&fake, 0x14) & 0x70 == 0x40 && reg(&fake, 0x0E) & 0x40 != 0);
        chip.set_input(Input::Analog).unwrap();
        assert!(reg(&fake, 0x14) & 0x70 == 0x10 && reg(&fake, 0x0E) & 0x40 == 0);
        chip.set_input(Input::None).unwrap();
        assert!(reg(&fake, 0x14) & 0x70 == 0x00 && reg(&fake, 0x0E) & 0x40 != 0);
        chip.configure_dmic(true).unwrap();
        assert_eq!(reg(&fake, 0x15) & 1, 1);
        chip.configure_dmic(false).unwrap();
        assert_eq!(reg(&fake, 0x15) & 1, 0);

        chip.set_adc_volume(-95.5).unwrap();
        assert_eq!(reg(&fake, 0x17), 0);
        chip.configure_adc(AdcConfig { scale_db: 42, osr: 128, hpf: false, hpf_coeff1: 5, hpf_coeff2: 0x0A, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x16) & 7, 7);
        assert_eq!(reg(&fake, 0x03), 32);
        assert_eq!((reg(&fake, 0x1C) & 0x3F, reg(&fake, 0x1B) & 0x1F), (0x0A, 0x05));
        chip.configure_adc(AdcConfig { double_speed: true, ..Default::default() }).unwrap();
        assert_eq!(reg(&fake, 0x03), 0x40 | 32);
        assert!(reg(&fake, 0x1C) & 0x20 != 0);
        assert_eq!(chip.configure_adc(AdcConfig { osr: 8, ..Default::default() }), Err(Es8311Error::InvalidArgument));
        assert_eq!(chip.configure_adc(AdcConfig { osr: 20, double_speed: true, ..Default::default() }), Err(Es8311Error::InvalidArgument));
        chip.set_adc_ramp(7).unwrap();
        assert_eq!(reg(&fake, 0x15) >> 4, 7);
        chip.set_adc_ramp(99).unwrap();
        assert_eq!(reg(&fake, 0x15) >> 4, 15);

        chip.configure_alc(AlcConfig { enabled: true, max_level_db: -12.0, min_level_db: -18.1, window: 3, automute: true,
            automute_window: 2, noise_gate_db: -60.0 }).unwrap();
        assert_eq!((reg(&fake, 0x18), reg(&fake, 0x19), reg(&fake, 0x1A)), (0xC3, (7 << 4) | 3, (2 << 4) | 6));
        chip.configure_alc(AlcConfig::default()).unwrap();
        assert_eq!((reg(&fake, 0x18), reg(&fake, 0x19), reg(&fake, 0x1A)), (0, 0xF0, 0));
    }

    #[test]
    fn full_equalizers_dac_and_routing() {
        let (fake, mut chip) = make_full();
        chip.set_adc_eq([1, 2, 3, 4, 0x3FFF_FFFF]).unwrap();
        assert_eq!(reg(&fake, 0x1C) & 0x40, 0);
        assert_eq!([0x1Du8, 0x1E, 0x1F, 0x20].map(|a| reg(&fake, a)), [0, 0, 0, 1]);
        assert_eq!([0x2Du8, 0x2E, 0x2F, 0x30].map(|a| reg(&fake, a)), [0x3F, 0xFF, 0xFF, 0xFF]);
        assert!(fake.0.borrow().writes.iter().any(|w| w.len() == 21 && w[0] == 0x1D));
        chip.bypass_adc_eq().unwrap();
        assert_eq!(reg(&fake, 0x1C) & 0x40, 0x40);
        assert_eq!(chip.set_adc_eq([1 << 30, 0, 0, 0, 0]), Err(Es8311Error::InvalidArgument));
        chip.set_dac_eq([0x100, 0x200, 0x300]).unwrap();
        assert_eq!(reg(&fake, 0x37) & 0x08, 0);
        assert_eq!([0x3Bu8, 0x3A, 0x3F, 0x3E, 0x42].map(|a| reg(&fake, a)), [0x00, 0x01, 0x00, 0x02, 0x03]);
        chip.bypass_dac_eq().unwrap();
        assert_eq!(reg(&fake, 0x37) & 0x08, 0x08);

        chip.set_dac_ramp(8).unwrap();
        assert!(reg(&fake, 0x37) >> 4 == 8 && reg(&fake, 0x37) & 0x08 != 0);
        chip.configure_drc(DrcConfig { enabled: true, max_level_db: -12.0, min_level_db: -24.1, window: 5 }).unwrap();
        assert_eq!((reg(&fake, 0x34), reg(&fake, 0x35)), (0x85, (7 << 4) | 1));
        chip.set_dac_mute_options(true, false, true).unwrap();
        assert_eq!(reg(&fake, 0x31) & 0xE0, 0xC0);
        chip.set_dac_mute_options(true, true, false).unwrap();
        assert_eq!(reg(&fake, 0x31) & 0xE0, 0x60);

        chip.set_loopback(true).unwrap();
        assert!(reg(&fake, 0x44) & 0x80 != 0);
        chip.set_adcdat_mode(AdcdatMode::DacAdc).unwrap();
        assert_eq!(reg(&fake, 0x44), 0xC0);
        chip.set_adcdat_mode(AdcdatMode::DacDac).unwrap();
        assert_eq!((reg(&fake, 0x44) >> 4) & 7, 6);
        chip.set_loopback(false).unwrap();
        assert_eq!(reg(&fake, 0x44), 0x60);

        chip.write_register(0x40, 0x34).unwrap();
        assert_eq!(reg(&fake, 0x40), 0x34);
        assert_eq!(chip.read_register(0x40).unwrap(), 0x34);
    }
}
