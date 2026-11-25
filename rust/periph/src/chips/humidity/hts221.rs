//! HTS221 — capacitive digital relative-humidity and temperature sensor (STMicroelectronics).
//!
//! Fixed I²C address `0x5F`. The chip is factory calibrated: each device stores two-point
//! calibration coefficients in registers `0x30`–`0x3F` that `init()` reads once; the raw 16-bit
//! outputs are converted by linear interpolation. Multi-byte transfers set the sub-address MSb
//! (register address auto-increment), which the driver ORs into the register address itself.
//! Construction does no bus access; `init()` reads the calibration and writes `CTRL_REG1 = 0x84`
//! (active, block data update, one-shot).
//!
//! The Full driver exposes only `poll_interrupt()` for the DRDY data-ready signal: the caller is
//! responsible for wiring it into an ISR or a polling loop.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_WHO_AM_I: u8 = 0x0F;
const REG_AV_CONF: u8 = 0x10;
const REG_CTRL_REG1: u8 = 0x20;
const REG_CTRL_REG2: u8 = 0x21;
const REG_CTRL_REG3: u8 = 0x22;
const REG_STATUS: u8 = 0x27;
const REG_HUMIDITY_OUT: u8 = 0x28;
const REG_CALIB: u8 = 0x30;

const AUTO_INC: u8 = 0x80;
const CTRL_REG1_DEFAULT: u8 = 0x84;
const CTRL1_PD: u8 = 0x80;
const CTRL1_BDU: u8 = 0x04;
const CTRL1_ODR_MASK: u8 = 0x03;
const CTRL2_BOOT: u8 = 0x80;
const CTRL2_HEATER: u8 = 0x02;
const CTRL2_ONE_SHOT: u8 = 0x01;
const STATUS_BOTH: u8 = 0x03;
const TIMEOUT_MS: u32 = 100;

/// `STATUS_REG` flag: humidity data available.
pub const STATUS_H_DA: u8 = 0x02;
/// `STATUS_REG` flag: temperature data available.
pub const STATUS_T_DA: u8 = 0x01;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// The conversion or reboot did not finish within 100 ms.
    Timeout,
    /// Unsupported argument (output data rate or averaging).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// Decoded factory calibration coefficients.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Calibration {
    /// Humidity calibration point 0 in %rH.
    pub h0_rh: f32,
    /// Humidity calibration point 1 in %rH.
    pub h1_rh: f32,
    /// Temperature calibration point 0 in °C.
    pub t0_degc: f32,
    /// Temperature calibration point 1 in °C.
    pub t1_degc: f32,
    /// Humidity ADC output at point 0.
    pub h0_t0_out: i16,
    /// Humidity ADC output at point 1.
    pub h1_t0_out: i16,
    /// Temperature ADC output at point 0.
    pub t0_out: i16,
    /// Temperature ADC output at point 1.
    pub t1_out: i16,
}

impl Calibration {
    fn decode(c: &[u8; 16]) -> Self {
        let t0 = (((c[5] & 0x03) as u16) << 8) | c[2] as u16;
        let t1 = (((c[5] & 0x0C) as u16) << 6) | c[3] as u16;
        Self {
            h0_rh: c[0] as f32 / 2.0,
            h1_rh: c[1] as f32 / 2.0,
            t0_degc: t0 as f32 / 8.0,
            t1_degc: t1 as f32 / 8.0,
            h0_t0_out: i16::from_le_bytes([c[6], c[7]]),
            h1_t0_out: i16::from_le_bytes([c[10], c[11]]),
            t0_out: i16::from_le_bytes([c[12], c[13]]),
            t1_out: i16::from_le_bytes([c[14], c[15]]),
        }
    }

    fn humidity(&self, raw: i16) -> f32 {
        let span = self.h1_t0_out as f32 - self.h0_t0_out as f32;
        if span == 0.0 {
            return 0.0;
        }
        let v = (raw as f32 - self.h0_t0_out as f32) * (self.h1_rh - self.h0_rh) / span + self.h0_rh;
        v.clamp(0.0, 100.0)
    }

    fn temperature(&self, raw: i16) -> f32 {
        let span = self.t1_out as f32 - self.t0_out as f32;
        if span == 0.0 {
            return 0.0;
        }
        (raw as f32 - self.t0_out as f32) * (self.t1_degc - self.t0_degc) / span + self.t0_degc
    }
}

/// HTS221 minimal driver — one calibrated one-shot measurement per `read()`.
pub struct HTS221Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    cal: Calibration,
}

impl<I2C: I2c> HTS221Minimal<I2C> {
    /// Create a new `HTS221Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x5F`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self {
            i2c,
            addr,
            cal: Calibration { h0_rh: 0.0, h1_rh: 0.0, t0_degc: 0.0, t1_degc: 0.0, h0_t0_out: 0, h1_t0_out: 0, t0_out: 0, t1_out: 0 },
        }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Read the calibration block and start one-shot mode (`CTRL_REG1 = 0x84`).
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.read_calibration_block()?;
        self.write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[reg, value])?;
        Ok(())
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, Error<I2C::Error>> {
        let mut d = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut d)?;
        Ok(d[0])
    }

    fn read_burst(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        self.i2c.write_read(self.addr, &[reg | AUTO_INC], buf)?;
        Ok(())
    }

    fn read_calibration_block(&mut self) -> Result<(), Error<I2C::Error>> {
        let mut c = [0u8; 16];
        self.read_burst(REG_CALIB, &mut c)?;
        self.cal = Calibration::decode(&c);
        Ok(())
    }

    fn read_raw_pair(&mut self) -> Result<(i16, i16), Error<I2C::Error>> {
        let mut d = [0u8; 4];
        self.read_burst(REG_HUMIDITY_OUT, &mut d)?;
        Ok((i16::from_le_bytes([d[0], d[1]]), i16::from_le_bytes([d[2], d[3]])))
    }

    fn one_shot_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(i16, i16), Error<I2C::Error>> {
        let ctrl2 = self.read_reg(REG_CTRL_REG2)?;
        self.write_reg(REG_CTRL_REG2, ctrl2 | CTRL2_ONE_SHOT)?;
        for _ in 0..TIMEOUT_MS {
            if self.read_reg(REG_STATUS)? & STATUS_BOTH == STATUS_BOTH {
                return self.read_raw_pair();
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    /// Measure temperature and relative humidity (one-shot).
    ///
    /// Returns `(temperature_c, humidity_pct)`, humidity clamped to 0–100 %rH;
    /// [`Error::Timeout`] if the conversion does not finish within 100 ms.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let (h, t) = self.one_shot_raw(delay)?;
        Ok((self.cal.temperature(t), self.cal.humidity(h)))
    }
}

/// HTS221 full driver — single values, raw outputs, output data rate, averaging, power modes, heater,
/// DRDY pin, calibration access and dew point.
///
/// Owns a [`HTS221Minimal`] and delegates `init` and `read`.
pub struct HTS221Full<I2C> {
    inner: HTS221Minimal<I2C>,
}

impl<I2C: I2c> HTS221Full<I2C> {
    /// Create a new `HTS221Full`; same arguments as [`HTS221Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: HTS221Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Read the calibration and start one-shot mode; see [`HTS221Minimal::init`].
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.init()
    }

    /// Measure temperature and humidity; see [`HTS221Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Run one one-shot measurement and return the relative humidity in %rH (clamped to 0–100).
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (h, _) = self.inner.one_shot_raw(delay)?;
        Ok(self.inner.cal.humidity(h))
    }

    /// Run one one-shot measurement and return the temperature in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (_, t) = self.inner.one_shot_raw(delay)?;
        Ok(self.inner.cal.temperature(t))
    }

    /// Run one one-shot measurement and return the signed 16-bit raw humidity output.
    pub fn read_raw_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, Error<I2C::Error>> {
        Ok(self.inner.one_shot_raw(delay)?.0)
    }

    /// Run one one-shot measurement and return the signed 16-bit raw temperature output.
    pub fn read_raw_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<i16, Error<I2C::Error>> {
        Ok(self.inner.one_shot_raw(delay)?.1)
    }

    /// Read the latest sample without starting a conversion (continuous output data rate).
    ///
    /// Returns `(temperature_c, humidity_pct)`.
    pub fn read_continuous(&mut self) -> Result<(f32, f32), Error<I2C::Error>> {
        let (h, t) = self.inner.read_raw_pair()?;
        Ok((self.inner.cal.temperature(t), self.inner.cal.humidity(h)))
    }

    /// Measure and return the dew point in °C (Magnus formula over one measurement).
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, rh) = self.inner.read(delay)?;
        let rh = if rh < 0.01 { 0.01 } else { rh };
        let g = libm::logf(rh / 100.0) + 17.62 * t / (243.12 + t);
        Ok(243.12 * g / (17.62 - g))
    }

    /// Set the output data rate: `0.0` (one-shot), `1.0`, `7.0` or `12.5` Hz.
    ///
    /// [`Error::InvalidArgument`] for any other value (nothing is written).
    pub fn set_output_data_rate(&mut self, hz: f32) -> Result<(), Error<I2C::Error>> {
        let code = if hz == 0.0 {
            0
        } else if hz == 1.0 {
            1
        } else if hz == 7.0 {
            2
        } else if hz == 12.5 {
            3
        } else {
            return Err(Error::InvalidArgument);
        };
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, (ctrl1 & !CTRL1_ODR_MASK) | code)?;
        // Clear flags left over from continuous sampling so the next one-shot wait does not return early.
        self.inner.read_raw_pair()?;
        Ok(())
    }

    /// Read the output data rate in Hz (`0.0`, `1.0`, `7.0` or `12.5`).
    pub fn get_output_data_rate(&mut self) -> Result<f32, Error<I2C::Error>> {
        const HZ: [f32; 4] = [0.0, 1.0, 7.0, 12.5];
        Ok(HZ[(self.inner.read_reg(REG_CTRL_REG1)? & CTRL1_ODR_MASK) as usize])
    }

    /// Set the internal averaging: temperature 2…256 and humidity 4…512 samples (powers of two).
    ///
    /// [`Error::InvalidArgument`] for any other value (nothing is written).
    pub fn set_averaging(&mut self, temperature_samples: u16, humidity_samples: u16) -> Result<(), Error<I2C::Error>> {
        let t = log2_index(temperature_samples, 2, 256).ok_or(Error::InvalidArgument)?;
        let h = log2_index(humidity_samples, 4, 512).ok_or(Error::InvalidArgument)?;
        self.inner.write_reg(REG_AV_CONF, (t << 3) | h)
    }

    /// Read the internal averaging as `(temperature_samples, humidity_samples)`.
    pub fn get_averaging(&mut self) -> Result<(u16, u16), Error<I2C::Error>> {
        let v = self.inner.read_reg(REG_AV_CONF)?;
        Ok((2u16 << ((v >> 3) & 0x07), 4u16 << (v & 0x07)))
    }

    /// Enable or disable block data update (`CTRL_REG1.BDU`).
    pub fn set_block_data_update(&mut self, enabled: bool) -> Result<(), Error<I2C::Error>> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        let v = if enabled { ctrl1 | CTRL1_BDU } else { ctrl1 & !CTRL1_BDU };
        self.inner.write_reg(REG_CTRL_REG1, v)
    }

    /// Enter power-down mode (`CTRL_REG1.PD = 0`).
    pub fn power_down(&mut self) -> Result<(), Error<I2C::Error>> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, ctrl1 & !CTRL1_PD)
    }

    /// Leave power-down mode (`CTRL_REG1.PD = 1`).
    pub fn power_up(&mut self) -> Result<(), Error<I2C::Error>> {
        let ctrl1 = self.inner.read_reg(REG_CTRL_REG1)?;
        self.inner.write_reg(REG_CTRL_REG1, ctrl1 | CTRL1_PD)
    }

    /// Switch the internal heater on or off (do not measure while heating).
    pub fn set_heater(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        let ctrl2 = self.inner.read_reg(REG_CTRL_REG2)?;
        let v = if on { ctrl2 | CTRL2_HEATER } else { ctrl2 & !CTRL2_HEATER };
        self.inner.write_reg(REG_CTRL_REG2, v)
    }

    /// `true` if the heater is on.
    pub fn is_heater_on(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_reg(REG_CTRL_REG2)? & CTRL2_HEATER != 0)
    }

    /// Reload the trimming registers (`CTRL_REG2.BOOT`), wait for it to clear (timeout 100 ms) and
    /// re-read the calibration.
    pub fn reboot<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let ctrl2 = self.inner.read_reg(REG_CTRL_REG2)?;
        self.inner.write_reg(REG_CTRL_REG2, ctrl2 | CTRL2_BOOT)?;
        for _ in 0..TIMEOUT_MS {
            if self.inner.read_reg(REG_CTRL_REG2)? & CTRL2_BOOT == 0 {
                return self.inner.read_calibration_block();
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    /// Read `WHO_AM_I` (`0xBC` for the HTS221).
    pub fn who_am_i(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_reg(REG_WHO_AM_I)
    }

    /// Read `STATUS_REG` ([`STATUS_H_DA`] / [`STATUS_T_DA`]).
    pub fn status(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_reg(REG_STATUS)
    }

    /// `true` when both humidity and temperature data are available.
    pub fn data_ready(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.status()? & STATUS_BOTH == STATUS_BOTH)
    }

    /// Return the decoded calibration coefficients read by `init()` / `reboot()`.
    pub fn read_calibration(&self) -> Calibration {
        self.inner.cal
    }

    /// Configure the DRDY output pin (`CTRL_REG3`).
    ///
    /// * `enabled` — route data-ready to the DRDY pin.
    /// * `active_high` — `false` selects active-low.
    /// * `open_drain` — `true` selects open-drain (default push-pull).
    pub fn configure_drdy(&mut self, enabled: bool, active_high: bool, open_drain: bool) -> Result<(), Error<I2C::Error>> {
        let mut v = 0u8;
        if !active_high {
            v |= 0x80;
        }
        if open_drain {
            v |= 0x40;
        }
        if enabled {
            v |= 0x04;
        }
        self.inner.write_reg(REG_CTRL_REG3, v)
    }

    /// Read `STATUS_REG`; the caller wires this into an ISR or polling loop. The flags clear when the
    /// output registers are read.
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.status()
    }
}

fn log2_index(value: u16, min: u16, max: u16) -> Option<u8> {
    if value < min || value > max || !value.is_power_of_two() {
        return None;
    }
    Some((value.trailing_zeros() - min.trailing_zeros()) as u8)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x5F;

    fn w(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::write(ADDR, bytes.to_vec())
    }

    fn wr(reg: &[u8], data: &[u8]) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, reg.to_vec(), data.to_vec())
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    // H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
    // H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
    const CALIB: [u8; 16] = [40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 0x2C, 0x01, 0xF4, 0x01];

    fn init_t() -> Vec<I2cTransaction> {
        vec![wr(&[0xB0], &CALIB), w(&[0x20, 0x84])]
    }

    /// Transactions of one one-shot conversion that reads the outputs `out`.
    fn one_shot(ctrl2: u8, out: [u8; 4]) -> Vec<I2cTransaction> {
        vec![
            wr(&[0x21], &[ctrl2]),
            w(&[0x21, ctrl2 | 0x01]),
            wr(&[0x27], &[0x03]),
            wr(&[0xA8], &out),
        ]
    }

    const OUT_30_15: [u8; 4] = [0x00, 0x50, 0x90, 0x01]; // H = 0x5000, T = 400

    #[test]
    fn ctor_no_bus_access() {
        let s = HTS221Minimal::new(I2cMock::new(&[]), ADDR);
        let mut i2c = s.release();
        i2c.done();
    }

    #[test]
    fn init_reads_calibration_and_starts() {
        let mut s = HTS221Minimal::new(I2cMock::new(&init_t()), ADDR);
        s.init().unwrap();
        s.i2c.done();
    }

    #[test]
    fn read_converts_with_calibration() {
        let mut t = init_t();
        t.extend(one_shot(0x00, OUT_30_15));
        let mut s = HTS221Minimal::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(temp, 15.0, 1e-3));
        assert!(close(rh, 30.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn humidity_is_clamped() {
        let mut t = init_t();
        t.extend(one_shot(0x00, [0xFF, 0x7F, 0x90, 0x01]));
        t.extend(one_shot(0x01, [0x00, 0x80, 0x90, 0x01]));
        let mut s = HTS221Minimal::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        // raw 0x7FFF is 60 %rH with this calibration; shrink the span to force clamping
        s.cal.h1_t0_out = 0x1000;
        s.cal.h0_t0_out = 0;
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 100.0, 1e-3));
        s.cal.h0_t0_out = 0x4000;
        s.cal.h1_t0_out = 0x6000;
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 0.0, 1e-3));
        s.i2c.done();
    }

    #[test]
    fn timeout_when_never_ready() {
        let mut t = init_t();
        t.push(wr(&[0x21], &[0x00]));
        t.push(w(&[0x21, 0x01]));
        for _ in 0..100 {
            t.push(wr(&[0x27], &[0x00]));
        }
        let mut s = HTS221Minimal::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        s.i2c.done();
    }

    #[test]
    fn bus_error_is_reported() {
        let t = vec![wr(&[0xB0], &CALIB).with_error(ErrorKind::Other)];
        let mut s = HTS221Minimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(s.init(), Err(Error::Bus(_))));
        s.i2c.done();
    }

    #[test]
    fn calibration_t_msb_bits() {
        let mut c = CALIB;
        c[5] = 0x0D; // T0 bits 9:8 = 01, T1 bits 9:8 = 11
        let cal = Calibration::decode(&c);
        assert!(close(cal.t0_degc, (256.0 + 80.0) / 8.0, 1e-6));
        assert!(close(cal.t1_degc, (768.0 + 160.0) / 8.0, 1e-6));
    }

    #[test]
    fn full_single_values_and_raw() {
        let mut t = init_t();
        t.extend(one_shot(0x00, OUT_30_15));
        t.extend(one_shot(0x00, OUT_30_15));
        t.extend(one_shot(0x00, OUT_30_15));
        t.extend(one_shot(0x00, OUT_30_15));
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 30.0, 1e-3));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), 15.0, 1e-3));
        assert_eq!(s.read_raw_humidity(&mut NoopDelay).unwrap(), 0x5000);
        assert_eq!(s.read_raw_temperature(&mut NoopDelay).unwrap(), 400);
        s.inner.i2c.done();
    }

    #[test]
    fn raw_values_are_signed() {
        let mut t = init_t();
        t.extend(one_shot(0x00, [0x00, 0xF0, 0x00, 0x00]));
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        assert_eq!(s.read_raw_humidity(&mut NoopDelay).unwrap(), -4096);
        s.inner.i2c.done();
    }

    #[test]
    fn read_continuous_does_not_trigger() {
        let mut t = init_t();
        t.push(wr(&[0xA8], &OUT_30_15));
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        let (temp, rh) = s.read_continuous().unwrap();
        assert!(close(temp, 15.0, 1e-3) && close(rh, 30.0, 1e-3));
        s.inner.i2c.done();
    }

    #[test]
    fn output_data_rate() {
        let t = vec![
            wr(&[0x20], &[0x84]), w(&[0x20, 0x87]), wr(&[0xA8], &[0, 0, 0, 0]),
            wr(&[0x20], &[0x87]),
            wr(&[0x20], &[0x87]), w(&[0x20, 0x84]), wr(&[0xA8], &[0, 0, 0, 0]),
        ];
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.set_output_data_rate(12.5).unwrap();
        assert_eq!(s.get_output_data_rate().unwrap(), 12.5);
        s.set_output_data_rate(0.0).unwrap();
        assert_eq!(s.set_output_data_rate(5.0), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn averaging() {
        let t = vec![w(&[0x10, 0x3F]), w(&[0x10, 0x00]), w(&[0x10, 0x1B]), wr(&[0x10], &[0x1B])];
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.set_averaging(256, 512).unwrap();
        s.set_averaging(2, 4).unwrap();
        s.set_averaging(16, 32).unwrap();
        assert_eq!(s.get_averaging().unwrap(), (16, 32));
        assert_eq!(s.set_averaging(3, 32), Err(Error::InvalidArgument));
        assert_eq!(s.set_averaging(16, 2), Err(Error::InvalidArgument));
        assert_eq!(s.set_averaging(1, 32), Err(Error::InvalidArgument));
        assert_eq!(s.set_averaging(16, 1024), Err(Error::InvalidArgument));
        s.inner.i2c.done();
    }

    #[test]
    fn bdu_and_power() {
        let t = vec![
            wr(&[0x20], &[0x84]), w(&[0x20, 0x80]),
            wr(&[0x20], &[0x80]), w(&[0x20, 0x84]),
            wr(&[0x20], &[0x84]), w(&[0x20, 0x04]),
            wr(&[0x20], &[0x04]), w(&[0x20, 0x84]),
        ];
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.set_block_data_update(false).unwrap();
        s.set_block_data_update(true).unwrap();
        s.power_down().unwrap();
        s.power_up().unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn heater() {
        let t = vec![wr(&[0x21], &[0x00]), w(&[0x21, 0x02]), wr(&[0x21], &[0x02]), wr(&[0x21], &[0x02]), w(&[0x21, 0x00])];
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.set_heater(true).unwrap();
        assert!(s.is_heater_on().unwrap());
        s.set_heater(false).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn drdy_configuration() {
        let t = vec![w(&[0x22, 0x04]), w(&[0x22, 0xC4]), w(&[0x22, 0x00])];
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.configure_drdy(true, true, false).unwrap();
        s.configure_drdy(true, false, true).unwrap();
        s.configure_drdy(false, true, false).unwrap();
        s.inner.i2c.done();
    }

    #[test]
    fn reboot_rereads_calibration() {
        let mut c2 = CALIB;
        c2[0] = 60;
        let mut t = init_t();
        t.extend(vec![wr(&[0x21], &[0x00]), w(&[0x21, 0x80]), wr(&[0x21], &[0x80]), wr(&[0x21], &[0x00]), wr(&[0xB0], &c2)]);
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        s.reboot(&mut NoopDelay).unwrap();
        assert!(close(s.read_calibration().h0_rh, 30.0, 1e-6));
        s.inner.i2c.done();
    }

    #[test]
    fn reboot_times_out() {
        let mut t = init_t();
        t.push(wr(&[0x21], &[0x00]));
        t.push(w(&[0x21, 0x80]));
        for _ in 0..100 {
            t.push(wr(&[0x21], &[0x80]));
        }
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        assert_eq!(s.reboot(&mut NoopDelay), Err(Error::Timeout));
        s.inner.i2c.done();
    }

    #[test]
    fn identity_and_status() {
        let t = vec![wr(&[0x0F], &[0xBC]), wr(&[0x27], &[0x03]), wr(&[0x27], &[0x01]), wr(&[0x27], &[0x02])];
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.who_am_i().unwrap(), 0xBC);
        assert!(s.data_ready().unwrap());
        assert_eq!(s.status().unwrap(), STATUS_T_DA);
        assert_eq!(s.poll_interrupt().unwrap(), STATUS_H_DA);
        s.inner.i2c.done();
    }

    #[test]
    fn dew_point_magnus() {
        let mut t = init_t();
        t.extend(one_shot(0x00, OUT_30_15));
        let mut s = HTS221Full::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        let g = (0.30f32).ln() + 17.62 * 15.0 / (243.12 + 15.0);
        let expected = 243.12 * g / (17.62 - g);
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), expected, 1e-2));
        s.inner.i2c.done();
    }

    #[test]
    fn minimal_class_read() {
        let mut t = init_t();
        t.extend(one_shot(0x00, OUT_30_15));
        let mut s = HTS221Minimal::new(I2cMock::new(&t), ADDR);
        s.init().unwrap();
        assert!(s.read(&mut NoopDelay).is_ok());
        s.i2c.done();
    }
}
