//! ADS1114 — 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), PGA and comparator) (Texas Instruments).
//!
//! I²C ADC with four addresses (`0x48`–`0x4B`) via the `ADDR` strap and no ID
//! register. Registers are 16-bit big-endian behind a write-only Address
//! Pointer — see `specs/adc_dac/ads1114.md`. The implementation is shared with
//! its siblings in [`super::ads111x`].
//!
//! ## Single-shot conversions
//!
//! Every conversion takes a [`DelayNs`] to wait out the conversion time
//! (1/DR, 7.8 ms at the default 128 SPS) before reading the result.
//!
//! ## Interrupts
//!
//! Rust exposes only `poll_interrupt` (no callback subscription — polling is
//! always caller-managed in this crate's `no_std` drivers). Pass the sampled
//! `ALERT/RDY` level, or `None` to fall back to `CONFIG.OS`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use super::ads111x::{Ads111x, Ads111xError};
pub use super::ads111x::{SOURCE_CONVERSION_READY as ADS1114_SOURCE_CONVERSION_READY, SOURCE_COMPARATOR as ADS1114_SOURCE_COMPARATOR};

/// Default 7-bit I²C address (`ADDR` = GND). Valid range `0x48`–`0x4B`.
pub const ADS1114_I2C_ADDRESS: u8 = 0x48;
/// Error type of the ADS1114 driver's checked operations.
pub type Ads1114Error<E> = Ads111xError<E>;

/// Data-rate codes (`DR[2:0]`).
pub const ADS1114_DR_8: u8 = 0;
/// 16 SPS.
pub const ADS1114_DR_16: u8 = 1;
/// 32 SPS.
pub const ADS1114_DR_32: u8 = 2;
/// 64 SPS.
pub const ADS1114_DR_64: u8 = 3;
/// 128 SPS (default).
pub const ADS1114_DR_128: u8 = 4;
/// 250 SPS.
pub const ADS1114_DR_250: u8 = 5;
/// 475 SPS.
pub const ADS1114_DR_475: u8 = 6;
/// 860 SPS.
pub const ADS1114_DR_860: u8 = 7;

/// Full-scale range ±6.144 V (`PGA` = 0).
pub const ADS1114_PGA_6_144: u8 = 0;
/// Full-scale range ±4.096 V.
pub const ADS1114_PGA_4_096: u8 = 1;
/// Full-scale range ±2.048 V (default).
pub const ADS1114_PGA_2_048: u8 = 2;
/// Full-scale range ±1.024 V.
pub const ADS1114_PGA_1_024: u8 = 3;
/// Full-scale range ±0.512 V.
pub const ADS1114_PGA_0_512: u8 = 4;
/// Full-scale range ±0.256 V.
pub const ADS1114_PGA_0_256: u8 = 5;

/// Traditional comparator mode.
pub const ADS1114_COMP_TRADITIONAL: u8 = 0;
/// Window comparator mode.
pub const ADS1114_COMP_WINDOW: u8 = 1;
/// `ALERT/RDY` active low (default).
pub const ADS1114_ACTIVE_LOW: u8 = 0;
/// `ALERT/RDY` active high.
pub const ADS1114_ACTIVE_HIGH: u8 = 1;
/// Assert after 1 out-of-range conversion.
pub const ADS1114_QUEUE_1: u8 = 0;
/// Assert after 2 out-of-range conversions.
pub const ADS1114_QUEUE_2: u8 = 1;
/// Assert after 4 out-of-range conversions.
pub const ADS1114_QUEUE_4: u8 = 2;

/// ADS1114 — minimal interface: single-shot reads at ±2.048 V, 128 SPS.
pub struct Ads1114Minimal<I2C> {
    inner: Ads111x<I2C>,
}

impl<I2C: I2c> Ads1114Minimal<I2C> {
    /// Construct the driver. Makes no bus traffic — every conversion writes the
    /// full Config word. `addr` is `0x48`–`0x4B` per the `ADDR` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads111x::new(i2c, addr) }
    }

    /// Run a single-shot conversion of AIN0 − AIN1; returns the signed 16-bit code.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, Ads1114Error<I2C::Error>> {
        self.inner.convert(0, delay)
    }

    /// Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Ads1114Error<I2C::Error>> {
        Ok(self.read_raw(delay)? as f32 * 0.0000625)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

/// ADS1114 — full interface: extends [`Ads1114Minimal`] with gain, data rate, continuous conversion, status and reset, the comparator, thresholds and `ALERT/RDY` polling.
pub struct Ads1114Full<I2C> {
    inner: Ads111x<I2C>,
}

impl<I2C: I2c> Ads1114Full<I2C> {
    /// Construct the driver; makes no bus traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads111x::new(i2c, addr) }
    }

    /// Single-shot conversion of AIN0 − AIN1; signed 16-bit code.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, Ads1114Error<I2C::Error>> {
        self.inner.convert(0, delay)
    }

    /// Single-shot conversion in volts at the current gain.
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Ads1114Error<I2C::Error>> {
        Ok(self.read_raw(delay)? as f32 * self.inner.lsb())
    }

    /// Start continuous conversion at the current data rate (`MODE` ← 0).
    pub fn start_continuous(&mut self) -> Result<(), Ads1114Error<I2C::Error>> {
        self.inner.start_continuous(0)
    }

    /// Set the full-scale range (`ADS1114_PGA_*`, 0–5); thresholds are not rescaled.
    pub fn set_gain(&mut self, pga: u8) -> Result<(), Ads1114Error<I2C::Error>> {
        self.inner.set_gain(pga)
    }

    /// The configured `PGA` code.
    pub fn get_gain(&self) -> u8 {
        self.inner.get_gain()
    }

    /// ±FSR of the current gain in volts.
    pub fn full_scale(&self) -> f32 {
        self.inner.full_scale()
    }

    /// Size of one code in volts (`full_scale() / 32768`).
    pub fn lsb(&self) -> f32 {
        self.inner.lsb()
    }

    /// Set the data rate (`ADS1114_DR_*`, 0–7).
    pub fn set_data_rate(&mut self, dr: u8) -> Result<(), Ads1114Error<I2C::Error>> {
        self.inner.set_data_rate(dr)
    }

    /// The configured `DR` code.
    pub fn get_data_rate(&self) -> u8 {
        self.inner.get_data_rate()
    }

    /// The configured data rate in samples per second.
    pub fn data_rate_sps(&self) -> u16 {
        self.inner.data_rate_sps()
    }

    /// Read the most recent completed result without touching Config.
    pub fn read_continuous_raw(&mut self) -> Result<i16, I2C::Error> {
        self.inner.read_continuous_raw()
    }

    /// Read the most recent continuous result in volts.
    pub fn read_continuous(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.read_continuous_raw()? as f32 * self.inner.lsb())
    }

    /// Return to single-shot / power-down mode (`MODE` ← 1).
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.stop_continuous()
    }

    /// `true` while `CONFIG.OS` reads 0 (single-shot mode only).
    pub fn is_converting(&mut self) -> Result<bool, I2C::Error> {
        self.inner.is_converting()
    }

    /// The raw 16-bit `CONFIG` register.
    pub fn read_config(&mut self) -> Result<u16, I2C::Error> {
        self.inner.read_config()
    }

    /// Write the reset values back to `CONFIG`, `LO_THRESH` and `HI_THRESH` and clear the cached settings. Not an I²C general-call reset.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.reset(true)
    }

    /// Configure the comparator and route it to `ALERT/RDY`.
    ///
    /// Fails with `InvalidArgument` on an out-of-range argument or when the last
    /// thresholds have `high <= low`.
    pub fn configure_comparator(&mut self, mode: u8, polarity: u8, latch: bool, queue: u8) -> Result<(), Ads1114Error<I2C::Error>> {
        self.inner.configure_comparator(mode, polarity, latch, queue)
    }

    /// Set `COMP_QUE` to 11; `ALERT/RDY` becomes high-impedance.
    pub fn disable_comparator(&mut self) -> Result<(), I2C::Error> {
        self.inner.disable_comparator()
    }

    /// Write `LO_THRESH` (signed code, same scale as `CONVERSION`).
    pub fn set_low_threshold_raw(&mut self, raw: i16) -> Result<(), I2C::Error> {
        self.inner.set_low_threshold_raw(raw)
    }

    /// Write `HI_THRESH` (signed code, same scale as `CONVERSION`).
    pub fn set_high_threshold_raw(&mut self, raw: i16) -> Result<(), I2C::Error> {
        self.inner.set_high_threshold_raw(raw)
    }

    /// Write both thresholds from volts at the current gain; `InvalidArgument` if `high <= low`.
    pub fn set_thresholds(&mut self, low: f32, high: f32) -> Result<(), Ads1114Error<I2C::Error>> {
        self.inner.set_thresholds(low, high)
    }

    /// `(low, high)` thresholds read back, sign-extended.
    pub fn get_thresholds_raw(&mut self) -> Result<(i16, i16), I2C::Error> {
        self.inner.get_thresholds_raw()
    }

    /// Read `CONVERSION` to release a latched comparator output.
    pub fn clear_alert(&mut self) -> Result<(), I2C::Error> {
        self.inner.clear_alert()
    }

    /// Route one source (`ADS1114_SOURCE_*`) to `ALERT/RDY`; the other is disabled.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Ads1114Error<I2C::Error>> {
        self.inner.enable_interrupt(source)
    }

    /// Release `ALERT/RDY` (`COMP_QUE` ← 11).
    pub fn disable_interrupt(&mut self, _source: u8) -> Result<(), I2C::Error> {
        self.inner.disable_comparator()
    }

    /// The enabled source constant if `ALERT/RDY` is asserted, else 0.
    ///
    /// Pass the sampled `ALERT/RDY` level as `alert_pin_high`, or `None` to fall
    /// back to `CONFIG.OS` (conversion-ready, single-shot only).
    pub fn poll_interrupt(&mut self, alert_pin_high: Option<bool>) -> Result<u8, I2C::Error> {
        self.inner.poll_interrupt(alert_pin_high)
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

    #[test]
    fn single_shot_and_scaling() {
        let mut i2c = I2cMock::new(&[
            wr(1, 0x8583), rd(1, 0x8583), rd(0, 0x1000),
            wr(1, 0x8B83), rd(1, 0x8B83), rd(0, 0x1000),
        ]);
        let mut d = NoopDelay::new();
        let mut min = Ads1114Minimal::new(i2c.clone(), ADDR);
        assert_eq!(min.read_raw(&mut d).unwrap(), 0x1000);
        let mut full = Ads1114Full::new(i2c.clone(), ADDR);
        full.set_gain(ADS1114_PGA_0_256).unwrap();
        assert_eq!(full.lsb(), 0.256 / 32768.0);
        assert_eq!(full.read_raw(&mut d).unwrap(), 0x1000);
        i2c.done();
    }

    #[test]
    fn continuous_comparator_and_reset() {
        let mut i2c = I2cMock::new(&[
            wr(1, 0x0A83),                                 // continuous, ±0.256 V
            wr(2, 0xCE00), wr(3, 0x3200),                  // ∓0.1 V at 7.8125 µV/LSB = ∓12800 codes
            wr(1, 0x0A80),                                 // comparator enabled (queue 1)
            wr(1, 0x8583), wr(2, 0x8000), wr(3, 0x7FFF),
        ]);
        let mut full = Ads1114Full::new(i2c.clone(), ADDR);
        full.set_gain(ADS1114_PGA_0_256).unwrap();
        full.start_continuous().unwrap();
        full.set_thresholds(-0.1, 0.1).unwrap();
        full.configure_comparator(ADS1114_COMP_TRADITIONAL, ADS1114_ACTIVE_LOW, false, ADS1114_QUEUE_1).unwrap();
        full.reset().unwrap();
        assert_eq!(full.get_gain(), 2);
        i2c.done();
    }
}
