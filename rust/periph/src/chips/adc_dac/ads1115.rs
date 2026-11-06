//! ADS1115 — 16-bit ΔΣ ADC (four-channel multiplexer, PGA and comparator) (Texas Instruments).
//!
//! I²C ADC with four addresses (`0x48`–`0x4B`) via the `ADDR` strap and no ID
//! register. Registers are 16-bit big-endian behind a write-only Address
//! Pointer — see `specs/adc_dac/ads1115.md`. The implementation is shared with
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
pub use super::ads111x::{SOURCE_CONVERSION_READY as ADS1115_SOURCE_CONVERSION_READY, SOURCE_COMPARATOR as ADS1115_SOURCE_COMPARATOR};

/// Default 7-bit I²C address (`ADDR` = GND). Valid range `0x48`–`0x4B`.
pub const ADS1115_I2C_ADDRESS: u8 = 0x48;
/// Error type of the ADS1115 driver's checked operations.
pub type Ads1115Error<E> = Ads111xError<E>;

/// Data-rate codes (`DR[2:0]`).
pub const ADS1115_DR_8: u8 = 0;
/// 16 SPS.
pub const ADS1115_DR_16: u8 = 1;
/// 32 SPS.
pub const ADS1115_DR_32: u8 = 2;
/// 64 SPS.
pub const ADS1115_DR_64: u8 = 3;
/// 128 SPS (default).
pub const ADS1115_DR_128: u8 = 4;
/// 250 SPS.
pub const ADS1115_DR_250: u8 = 5;
/// 475 SPS.
pub const ADS1115_DR_475: u8 = 6;
/// 860 SPS.
pub const ADS1115_DR_860: u8 = 7;

/// Full-scale range ±6.144 V (`PGA` = 0).
pub const ADS1115_PGA_6_144: u8 = 0;
/// Full-scale range ±4.096 V.
pub const ADS1115_PGA_4_096: u8 = 1;
/// Full-scale range ±2.048 V (default).
pub const ADS1115_PGA_2_048: u8 = 2;
/// Full-scale range ±1.024 V.
pub const ADS1115_PGA_1_024: u8 = 3;
/// Full-scale range ±0.512 V.
pub const ADS1115_PGA_0_512: u8 = 4;
/// Full-scale range ±0.256 V.
pub const ADS1115_PGA_0_256: u8 = 5;

/// Differential pair AIN0 − AIN1.
pub const ADS1115_DIFF_0_1: u8 = 0;
/// Differential pair AIN0 − AIN3.
pub const ADS1115_DIFF_0_3: u8 = 1;
/// Differential pair AIN1 − AIN3.
pub const ADS1115_DIFF_1_3: u8 = 2;
/// Differential pair AIN2 − AIN3.
pub const ADS1115_DIFF_2_3: u8 = 3;
/// `MUX` code AIN0 against GND (`MUX` 4–7 are AIN0–AIN3 against GND).
pub const ADS1115_MUX_AIN0_GND: u8 = 4;

/// Traditional comparator mode.
pub const ADS1115_COMP_TRADITIONAL: u8 = 0;
/// Window comparator mode.
pub const ADS1115_COMP_WINDOW: u8 = 1;
/// `ALERT/RDY` active low (default).
pub const ADS1115_ACTIVE_LOW: u8 = 0;
/// `ALERT/RDY` active high.
pub const ADS1115_ACTIVE_HIGH: u8 = 1;
/// Assert after 1 out-of-range conversion.
pub const ADS1115_QUEUE_1: u8 = 0;
/// Assert after 2 out-of-range conversions.
pub const ADS1115_QUEUE_2: u8 = 1;
/// Assert after 4 out-of-range conversions.
pub const ADS1115_QUEUE_4: u8 = 2;

/// ADS1115 — minimal interface: single-shot reads at ±2.048 V, 128 SPS.
pub struct Ads1115Minimal<I2C> {
    inner: Ads111x<I2C>,
}

impl<I2C: I2c> Ads1115Minimal<I2C> {
    /// Construct the driver. Makes no bus traffic — every conversion writes the
    /// full Config word. `addr` is `0x48`–`0x4B` per the `ADDR` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads111x::new(i2c, addr) }
    }

    /// Run a single-shot conversion of AIN<channel> against GND (`channel` clamped to 0–3); returns the signed 16-bit code.
    pub fn read_raw<D: DelayNs>(&mut self, channel: u8, delay: &mut D) -> Result<i16, Ads1115Error<I2C::Error>> {
        self.inner.convert(4 + channel.min(3), delay)
    }

    /// Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
    pub fn read_voltage<D: DelayNs>(&mut self, channel: u8, delay: &mut D) -> Result<f32, Ads1115Error<I2C::Error>> {
        Ok(self.read_raw(channel, delay)? as f32 * 0.0000625)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

/// ADS1115 — full interface: extends [`Ads1115Minimal`] with gain, data rate, differential reads, continuous conversion, status and reset, the comparator, thresholds and `ALERT/RDY` polling.
pub struct Ads1115Full<I2C> {
    inner: Ads111x<I2C>,
}

impl<I2C: I2c> Ads1115Full<I2C> {
    /// Construct the driver; makes no bus traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads111x::new(i2c, addr) }
    }

    /// Single-shot conversion of AIN<channel> against GND; signed 16-bit code.
    pub fn read_raw<D: DelayNs>(&mut self, channel: u8, delay: &mut D) -> Result<i16, Ads1115Error<I2C::Error>> {
        self.inner.convert(4 + channel.min(3), delay)
    }

    /// Single-shot conversion in volts at the current gain.
    pub fn read_voltage<D: DelayNs>(&mut self, channel: u8, delay: &mut D) -> Result<f32, Ads1115Error<I2C::Error>> {
        Ok(self.read_raw(channel, delay)? as f32 * self.inner.lsb())
    }

    /// Single-shot signed differential conversion (`pair` = `ADS1115_DIFF_*`, 0–3).
    pub fn read_differential_raw<D: DelayNs>(&mut self, pair: u8, delay: &mut D) -> Result<i16, Ads1115Error<I2C::Error>> {
        if pair > 3 {
            return Err(Ads111xError::InvalidArgument);
        }
        self.inner.convert(pair, delay)
    }

    /// Single-shot differential conversion in volts (`raw × lsb()`).
    pub fn read_differential<D: DelayNs>(&mut self, pair: u8, delay: &mut D) -> Result<f32, Ads1115Error<I2C::Error>> {
        Ok(self.read_differential_raw(pair, delay)? as f32 * self.inner.lsb())
    }

    /// Start continuous conversion on any `MUX` code (0–7; `ADS1115_MUX_AIN0_GND` … `+3` single-ended).
    pub fn start_continuous(&mut self, mux: u8) -> Result<(), Ads1115Error<I2C::Error>> {
        self.inner.start_continuous(mux)
    }

    /// Set the full-scale range (`ADS1115_PGA_*`, 0–5); thresholds are not rescaled.
    pub fn set_gain(&mut self, pga: u8) -> Result<(), Ads1115Error<I2C::Error>> {
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

    /// Set the data rate (`ADS1115_DR_*`, 0–7).
    pub fn set_data_rate(&mut self, dr: u8) -> Result<(), Ads1115Error<I2C::Error>> {
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
    pub fn configure_comparator(&mut self, mode: u8, polarity: u8, latch: bool, queue: u8) -> Result<(), Ads1115Error<I2C::Error>> {
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
    pub fn set_thresholds(&mut self, low: f32, high: f32) -> Result<(), Ads1115Error<I2C::Error>> {
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

    /// Route one source (`ADS1115_SOURCE_*`) to `ALERT/RDY`; the other is disabled.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Ads1115Error<I2C::Error>> {
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
    const CONV: u8 = 0x00;
    const CONFIG: u8 = 0x01;
    const LO: u8 = 0x02;
    const HI: u8 = 0x03;

    fn rd(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![(value >> 8) as u8, value as u8])
    }

    fn wr(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, (value >> 8) as u8, value as u8])
    }

    /// One single-shot conversion: Config write, OS poll (already idle), result read.
    fn shot(config: u16, raw: u16) -> Vec<I2cTransaction> {
        vec![wr(CONFIG, config), rd(CONFIG, config & 0x7FFF | 0x8000), rd(CONV, raw)]
    }

    #[test]
    fn minimal_single_ended() {
        let mut tr = shot(0xC583, 0x4000);
        tr.extend(shot(0xE583, 0xFFFF));
        tr.extend(shot(0xF583, 0x0000));
        tr.extend(shot(0xC583, 0x4000));
        let mut i2c = I2cMock::new(&tr);
        let mut adc = Ads1115Minimal::new(i2c.clone(), ADDR);
        let mut d = NoopDelay::new();
        assert_eq!(adc.read_raw(0, &mut d).unwrap(), 0x4000);
        assert_eq!(adc.read_raw(2, &mut d).unwrap(), -1);
        assert_eq!(adc.read_raw(9, &mut d).unwrap(), 0);       // clamps to channel 3
        assert_eq!(adc.read_voltage(0, &mut d).unwrap(), 0x4000 as f32 * 0.0000625);
        i2c.done();
    }

    #[test]
    fn full_gain_rate_and_differential() {
        let mut tr = shot(0xD383, 0x2000);           // AIN1, ±4.096 V
        tr.extend(shot(0xC3E3, 0x0000));             // AIN0, 860 SPS
        tr.extend(shot(0xB3E3, 0x2000));             // AIN2 − AIN3
        let mut i2c = I2cMock::new(&tr);
        let mut adc = Ads1115Full::new(i2c.clone(), ADDR);
        let mut d = NoopDelay::new();
        adc.set_gain(ADS1115_PGA_4_096).unwrap();
        assert_eq!(adc.full_scale(), 4.096);
        assert_eq!(adc.lsb(), 4.096 / 32768.0);
        assert_eq!(adc.read_voltage(1, &mut d).unwrap(), 0x2000 as f32 * (4.096 / 32768.0));
        assert!(adc.set_gain(6).is_err());
        adc.set_data_rate(ADS1115_DR_860).unwrap();
        assert_eq!((adc.get_data_rate(), adc.data_rate_sps()), (7, 860));
        adc.read_raw(0, &mut d).unwrap();
        assert_eq!(adc.read_differential_raw(ADS1115_DIFF_2_3, &mut d).unwrap(), 0x2000);
        assert!(adc.read_differential_raw(4, &mut d).is_err());
        i2c.done();
    }

    #[test]
    fn continuous_and_status() {
        let mut i2c = I2cMock::new(&[
            wr(CONFIG, 0x54E3),
            rd(CONV, 0x0100),
            wr(CONFIG, 0x55E3),
            rd(CONFIG, 0x0583), rd(CONFIG, 0x8583), rd(CONFIG, 0x8583),
        ]);
        let mut adc = Ads1115Full::new(i2c.clone(), ADDR);
        adc.set_data_rate(ADS1115_DR_860).unwrap();
        adc.start_continuous(5).unwrap();
        assert_eq!(adc.read_continuous_raw().unwrap(), 256);
        adc.stop_continuous().unwrap();
        assert!(adc.is_converting().unwrap());
        assert!(!adc.is_converting().unwrap());
        assert_eq!(adc.read_config().unwrap(), 0x8583);
        i2c.done();
    }

    #[test]
    fn thresholds_and_comparator() {
        let mut i2c = I2cMock::new(&[
            wr(LO, 8000), wr(HI, 0x7FFF),                 // 0.5 V / 3.0 V (clamped)
            wr(HI, 0xFF9C), wr(LO, 0xFF38),
            rd(LO, 0xFF38), rd(HI, 0xFF9C),
            wr(LO, 8000), wr(HI, 24000),
            wr(CONFIG, 0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0001),
            wr(CONFIG, 0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0003),
            rd(CONV, 0),
        ]);
        let mut adc = Ads1115Full::new(i2c.clone(), ADDR);
        adc.set_thresholds(0.5, 3.0).unwrap();
        adc.set_high_threshold_raw(-100).unwrap();
        adc.set_low_threshold_raw(-200).unwrap();
        assert_eq!(adc.get_thresholds_raw().unwrap(), (-200, -100));
        assert!(adc.set_thresholds(1.0, 1.0).is_err());
        adc.set_thresholds(0.5, 1.5).unwrap();
        adc.configure_comparator(ADS1115_COMP_WINDOW, ADS1115_ACTIVE_HIGH, true, ADS1115_QUEUE_2).unwrap();
        adc.disable_comparator().unwrap();
        adc.clear_alert().unwrap();
        i2c.done();
    }

    #[test]
    fn interrupt_routing_and_polling() {
        let mut i2c = I2cMock::new(&[
            wr(HI, 0x8000), wr(LO, 0x0000), wr(CONFIG, 0x0580),
            rd(CONFIG, 0x8583), rd(CONFIG, 0x0583),
            wr(CONFIG, 0x0583),
            wr(CONFIG, 0x8583), wr(LO, 0x8000), wr(HI, 0x7FFF),
        ]);
        let mut adc = Ads1115Full::new(i2c.clone(), ADDR);
        adc.enable_interrupt(ADS1115_SOURCE_CONVERSION_READY).unwrap();
        assert_eq!(adc.poll_interrupt(None).unwrap(), ADS1115_SOURCE_CONVERSION_READY);
        assert_eq!(adc.poll_interrupt(None).unwrap(), 0);
        assert_eq!(adc.poll_interrupt(Some(false)).unwrap(), ADS1115_SOURCE_CONVERSION_READY);
        assert_eq!(adc.poll_interrupt(Some(true)).unwrap(), 0);
        adc.disable_interrupt(ADS1115_SOURCE_CONVERSION_READY).unwrap();
        assert_eq!(adc.poll_interrupt(Some(false)).unwrap(), 0);
        assert!(adc.enable_interrupt(0x80).is_err());
        adc.reset().unwrap();
        i2c.done();
    }
}
