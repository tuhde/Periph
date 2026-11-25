//! ADS1014 — 12-bit I²C ADC with PGA and programmable comparator (Texas Instruments).
//!
//! Single-input (`AIN0` − `AIN1`) ΔΣ ADC with internal reference and
//! oscillator, a programmable-gain amplifier (±6.144 V … ±0.256 V full scale),
//! data rates of 128–3300 SPS, single-shot or continuous conversion, and a
//! programmable digital comparator with an `ALERT/RDY` output. The chip has no
//! input multiplexer and no ID register, so construction performs no I²C
//! traffic. Four selectable addresses (`0x48`–`0x4B`) via the `ADDR` strap.
//!
//! Registers are 16-bit, big-endian, addressed through a persistent Address
//! Pointer; Config writes are always whole-word — see
//! `specs/adc_dac/ads1014.md`.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Ads1014Full::poll_interrupt`] (no callback subscription
//! — polling is always caller-managed in this crate's `no_std` Rust drivers).
//! The caller samples the `ALERT/RDY` pin itself and passes its level in, or
//! passes `None` when no pin is wired.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{self, to_signed};

const REG_CONVERSION: u8 = 0x00;
const REG_CONFIG: u8 = 0x01;
const REG_LO_THRESH: u8 = 0x02;
const REG_HI_THRESH: u8 = 0x03;

const CONFIG_RESET: u16 = 0x8583;
const LO_RESET: u16 = 0x8000;
const HI_RESET: u16 = 0x7FFF;

const CFG_OS: u16 = 0x8000;
const CFG_MODE_SINGLE: u16 = 0x0100;
const QUEUE_DISABLE: u8 = 3;

const FULL_SCALE: [f32; 6] = [6.144, 4.096, 2.048, 1.024, 0.512, 0.256];
const SPS: [u16; 7] = [128, 250, 490, 920, 1600, 2400, 3300];

/// Default 7-bit I²C address (`ADDR` = GND). Valid range `0x48`–`0x4B`.
pub const ADS1014_I2C_ADDRESS: u8 = 0x48;

/// PGA full-scale ±6.144 V.
pub const ADS1014_PGA_6_144: u8 = 0;
/// PGA full-scale ±4.096 V.
pub const ADS1014_PGA_4_096: u8 = 1;
/// PGA full-scale ±2.048 V (default).
pub const ADS1014_PGA_2_048: u8 = 2;
/// PGA full-scale ±1.024 V.
pub const ADS1014_PGA_1_024: u8 = 3;
/// PGA full-scale ±0.512 V.
pub const ADS1014_PGA_0_512: u8 = 4;
/// PGA full-scale ±0.256 V.
pub const ADS1014_PGA_0_256: u8 = 5;

/// Data rate 128 SPS.
pub const ADS1014_DR_128: u8 = 0;
/// Data rate 250 SPS.
pub const ADS1014_DR_250: u8 = 1;
/// Data rate 490 SPS.
pub const ADS1014_DR_490: u8 = 2;
/// Data rate 920 SPS.
pub const ADS1014_DR_920: u8 = 3;
/// Data rate 1600 SPS (default).
pub const ADS1014_DR_1600: u8 = 4;
/// Data rate 2400 SPS.
pub const ADS1014_DR_2400: u8 = 5;
/// Data rate 3300 SPS.
pub const ADS1014_DR_3300: u8 = 6;

/// Traditional comparator.
pub const ADS1014_COMP_TRADITIONAL: u8 = 0;
/// Window comparator.
pub const ADS1014_COMP_WINDOW: u8 = 1;
/// `ALERT/RDY` active low (default).
pub const ADS1014_ACTIVE_LOW: u8 = 0;
/// `ALERT/RDY` active high.
pub const ADS1014_ACTIVE_HIGH: u8 = 1;
/// Assert after 1 out-of-range conversion.
pub const ADS1014_QUEUE_1: u8 = 0;
/// Assert after 2 consecutive out-of-range conversions.
pub const ADS1014_QUEUE_2: u8 = 1;
/// Assert after 4 consecutive out-of-range conversions.
pub const ADS1014_QUEUE_4: u8 = 2;

/// Interrupt source: a conversion finished.
pub const ADS1014_SOURCE_CONVERSION_READY: u8 = 0x01;
/// Interrupt source: the result is beyond the comparator thresholds.
pub const ADS1014_SOURCE_COMPARATOR: u8 = 0x02;

/// Errors from the ADS1014 driver's checked operations.
#[derive(Debug)]
pub enum Ads1014Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// An argument outside its documented range, or `high` ≤ `low` for the
    /// comparator thresholds.
    InvalidArgument,
}

impl<E> From<E> for Ads1014Error<E> {
    fn from(e: E) -> Self {
        Ads1014Error::Bus(e)
    }
}

/// Mutable configuration shared by the Minimal and Full drivers.
#[derive(Clone, Copy)]
struct State {
    pga: u8,
    dr: u8,
    continuous: bool,
    comp_mode: u8,
    comp_pol: u8,
    comp_lat: bool,
    queue: u8,
    source: u8,
}

impl State {
    const fn new() -> Self {
        Self {
            pga: ADS1014_PGA_2_048, dr: ADS1014_DR_1600, continuous: false,
            comp_mode: 0, comp_pol: 0, comp_lat: false, queue: 0, source: 0,
        }
    }

    fn comp_que(&self) -> u8 {
        match self.source {
            ADS1014_SOURCE_CONVERSION_READY => 0,
            ADS1014_SOURCE_COMPARATOR => self.queue,
            _ => QUEUE_DISABLE,
        }
    }

    fn config_word(&self, os: bool, continuous: bool) -> u16 {
        let mut word = if os { CFG_OS } else { 0 };
        word |= (self.pga as u16) << 9;
        if !continuous {
            word |= CFG_MODE_SINGLE;
        }
        word |= (self.dr as u16) << 5;
        word |= (self.comp_mode as u16) << 4;
        word |= (self.comp_pol as u16) << 3;
        word |= (self.comp_lat as u16) << 2;
        word | self.comp_que() as u16
    }
}

/// ADS1014 — minimal interface: single-shot reads of `AIN0` − `AIN1`.
pub struct Ads1014Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    state: State,
}

impl<I2C: I2c> Ads1014Minimal<I2C> {
    /// Construct the driver. Performs no I²C traffic (the chip has no ID
    /// register). Defaults: ±2.048 V range, 1600 SPS, single-shot, comparator
    /// disabled.
    ///
    /// `addr` is `0x48`–`0x4B` per the board's `ADDR` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, state: State::new() }
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn write_reg(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[(value >> 8) as u8, value as u8])
    }

    fn read_conversion(&mut self) -> Result<i16, I2C::Error> {
        Ok((to_signed(self.read_reg(REG_CONVERSION)? as u32, 16) >> 4) as i16)
    }

    /// Run one single-shot conversion of `AIN0` − `AIN1` and return the
    /// 12-bit two's-complement code (−2048…2047).
    ///
    /// Writes the whole Config word with `OS` = 1, polls `OS` until the chip
    /// is idle again (waiting 1 ms between polls), then reads `CONVERSION`.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, I2C::Error> {
        let word = self.state.config_word(true, false);
        self.write_reg(REG_CONFIG, word)?;
        self.state.continuous = false;
        for _ in 0..100 {
            if self.read_reg(REG_CONFIG)? & CFG_OS != 0 {
                break;
            }
            delay.delay_ms(1);
        }
        self.read_conversion()
    }

    /// Run one single-shot conversion and return the voltage `AIN0` − `AIN1`
    /// in volts (`raw × full-scale / 2048`).
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        let raw = self.read_raw(delay)?;
        Ok(raw as f32 * FULL_SCALE[self.state.pga as usize] / 2048.0)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// ADS1014 — full interface: extends [`Ads1014Minimal`] with PGA gain, data
/// rate, continuous conversion, the programmable comparator and the
/// `ALERT/RDY` interrupt routing.
pub struct Ads1014Full<I2C> {
    inner: Ads1014Minimal<I2C>,
    lo_raw: i16,
    hi_raw: i16,
}

impl<I2C: I2c> Ads1014Full<I2C> {
    /// Construct the driver; performs no I²C traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads1014Minimal::new(i2c, addr), lo_raw: -2048, hi_raw: 2047 }
    }

    /// Single-shot read of the 12-bit code. Delegates to
    /// [`Ads1014Minimal::read_raw`].
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, I2C::Error> {
        self.inner.read_raw(delay)
    }

    /// Single-shot read in volts. Delegates to [`Ads1014Minimal::read_voltage`].
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        self.inner.read_voltage(delay)
    }

    fn write_config(&mut self) -> Result<(), I2C::Error> {
        let word = self.inner.state.config_word(false, self.inner.state.continuous);
        self.inner.write_reg(REG_CONFIG, word)
    }

    /// Select the full-scale range used by all following reads (`ADS1014_PGA_*`).
    /// Thresholds must be rewritten after a gain change. Values above 5 are
    /// rejected (they alias ±0.256 V).
    pub fn set_gain(&mut self, pga: u8) -> Result<(), Ads1014Error<I2C::Error>> {
        if pga > ADS1014_PGA_0_256 {
            return Err(Ads1014Error::InvalidArgument);
        }
        self.inner.state.pga = pga;
        if self.inner.state.continuous {
            self.write_config()?;
        }
        Ok(())
    }

    /// Currently configured `PGA` code (0–5).
    pub fn get_gain(&self) -> u8 {
        self.inner.state.pga
    }

    /// Full-scale range (±V) of the current gain.
    pub fn full_scale(&self) -> f32 {
        FULL_SCALE[self.inner.state.pga as usize]
    }

    /// Size of one conversion code in volts: `full_scale() / 2048`.
    pub fn lsb(&self) -> f32 {
        self.full_scale() / 2048.0
    }

    /// Select the conversion data rate (`ADS1014_DR_*`). Values above 6 are
    /// rejected (7 aliases 3300 SPS).
    pub fn set_data_rate(&mut self, dr: u8) -> Result<(), Ads1014Error<I2C::Error>> {
        if dr > ADS1014_DR_3300 {
            return Err(Ads1014Error::InvalidArgument);
        }
        self.inner.state.dr = dr;
        if self.inner.state.continuous {
            self.write_config()?;
        }
        Ok(())
    }

    /// Currently configured `DR` code (0–6).
    pub fn get_data_rate(&self) -> u8 {
        self.inner.state.dr
    }

    /// Samples per second of the current setting.
    pub fn data_rate_sps(&self) -> u16 {
        SPS[self.inner.state.dr as usize]
    }

    /// Start continuous conversion (`MODE` = 0) at the current data rate.
    pub fn start_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.state.continuous = true;
        self.write_config()
    }

    /// Read the most recent completed result without touching Config (valid
    /// at least 1/DR after [`start_continuous`](Self::start_continuous)).
    pub fn read_continuous_raw(&mut self) -> Result<i16, I2C::Error> {
        self.inner.read_conversion()
    }

    /// Read the most recent completed result in volts: `raw × lsb()`.
    pub fn read_continuous(&mut self) -> Result<f32, I2C::Error> {
        let raw = self.inner.read_conversion()?;
        Ok(raw as f32 * self.lsb())
    }

    /// Stop continuous conversion (`MODE` = 1, single-shot / power-down).
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.state.continuous = false;
        self.write_config()
    }

    /// `true` while a single-shot conversion is running (`OS` = 0; only
    /// meaningful in single-shot mode).
    pub fn is_converting(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONFIG)? & CFG_OS == 0)
    }

    /// Raw 16-bit `CONFIG` register.
    pub fn read_config(&mut self) -> Result<u16, I2C::Error> {
        self.inner.read_reg(REG_CONFIG)
    }

    /// Write the reset values back (`CONFIG` = 0x8583, `LO_THRESH` = 0x8000,
    /// `HI_THRESH` = 0x7FFF) and clear the cached settings. Not an I²C
    /// general-call reset.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONFIG, CONFIG_RESET)?;
        self.inner.write_reg(REG_LO_THRESH, LO_RESET)?;
        self.inner.write_reg(REG_HI_THRESH, HI_RESET)?;
        self.inner.state = State::new();
        self.lo_raw = -2048;
        self.hi_raw = 2047;
        Ok(())
    }

    /// Configure the comparator and enable it as the `ALERT/RDY` source.
    ///
    /// `mode`: `ADS1014_COMP_TRADITIONAL` / `ADS1014_COMP_WINDOW`; `polarity`:
    /// `ADS1014_ACTIVE_LOW` / `ADS1014_ACTIVE_HIGH`; `latch`: hold the alert
    /// until `CONVERSION` is read; `queue`: `ADS1014_QUEUE_1` / `_2` / `_4`.
    pub fn configure_comparator(
        &mut self, mode: u8, polarity: u8, latch: bool, queue: u8,
    ) -> Result<(), Ads1014Error<I2C::Error>> {
        if mode > ADS1014_COMP_WINDOW || polarity > ADS1014_ACTIVE_HIGH || queue > ADS1014_QUEUE_4 {
            return Err(Ads1014Error::InvalidArgument);
        }
        let s = &mut self.inner.state;
        s.comp_mode = mode;
        s.comp_pol = polarity;
        s.comp_lat = latch;
        s.queue = queue;
        s.source = ADS1014_SOURCE_COMPARATOR;
        self.write_config()?;
        Ok(())
    }

    /// Disable the comparator (`COMP_QUE` = 11); `ALERT/RDY` goes high-impedance.
    pub fn disable_comparator(&mut self) -> Result<(), I2C::Error> {
        self.inner.state.source = 0;
        self.write_config()
    }

    fn write_threshold(&mut self, reg: u8, raw: i16) -> Result<(), Ads1014Error<I2C::Error>> {
        if !(-2048..=2047).contains(&raw) {
            return Err(Ads1014Error::InvalidArgument);
        }
        self.inner.write_reg(reg, ((raw as u16) & 0x0FFF) << 4)?;
        Ok(())
    }

    /// Write the comparator low threshold (`LO_THRESH` = `raw << 4`).
    pub fn set_low_threshold_raw(&mut self, raw: i16) -> Result<(), Ads1014Error<I2C::Error>> {
        self.write_threshold(REG_LO_THRESH, raw)?;
        self.lo_raw = raw;
        Ok(())
    }

    /// Write the comparator high threshold (`HI_THRESH` = `raw << 4`).
    pub fn set_high_threshold_raw(&mut self, raw: i16) -> Result<(), Ads1014Error<I2C::Error>> {
        self.write_threshold(REG_HI_THRESH, raw)?;
        self.hi_raw = raw;
        Ok(())
    }

    /// Write both thresholds from volts (converted with [`lsb`](Self::lsb),
    /// clamped to the code range). Rejects `high` ≤ `low`.
    pub fn set_thresholds(&mut self, low: f32, high: f32) -> Result<(), Ads1014Error<I2C::Error>> {
        if high <= low {
            return Err(Ads1014Error::InvalidArgument);
        }
        let step = self.lsb();
        let to_code = |v: f32| -> i16 {
            let c = v / step;
            let r = if c >= 0.0 { (c + 0.5) as i32 } else { -((-c + 0.5) as i32) };
            r.clamp(-2048, 2047) as i16
        };
        self.set_low_threshold_raw(to_code(low))?;
        self.set_high_threshold_raw(to_code(high))
    }

    /// Read both thresholds back as `(low, high)` sign-extended 12-bit codes.
    pub fn get_thresholds_raw(&mut self) -> Result<(i16, i16), I2C::Error> {
        let lo = (to_signed(self.inner.read_reg(REG_LO_THRESH)? as u32, 16) >> 4) as i16;
        let hi = (to_signed(self.inner.read_reg(REG_HI_THRESH)? as u32, 16) >> 4) as i16;
        Ok((lo, hi))
    }

    /// Release a latched comparator output by reading `CONVERSION`.
    pub fn clear_alert(&mut self) -> Result<(), I2C::Error> {
        self.inner.read_reg(REG_CONVERSION)?;
        Ok(())
    }

    /// Route one source to `ALERT/RDY`; the other is disabled.
    ///
    /// `ADS1014_SOURCE_CONVERSION_READY` writes `HI_THRESH` = 0x8000 and
    /// `LO_THRESH` = 0x0000 and sets `COMP_QUE` = 00.
    /// `ADS1014_SOURCE_COMPARATOR` restores the thresholds last set through the
    /// driver and the configured queue; it is rejected when those have
    /// high ≤ low.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Ads1014Error<I2C::Error>> {
        match source {
            ADS1014_SOURCE_CONVERSION_READY => {
                self.inner.write_reg(REG_HI_THRESH, 0x8000)?;
                self.inner.write_reg(REG_LO_THRESH, 0x0000)?;
            }
            ADS1014_SOURCE_COMPARATOR => {
                if self.hi_raw <= self.lo_raw {
                    return Err(Ads1014Error::InvalidArgument);
                }
                self.inner.write_reg(REG_LO_THRESH, ((self.lo_raw as u16) & 0x0FFF) << 4)?;
                self.inner.write_reg(REG_HI_THRESH, ((self.hi_raw as u16) & 0x0FFF) << 4)?;
            }
            _ => return Err(Ads1014Error::InvalidArgument),
        }
        self.inner.state.source = source;
        self.write_config()?;
        Ok(())
    }

    /// Release `ALERT/RDY` (`COMP_QUE` = 11). The two sources share the pin,
    /// so it is released whichever source is named.
    pub fn disable_interrupt(&mut self, _source: u8) -> Result<(), I2C::Error> {
        self.inner.state.source = 0;
        self.write_config()
    }

    /// Report whether the enabled source is asserted.
    ///
    /// The chip has no interrupt-status register. `alert_level_high` is the
    /// sampled `ALERT/RDY` level (`None` when no pin is wired): the pin counts
    /// as asserted when its level matches `COMP_POL`, and a latched comparator
    /// alert is then cleared by reading `CONVERSION`. Without a pin,
    /// `ADS1014_SOURCE_CONVERSION_READY` falls back to `CONFIG.OS` (single-shot
    /// only) and `ADS1014_SOURCE_COMPARATOR` returns 0.
    ///
    /// Returns the enabled `ADS1014_SOURCE_*` constant if asserted, else 0.
    pub fn poll_interrupt(&mut self, alert_level_high: Option<bool>) -> Result<u8, I2C::Error> {
        let s = self.inner.state;
        if s.source == 0 {
            return Ok(0);
        }
        if let Some(level) = alert_level_high {
            if level != (s.comp_pol == ADS1014_ACTIVE_HIGH) {
                return Ok(0);
            }
            if s.source == ADS1014_SOURCE_COMPARATOR && s.comp_lat {
                self.clear_alert()?;
            }
            return Ok(s.source);
        }
        if s.source == ADS1014_SOURCE_CONVERSION_READY && !s.continuous
            && self.inner.read_reg(REG_CONFIG)? & CFG_OS != 0
        {
            return Ok(ADS1014_SOURCE_CONVERSION_READY);
        }
        Ok(0)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x48;

    fn rd(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![(value >> 8) as u8, value as u8])
    }

    fn wr(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, (value >> 8) as u8, value as u8])
    }

    /// Transactions of one single-shot read: Config write, idle poll, result.
    fn shot(config: u16, result: u16) -> Vec<I2cTransaction> {
        vec![wr(REG_CONFIG, config), rd(REG_CONFIG, 0x8583), rd(REG_CONVERSION, result)]
    }

    #[test]
    fn minimal_single_shot_decoding() {
        let mut t = Vec::new();
        t.extend(shot(0x8583, 0x7FF0));
        t.extend(shot(0x8583, 0x8000));
        t.extend(shot(0x8583, 0xFFF0));
        t.extend(shot(0x8583, 0x3E80));
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1014Minimal::new(i2c.clone(), ADDR);
        let mut d = NoopDelay::new();
        assert_eq!(adc.read_raw(&mut d).unwrap(), 2047);
        assert_eq!(adc.read_raw(&mut d).unwrap(), -2048);
        assert_eq!(adc.read_raw(&mut d).unwrap(), -1);
        assert!((adc.read_voltage(&mut d).unwrap() - 1.0).abs() < 1e-6);
        i2c.done();
    }

    #[test]
    fn minimal_waits_while_converting() {
        let t = vec![wr(REG_CONFIG, 0x8583), rd(REG_CONFIG, 0x0583), rd(REG_CONFIG, 0x8583), rd(REG_CONVERSION, 0x1000)];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1014Minimal::new(i2c.clone(), ADDR);
        assert_eq!(adc.read_raw(&mut NoopDelay::new()).unwrap(), 256);
        i2c.done();
    }

    #[test]
    fn full_gain_and_data_rate() {
        let mut t = Vec::new();
        t.extend(shot(0x8000 | (5 << 9) | 0x0100 | (4 << 5) | 0x3, 0x4000));
        t.extend(shot(0x8000 | (5 << 9) | 0x0100 | (6 << 5) | 0x3, 0x0000));
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1014Full::new(i2c.clone(), ADDR);
        let mut d = NoopDelay::new();
        adc.set_gain(ADS1014_PGA_6_144).unwrap();
        assert_eq!(adc.get_gain(), 0);
        assert_eq!(adc.full_scale(), 6.144);
        assert!((adc.lsb() - 0.003).abs() < 1e-9);
        adc.set_gain(ADS1014_PGA_0_256).unwrap();
        assert!((adc.lsb() - 0.000125).abs() < 1e-9);
        assert!((adc.read_voltage(&mut d).unwrap() - 0.256 * 0x400 as f32 / 2048.0).abs() < 1e-6);
        assert!(adc.set_gain(6).is_err());
        assert!(adc.set_gain(7).is_err());
        adc.set_data_rate(ADS1014_DR_128).unwrap();
        assert_eq!(adc.data_rate_sps(), 128);
        adc.set_data_rate(ADS1014_DR_3300).unwrap();
        assert_eq!((adc.data_rate_sps(), adc.get_data_rate()), (3300, 6));
        assert!(adc.set_data_rate(7).is_err());
        adc.read_raw(&mut d).unwrap();
        i2c.done();
    }

    #[test]
    fn full_continuous_mode() {
        let t = vec![
            wr(REG_CONFIG, 0x0483),
            rd(REG_CONVERSION, 0x1000),
            rd(REG_CONVERSION, 0x1000),
            wr(REG_CONFIG, 0x0583),
            rd(REG_CONFIG, 0x0583),
            rd(REG_CONFIG, 0x8583),
            rd(REG_CONFIG, 0x8583),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1014Full::new(i2c.clone(), ADDR);
        adc.start_continuous().unwrap();
        assert_eq!(adc.read_continuous_raw().unwrap(), 256);
        assert!((adc.read_continuous().unwrap() - 0.256).abs() < 1e-6);
        adc.stop_continuous().unwrap();
        assert!(adc.is_converting().unwrap());
        assert!(!adc.is_converting().unwrap());
        assert_eq!(adc.read_config().unwrap(), 0x8583);
        i2c.done();
    }

    #[test]
    fn full_comparator_and_thresholds() {
        let t = vec![
            wr(REG_CONFIG, 0x059E),  // window, active high, latch, queue 4
            wr(REG_CONFIG, 0x059F),  // COMP_QUE = 11, other comparator bits kept
            wr(REG_LO_THRESH, (((-500i16) as u16) & 0x0FFF) << 4),
            wr(REG_HI_THRESH, 1000 << 4),
            rd(REG_LO_THRESH, (((-500i16) as u16) & 0x0FFF) << 4),
            rd(REG_HI_THRESH, 1000 << 4),
            wr(REG_LO_THRESH, 0x8000),
            wr(REG_HI_THRESH, 0x7FF0),
            rd(REG_CONVERSION, 0x0000),
            wr(REG_CONFIG, 0x8583),
            wr(REG_LO_THRESH, 0x8000),
            wr(REG_HI_THRESH, 0x7FFF),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1014Full::new(i2c.clone(), ADDR);
        adc.configure_comparator(ADS1014_COMP_WINDOW, ADS1014_ACTIVE_HIGH, true, ADS1014_QUEUE_4).unwrap();
        adc.disable_comparator().unwrap();
        assert!(adc.configure_comparator(0, 0, false, 3).is_err());
        adc.set_thresholds(-0.5, 1.0).unwrap();
        assert_eq!(adc.get_thresholds_raw().unwrap(), (-500, 1000));
        assert!(adc.set_thresholds(1.0, 1.0).is_err());
        assert!(adc.set_high_threshold_raw(2048).is_err());
        adc.set_thresholds(-10.0, 10.0).unwrap();
        adc.clear_alert().unwrap();
        adc.reset().unwrap();
        i2c.done();
    }

    #[test]
    fn full_interrupt_routing() {
        let lo = ((-100i16 as u16) & 0x0FFF) << 4;
        let hi = 100u16 << 4;
        let t = vec![
            // enable_interrupt(CONVERSION_READY): thresholds, then Config with COMP_QUE = 00
            wr(REG_HI_THRESH, 0x8000), wr(REG_LO_THRESH, 0x0000), wr(REG_CONFIG, 0x0580),
            rd(REG_CONFIG, 0x8580),                          // fallback poll: OS = 1 (idle)
            // high <= low as last set through the driver
            wr(REG_HI_THRESH, ((-5i16 as u16) & 0x0FFF) << 4), wr(REG_LO_THRESH, 10 << 4),
            // set_thresholds(-0.1, 0.1), configure_comparator(.., latch, QUEUE_1)
            wr(REG_LO_THRESH, lo), wr(REG_HI_THRESH, hi), wr(REG_CONFIG, 0x0584),
            // enable_interrupt(COMPARATOR): thresholds restored, then Config
            wr(REG_LO_THRESH, lo), wr(REG_HI_THRESH, hi), wr(REG_CONFIG, 0x0584),
            rd(REG_CONVERSION, 0x0000),                      // latched alert cleared by poll
            // disable_interrupt: COMP_QUE = 11
            wr(REG_CONFIG, 0x0587),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1014Full::new(i2c.clone(), ADDR);
        assert_eq!(adc.poll_interrupt(None).unwrap(), 0); // nothing enabled: no traffic
        adc.enable_interrupt(ADS1014_SOURCE_CONVERSION_READY).unwrap();
        assert_eq!(adc.poll_interrupt(None).unwrap(), ADS1014_SOURCE_CONVERSION_READY);
        assert_eq!(adc.poll_interrupt(Some(true)).unwrap(), 0); // active low: high = idle
        assert_eq!(adc.poll_interrupt(Some(false)).unwrap(), ADS1014_SOURCE_CONVERSION_READY);

        adc.set_high_threshold_raw(-5).unwrap();
        adc.set_low_threshold_raw(10).unwrap();
        assert!(adc.enable_interrupt(ADS1014_SOURCE_COMPARATOR).is_err());
        assert!(adc.enable_interrupt(0x04).is_err());

        adc.set_thresholds(-0.1, 0.1).unwrap();
        adc.configure_comparator(ADS1014_COMP_TRADITIONAL, ADS1014_ACTIVE_LOW, true, ADS1014_QUEUE_1).unwrap();
        adc.enable_interrupt(ADS1014_SOURCE_COMPARATOR).unwrap();
        assert_eq!(adc.poll_interrupt(Some(false)).unwrap(), ADS1014_SOURCE_COMPARATOR);
        adc.disable_interrupt(ADS1014_SOURCE_COMPARATOR).unwrap();
        assert_eq!(adc.poll_interrupt(Some(false)).unwrap(), 0);
        i2c.done();
    }
}
