//! ADS1013 — 12-bit I²C ADC with a fixed ±2.048 V range (Texas Instruments).
//!
//! Smallest member of the ADS101x family: one input (`AIN0` − `AIN1`), no PGA,
//! no multiplexer, no comparator and no `ALERT/RDY` pin. Data rates of
//! 128–3300 SPS, single-shot or continuous conversion. The chip has no ID
//! register, so construction performs no I²C traffic. Four selectable
//! addresses (`0x48`–`0x4B`) via the `ADDR` strap.
//!
//! Registers are 16-bit, big-endian, addressed through a persistent Address
//! Pointer; Config writes are always whole-word — see
//! `specs/adc_dac/ads1013.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{self, to_signed};

const REG_CONVERSION: u8 = 0x00;
const REG_CONFIG: u8 = 0x01;

const CONFIG_RESET: u16 = 0x8583;

const CFG_OS: u16 = 0x8000;
const CFG_MODE_SINGLE: u16 = 0x0100;
// Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
const CFG_RESERVED: u16 = 0x0403;

const SPS: [u16; 7] = [128, 250, 490, 920, 1600, 2400, 3300];
const LSB: f32 = 0.001;

/// Default 7-bit I²C address (`ADDR` = GND). Valid range `0x48`–`0x4B`.
pub const ADS1013_I2C_ADDRESS: u8 = 0x48;

/// Data rate 128 SPS.
pub const ADS1013_DR_128: u8 = 0;
/// Data rate 250 SPS.
pub const ADS1013_DR_250: u8 = 1;
/// Data rate 490 SPS.
pub const ADS1013_DR_490: u8 = 2;
/// Data rate 920 SPS.
pub const ADS1013_DR_920: u8 = 3;
/// Data rate 1600 SPS (default).
pub const ADS1013_DR_1600: u8 = 4;
/// Data rate 2400 SPS.
pub const ADS1013_DR_2400: u8 = 5;
/// Data rate 3300 SPS.
pub const ADS1013_DR_3300: u8 = 6;

/// Errors from the ADS1013 driver's checked operations.
#[derive(Debug)]
pub enum Ads1013Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// An argument outside its documented range.
    InvalidArgument,
}

impl<E> From<E> for Ads1013Error<E> {
    fn from(e: E) -> Self {
        Ads1013Error::Bus(e)
    }
}

/// ADS1013 — minimal interface: single-shot reads of `AIN0` − `AIN1`.
pub struct Ads1013Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    dr: u8,
    continuous: bool,
}

impl<I2C: I2c> Ads1013Minimal<I2C> {
    /// Construct the driver. Performs no I²C traffic (the chip has no ID
    /// register). Defaults: 1600 SPS, single-shot.
    ///
    /// `addr` is `0x48`–`0x4B` per the board's `ADDR` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, dr: ADS1013_DR_1600, continuous: false }
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn write_reg(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[(value >> 8) as u8, value as u8])
    }

    fn config_word(&self, os: bool, continuous: bool) -> u16 {
        let mut word = CFG_RESERVED | ((self.dr as u16) << 5);
        if os {
            word |= CFG_OS;
        }
        if !continuous {
            word |= CFG_MODE_SINGLE;
        }
        word
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
        let word = self.config_word(true, false);
        self.write_reg(REG_CONFIG, word)?;
        self.continuous = false;
        for _ in 0..100 {
            if self.read_reg(REG_CONFIG)? & CFG_OS != 0 {
                break;
            }
            delay.delay_ms(1);
        }
        self.read_conversion()
    }

    /// Run one single-shot conversion and return the voltage `AIN0` − `AIN1`
    /// in volts (`raw × 0.001`).
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        Ok(self.read_raw(delay)? as f32 * LSB)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// ADS1013 — full interface: extends [`Ads1013Minimal`] with data-rate
/// selection and continuous conversion.
pub struct Ads1013Full<I2C> {
    inner: Ads1013Minimal<I2C>,
}

impl<I2C: I2c> Ads1013Full<I2C> {
    /// Construct the driver; performs no I²C traffic.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Ads1013Minimal::new(i2c, addr) }
    }

    /// Single-shot read of the 12-bit code. Delegates to
    /// [`Ads1013Minimal::read_raw`].
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, I2C::Error> {
        self.inner.read_raw(delay)
    }

    /// Single-shot read in volts. Delegates to [`Ads1013Minimal::read_voltage`].
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        self.inner.read_voltage(delay)
    }

    fn write_config(&mut self) -> Result<(), I2C::Error> {
        let word = self.inner.config_word(false, self.inner.continuous);
        self.inner.write_reg(REG_CONFIG, word)
    }

    /// Fixed full-scale range: always 2.048 (±V).
    pub fn full_scale(&self) -> f32 {
        2.048
    }

    /// Size of one conversion code: always 0.001 V.
    pub fn lsb(&self) -> f32 {
        LSB
    }

    /// Select the conversion data rate (`ADS1013_DR_*`). Values above 6 are
    /// rejected (7 aliases 3300 SPS).
    pub fn set_data_rate(&mut self, dr: u8) -> Result<(), Ads1013Error<I2C::Error>> {
        if dr > ADS1013_DR_3300 {
            return Err(Ads1013Error::InvalidArgument);
        }
        self.inner.dr = dr;
        if self.inner.continuous {
            self.write_config()?;
        }
        Ok(())
    }

    /// Currently configured `DR` code (0–6).
    pub fn get_data_rate(&self) -> u8 {
        self.inner.dr
    }

    /// Samples per second of the current setting.
    pub fn data_rate_sps(&self) -> u16 {
        SPS[self.inner.dr as usize]
    }

    /// Start continuous conversion (`MODE` = 0) at the current data rate.
    pub fn start_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.continuous = true;
        self.write_config()
    }

    /// Read the most recent completed result without touching Config (valid
    /// at least 1/DR after [`start_continuous`](Self::start_continuous)).
    pub fn read_continuous_raw(&mut self) -> Result<i16, I2C::Error> {
        self.inner.read_conversion()
    }

    /// Read the most recent completed result in volts: `raw × lsb()`.
    pub fn read_continuous(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.read_conversion()? as f32 * LSB)
    }

    /// Stop continuous conversion (`MODE` = 1, single-shot / power-down).
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.continuous = false;
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

    /// Write `CONFIG` = 0x8583 back and clear the cached settings. Not an I²C
    /// general-call reset.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONFIG, CONFIG_RESET)?;
        self.inner.dr = ADS1013_DR_1600;
        self.inner.continuous = false;
        Ok(())
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
        let mut adc = Ads1013Minimal::new(i2c.clone(), ADDR);
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
        let mut adc = Ads1013Minimal::new(i2c.clone(), ADDR);
        assert_eq!(adc.read_raw(&mut NoopDelay::new()).unwrap(), 256);
        i2c.done();
    }

    #[test]
    fn full_data_rate_and_fixed_range() {
        let t = shot(0x8000 | 0x0403 | 0x0100 | (6 << 5), 0x0000);
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1013Full::new(i2c.clone(), ADDR);
        assert_eq!(adc.full_scale(), 2.048);
        assert_eq!(adc.lsb(), 0.001);
        adc.set_data_rate(ADS1013_DR_128).unwrap();
        assert_eq!((adc.data_rate_sps(), adc.get_data_rate()), (128, 0));
        adc.set_data_rate(ADS1013_DR_3300).unwrap();
        assert_eq!((adc.data_rate_sps(), adc.get_data_rate()), (3300, 6));
        assert!(adc.set_data_rate(7).is_err());
        adc.read_raw(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn full_continuous_mode_and_reset() {
        let t = vec![
            wr(REG_CONFIG, 0x0483),
            rd(REG_CONVERSION, 0x1000),
            rd(REG_CONVERSION, 0x1000),
            wr(REG_CONFIG, 0x0583),
            rd(REG_CONFIG, 0x0583),
            rd(REG_CONFIG, 0x8583),
            rd(REG_CONFIG, 0x8583),
            wr(REG_CONFIG, 0x8583),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads1013Full::new(i2c.clone(), ADDR);
        adc.start_continuous().unwrap();
        assert_eq!(adc.read_continuous_raw().unwrap(), 256);
        assert!((adc.read_continuous().unwrap() - 0.256).abs() < 1e-6);
        adc.stop_continuous().unwrap();
        assert!(adc.is_converting().unwrap());
        assert!(!adc.is_converting().unwrap());
        assert_eq!(adc.read_config().unwrap(), 0x8583);
        adc.set_data_rate(ADS1013_DR_128).unwrap();
        adc.reset().unwrap();
        assert_eq!(adc.get_data_rate(), ADS1013_DR_1600);
        i2c.done();
    }
}
