//! ADS1113 — 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V) (Texas Instruments).
//!
//! I²C ADC with four addresses (`0x48`–`0x4B`) via the `ADDR` strap and no ID
//! register. Registers are 16-bit big-endian behind a write-only Address
//! Pointer — see `specs/adc_dac/ads1113.md`. The implementation is shared with
//! its siblings in [`super::ads111x`].
//!
//! ## Single-shot conversions
//!
//! Every conversion takes a [`DelayNs`] to wait out the conversion time
//! (1/DR, 7.8 ms at the default 128 SPS) before reading the result.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use super::ads111x::{Ads111x, Ads111xError};
pub use super::ads111x::{SOURCE_CONVERSION_READY as ADS1113_SOURCE_CONVERSION_READY, SOURCE_COMPARATOR as ADS1113_SOURCE_COMPARATOR};

/// Default 7-bit I²C address (`ADDR` = GND). Valid range `0x48`–`0x4B`.
pub const ADS1113_I2C_ADDRESS: u8 = 0x48;
/// Error type of the ADS1113 driver's checked operations.
pub type Ads1113Error<E> = Ads111xError<E>;

/// Data-rate codes (`DR[2:0]`).
pub const ADS1113_DR_8: u8 = 0;
/// 16 SPS.
pub const ADS1113_DR_16: u8 = 1;
/// 32 SPS.
pub const ADS1113_DR_32: u8 = 2;
/// 64 SPS.
pub const ADS1113_DR_64: u8 = 3;
/// 128 SPS (default).
pub const ADS1113_DR_128: u8 = 4;
/// 250 SPS.
pub const ADS1113_DR_250: u8 = 5;
/// 475 SPS.
pub const ADS1113_DR_475: u8 = 6;
/// 860 SPS.
pub const ADS1113_DR_860: u8 = 7;

/// ADS1113 — minimal interface: single-shot reads at ±2.048 V, 128 SPS.
pub struct Ads1113Minimal<I2C> {
    inner: Ads111x<I2C>,
}

impl<I2C: I2c> Ads1113Minimal<I2C> {
    /// Construct the driver. Makes no bus traffic — every conversion writes the
    /// full Config word. `addr` is `0x48`–`0x4B` per the `ADDR` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads111x::new(i2c, addr) }
    }

    /// Run a single-shot conversion of AIN0 − AIN1; returns the signed 16-bit code.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, Ads1113Error<I2C::Error>> {
        self.inner.convert(0, delay)
    }

    /// Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Ads1113Error<I2C::Error>> {
        Ok(self.read_raw(delay)? as f32 * 0.0000625)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

/// ADS1113 — full interface: extends [`Ads1113Minimal`] with data rate, continuous conversion, status and reset.
pub struct Ads1113Full<I2C> {
    inner: Ads111x<I2C>,
}

impl<I2C: I2c> Ads1113Full<I2C> {
    /// Construct the driver; makes no bus traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads111x::new(i2c, addr) }
    }

    /// Single-shot conversion of AIN0 − AIN1; signed 16-bit code.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, Ads1113Error<I2C::Error>> {
        self.inner.convert(0, delay)
    }

    /// Single-shot conversion in volts at the fixed ±2.048 V range.
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Ads1113Error<I2C::Error>> {
        Ok(self.read_raw(delay)? as f32 * self.inner.lsb())
    }

    /// Start continuous conversion at the current data rate (`MODE` ← 0).
    pub fn start_continuous(&mut self) -> Result<(), Ads1113Error<I2C::Error>> {
        self.inner.start_continuous(0)
    }

    /// ±FSR of the current gain in volts.
    pub fn full_scale(&self) -> f32 {
        self.inner.full_scale()
    }

    /// Size of one code in volts (`full_scale() / 32768`).
    pub fn lsb(&self) -> f32 {
        self.inner.lsb()
    }

    /// Set the data rate (`ADS1113_DR_*`, 0–7).
    pub fn set_data_rate(&mut self, dr: u8) -> Result<(), Ads1113Error<I2C::Error>> {
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

    /// Write the reset values back to `CONFIG` and clear the cached settings. Not an I²C general-call reset.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.reset(false)
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
    fn single_shot_voltage() {
        let mut i2c = I2cMock::new(&[wr(1, 0x8583), rd(1, 0x8583), rd(0, 0x0200)]);
        let mut d = NoopDelay::new();
        let mut adc = Ads1113Minimal::new(i2c.clone(), ADDR);
        assert_eq!(adc.read_voltage(&mut d).unwrap(), 0x0200 as f32 * 0.0000625);
        i2c.done();
    }

    #[test]
    fn fixed_range_continuous_and_reset() {
        let mut i2c = I2cMock::new(&[wr(1, 0x0403), wr(1, 0x8583)]);
        let mut adc = Ads1113Full::new(i2c.clone(), ADDR);
        assert_eq!((adc.full_scale(), adc.lsb()), (2.048, 2.048 / 32768.0));
        adc.set_data_rate(ADS1113_DR_8).unwrap();
        adc.start_continuous().unwrap();
        adc.reset().unwrap();
        assert_eq!(adc.data_rate_sps(), 128);
        i2c.done();
    }
}
