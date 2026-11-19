//! HS40xx — relative humidity and temperature sensors (Renesas HS4001, HS4003).
//!
//! HS4001 and HS4003 share one protocol, so `HS4001Minimal` / `HS4003Minimal` and the `Full`
//! variants are plain type aliases of [`HS40xxMinimal`] / [`HS40xxFull`] (HS4002 and the HS401x
//! variants differ only physically and get no separate type).
//!
//! Communicates over I²C (up to 400 kHz) at the fixed address `0x54`. The chip has no register
//! pointer: single-byte **commands** (no-hold measurement `0xF5`/`0xF3`, hold measurement `0xE5`,
//! register read `0xA7`, register write `0xA6`, stop periodic `0x30`, sensor ID `0xD7`, soft reset
//! `0xFE`) are written, and results are read back in a separate transaction. While a no-hold
//! conversion runs the sensor NACKs the read header, so the driver retries the read for up to
//! 20 ms. Every result carries an 8-bit CRC (polynomial `0x1D`, initial value `0xFF`).
//!
//! Rust exposes [`HS40xxFull::poll_interrupt`] (no callback subscription): the caller wires the
//! ALERT pin itself and calls `poll_interrupt` when it fires.
//!
//! Construction does no bus access.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_T_NOHOLD: u8 = 0xF3;
const CMD_HT_HOLD: u8 = 0xE5;
const CMD_HT_NOHOLD: u8 = 0xF5;
const CMD_READ_REG: u8 = 0xA7;
const CMD_WRITE_REG: u8 = 0xA6;
const CMD_STOP_PERIODIC: u8 = 0x30;
const CMD_READ_ID: u8 = 0xD7;
const CMD_SOFT_RESET: u8 = 0xFE;

const REG_RESOLUTION: u8 = 0x00;
const REG_PERIODIC: u8 = 0x02;
const REG_ALERT_EN: u8 = 0x03;

const BITS: [u8; 4] = [8, 10, 12, 14];
const FULL_SCALE: f32 = 16383.0;

/// Alert source: humidity below the low trigger threshold.
pub const SOURCE_RH_LOW: u8 = 0x01;
/// Alert source: humidity above the high trigger threshold.
pub const SOURCE_RH_HIGH: u8 = 0x02;
/// Alert source: temperature below the low trigger threshold.
pub const SOURCE_TEMP_LOW: u8 = 0x04;
/// Alert source: temperature above the high trigger threshold.
pub const SOURCE_TEMP_HIGH: u8 = 0x08;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error (also a read header NACKed for longer than 20 ms).
    Bus(E),
    /// The CRC did not match after one retry.
    Crc,
    /// Unsupported argument (resolution other than 8, 10, 12 or 14 bit; unsupported period).
    InvalidArgument,
    /// `poll_interrupt` was called while no alert source is enabled.
    AlertsDisabled,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// Result of a periodic-mode fetch.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Periodic {
    /// Temperature in °C.
    pub temperature_c: f32,
    /// Relative humidity in %RH.
    pub humidity_pct: f32,
    /// `SOURCE_*` bitmask of the active alerts (0 when alerts are off).
    pub status: u8,
}

/// Alert thresholds for [`HS40xxFull::set_alert_thresholds`]; `None` leaves a threshold unchanged.
/// Humidity values are in %RH, temperatures in °C.
#[derive(Debug, Default, Clone, Copy)]
pub struct AlertThresholds {
    /// Humidity high trigger threshold.
    pub rh_high: Option<f32>,
    /// Humidity high reset threshold.
    pub rh_high_reset: Option<f32>,
    /// Humidity low trigger threshold.
    pub rh_low: Option<f32>,
    /// Humidity low reset threshold.
    pub rh_low_reset: Option<f32>,
    /// Temperature high trigger threshold.
    pub temp_high: Option<f32>,
    /// Temperature high reset threshold.
    pub temp_high_reset: Option<f32>,
    /// Temperature low trigger threshold.
    pub temp_low: Option<f32>,
    /// Temperature low reset threshold.
    pub temp_low_reset: Option<f32>,
}

/// CRC-8 (polynomial 0x1D, initial value 0xFF, no reflection, no final XOR).
pub fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0xFFu8;
    for &b in data {
        crc ^= b;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x1D } else { crc << 1 };
        }
    }
    crc
}

fn crc_ok(frame: &[u8]) -> bool {
    // Temperature-only frames (3 bytes) take the humidity bytes as zero.
    let n = frame.len();
    if n == 3 {
        crc8(&[0, 0, frame[0], frame[1]]) == frame[2]
    } else {
        crc8(&frame[..4]) == frame[n - 1]
    }
}

fn raw_humidity(d: &[u8]) -> u16 {
    (((d[0] & 0x3F) as u16) << 8) | d[1] as u16
}

fn raw_temperature(d: &[u8], offset: usize) -> u16 {
    (((d[offset] & 0x3F) as u16) << 8) | d[offset + 1] as u16
}

fn to_humidity(raw: u16) -> f32 {
    raw as f32 / FULL_SCALE * 100.0
}

fn to_temperature(raw: u16) -> f32 {
    raw as f32 / FULL_SCALE * 165.0 - 40.0
}

/// HS40xx minimal driver — one no-hold `0xF5` measurement, five-byte read and CRC check per `read()`.
///
/// Default configuration (power-on): 14-bit resolution, one-shot no-hold measurements, CRC
/// checked, periodic mode and alerts off.
pub struct HS40xxMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> HS40xxMinimal<I2C> {
    /// Create a new `HS40xxMinimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x54`, fixed).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Read `buf`, retrying while the sensor NACKs the read header (up to 20 ms).
    fn read_frame<D: DelayNs>(&mut self, buf: &mut [u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let mut attempt = 0;
        loop {
            match self.i2c.read(self.addr, buf) {
                Ok(()) => return Ok(()),
                Err(e) => {
                    attempt += 1;
                    if attempt > 20 {
                        return Err(Error::Bus(e));
                    }
                    delay.delay_ms(1);
                }
            }
        }
    }

    /// Send `cmd`, wait (no-hold), read `buf` and verify the CRC; one retry of the whole sequence on a CRC mismatch.
    fn measure<D: DelayNs>(&mut self, cmd: u8, buf: &mut [u8], hold: bool, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        for _ in 0..2 {
            self.i2c.write(self.addr, &[cmd])?;
            if !hold {
                delay.delay_ms(3);
            }
            self.read_frame(buf, delay)?;
            if crc_ok(buf) {
                return Ok(());
            }
        }
        Err(Error::Crc)
    }

    /// Measure temperature and humidity (no-hold `0xF5`, CRC verified).
    ///
    /// Returns `(temperature_c, humidity_pct)` in degrees Celsius (−40…125) and %RH (0…100).
    /// [`Error::Crc`] if the CRC is wrong after one retry.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let mut b = [0u8; 5];
        self.measure(CMD_HT_NOHOLD, &mut b, false, delay)?;
        Ok((to_temperature(raw_temperature(&b, 2)), to_humidity(raw_humidity(&b))))
    }
}

/// HS40xx full driver — single values, hold mode, resolution, periodic mode, alerts, ID.
///
/// Owns a [`HS40xxMinimal`] and delegates `read`. Registers are volatile: they return to their
/// defaults whenever the supply is cut or after [`HS40xxFull::soft_reset`].
pub struct HS40xxFull<I2C> {
    inner: HS40xxMinimal<I2C>,
    alerts_enabled: bool,
}

impl<I2C: I2c> HS40xxFull<I2C> {
    /// Create a new `HS40xxFull`; same arguments as [`HS40xxMinimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: HS40xxMinimal::new(i2c, addr), alerts_enabled: false }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Measure temperature and humidity; see [`HS40xxMinimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Measure temperature only (no-hold `0xF3`, three bytes) in °C.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let mut b = [0u8; 3];
        self.inner.measure(CMD_T_NOHOLD, &mut b, false, delay)?;
        Ok(to_temperature(raw_temperature(&b, 0)))
    }

    /// Measure humidity (uses the `0xF5` command) in %RH.
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.read(delay)?.1)
    }

    /// Measure with the hold command `0xE5` (the sensor stretches SCL until done).
    ///
    /// Needs a clock-stretching-capable controller and SCL ≥ 200 kHz. Returns `(temperature_c, humidity_pct)`.
    pub fn read_hold<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let mut b = [0u8; 5];
        self.inner.measure(CMD_HT_HOLD, &mut b, true, delay)?;
        Ok((to_temperature(raw_temperature(&b, 2)), to_humidity(raw_humidity(&b))))
    }

    /// Measure and return `(raw_humidity, raw_temperature)`, 14 bit each (CRC verified).
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), Error<I2C::Error>> {
        let mut b = [0u8; 5];
        self.inner.measure(CMD_HT_NOHOLD, &mut b, false, delay)?;
        Ok((raw_humidity(&b), raw_temperature(&b, 2)))
    }

    /// Read one configuration register (`0xA7` + address, then a one-byte read).
    pub fn read_register<D: DelayNs>(&mut self, reg: u8, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_READ_REG, reg])?;
        let mut b = [0u8; 1];
        self.inner.read_frame(&mut b, delay)?;
        Ok(b[0])
    }

    /// Write one configuration register (`0xA6` + address + data).
    pub fn write_register(&mut self, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_WRITE_REG, reg, value])?;
        Ok(())
    }

    fn modify<D: DelayNs>(&mut self, reg: u8, mask: u8, bits: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let v = self.read_register(reg, delay)?;
        self.write_register(reg, (v & !mask) | (bits & mask))
    }

    /// Set the measurement resolutions (register `0x00`, read-modify-write).
    ///
    /// `None` leaves a channel unchanged; other values must be 8, 10, 12 or 14
    /// ([`Error::InvalidArgument`], before any bus access).
    pub fn set_resolution<D: DelayNs>(&mut self, humidity_bits: Option<u8>, temperature_bits: Option<u8>, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let code = |bits: Option<u8>| -> Result<Option<u8>, Error<I2C::Error>> {
            match bits {
                None => Ok(None),
                Some(b) => BITS.iter().position(|&x| x == b).map(|i| Some(i as u8)).ok_or(Error::InvalidArgument),
            }
        };
        let rh = code(humidity_bits)?;
        let t = code(temperature_bits)?;
        let (mut mask, mut value) = (0u8, 0u8);
        if let Some(c) = rh {
            mask |= 0x0C;
            value |= c << 2;
        }
        if let Some(c) = t {
            mask |= 0x03;
            value |= c;
        }
        if mask != 0 {
            self.modify(REG_RESOLUTION, mask, value, delay)?;
        }
        Ok(())
    }

    /// Read the measurement resolutions: `(humidity_bits, temperature_bits)`, each 8, 10, 12 or 14.
    pub fn get_resolution<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u8, u8), Error<I2C::Error>> {
        let v = self.read_register(REG_RESOLUTION, delay)?;
        Ok((BITS[((v >> 2) & 3) as usize], BITS[(v & 3) as usize]))
    }

    /// Start periodic measurement mode with a period of 0.5, 1 or 2.5 seconds ([`Error::InvalidArgument`] otherwise).
    pub fn start_periodic<D: DelayNs>(&mut self, period_s: f32, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let code = if period_s == 0.5 {
            0u8
        } else if period_s == 1.0 {
            1
        } else if period_s == 2.5 {
            2
        } else {
            return Err(Error::InvalidArgument);
        };
        self.alerts_enabled = self.read_register(REG_ALERT_EN, delay)? & 0x0F != 0;
        self.modify(REG_PERIODIC, 0xB0, 0x80 | (code << 4), delay)
    }

    /// Leave periodic mode (command `0x30`); the sensor then sleeps.
    pub fn stop_periodic(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_STOP_PERIODIC])?;
        Ok(())
    }

    /// Fetch the latest periodic result (a plain read, CRC verified).
    ///
    /// The frame has a leading status byte when any alert source is enabled.
    pub fn read_periodic<D: DelayNs>(&mut self, delay: &mut D) -> Result<Periodic, Error<I2C::Error>> {
        let mut b = [0u8; 6];
        let (off, status) = if self.alerts_enabled {
            self.inner.read_frame(&mut b, delay)?;
            (1, b[0] & 0x0F)
        } else {
            self.inner.read_frame(&mut b[..5], delay)?;
            (0, 0)
        };
        let f = &b[off..off + 5];
        if !crc_ok(f) {
            return Err(Error::Crc);
        }
        Ok(Periodic { temperature_c: to_temperature(raw_temperature(f, 2)), humidity_pct: to_humidity(raw_humidity(f)), status })
    }

    /// Program alert trigger / reset thresholds; only the `Some` fields are written.
    pub fn set_alert_thresholds(&mut self, th: &AlertThresholds) -> Result<(), Error<I2C::Error>> {
        let rh = |v: f32| ((v / 100.0 * FULL_SCALE + 0.5) as i32).clamp(0, 16383) as u16;
        let t = |v: f32| (((v + 40.0) / 165.0 * FULL_SCALE + 0.5) as i32).clamp(0, 16383) as u16;
        let list: [(Option<f32>, u8, bool); 8] = [
            (th.rh_high, 0x0F, true), (th.rh_high_reset, 0x0D, true),
            (th.rh_low, 0x11, true), (th.rh_low_reset, 0x13, true),
            (th.temp_high, 0x07, false), (th.temp_high_reset, 0x05, false),
            (th.temp_low, 0x09, false), (th.temp_low_reset, 0x0B, false),
        ];
        for (value, reg, is_rh) in list {
            if let Some(v) = value {
                let word = if is_rh { rh(v) } else { t(v) };
                self.write_register(reg, word as u8)?;
                self.write_register(reg + 1, (word >> 8) as u8)?;
            }
        }
        Ok(())
    }

    /// Set the ALERT pin polarity (register `0x02` bit 0): `true` = active high when triggered (default).
    pub fn set_alert_polarity<D: DelayNs>(&mut self, active_high: bool, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.modify(REG_PERIODIC, 0x01, active_high as u8, delay)
    }

    /// Enable alert source(s) (`SOURCE_*` bitmask, register `0x03`).
    pub fn enable_interrupt<D: DelayNs>(&mut self, source: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let v = self.read_register(REG_ALERT_EN, delay)? | (source & 0x0F);
        self.write_register(REG_ALERT_EN, v)?;
        self.alerts_enabled = v & 0x0F != 0;
        Ok(())
    }

    /// Disable alert source(s) (`SOURCE_*` bitmask, register `0x03`).
    pub fn disable_interrupt<D: DelayNs>(&mut self, source: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let v = self.read_register(REG_ALERT_EN, delay)? & !(source & 0x0F);
        self.write_register(REG_ALERT_EN, v)?;
        self.alerts_enabled = v & 0x0F != 0;
        Ok(())
    }

    /// Fetch the latest periodic result and return the active-alert `SOURCE_*` bitmask.
    ///
    /// Requires periodic mode with at least one alert source enabled ([`Error::AlertsDisabled`] otherwise).
    pub fn poll_interrupt<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        if !self.alerts_enabled {
            return Err(Error::AlertsDisabled);
        }
        Ok(self.read_periodic(delay)?.status)
    }

    /// Read the 32-bit sensor ID (command `0xD7`, four bytes MSB first).
    pub fn read_id<D: DelayNs>(&mut self, delay: &mut D) -> Result<u32, Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_READ_ID])?;
        let mut b = [0u8; 4];
        self.inner.read_frame(&mut b, delay)?;
        Ok(u32::from_be_bytes(b))
    }

    /// Stop periodic mode, send the soft reset (`0xFE`) and wait 500 ms.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.stop_periodic()?;
        self.inner.i2c.write(self.inner.addr, &[CMD_SOFT_RESET])?;
        delay.delay_ms(500);
        self.alerts_enabled = false;
        Ok(())
    }

    /// Measure and return the dew point in °C (Magnus formula); −273.15 if humidity is 0 %RH.
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let (t, rh) = self.inner.read(delay)?;
        if rh <= 0.0 {
            return Ok(-273.15);
        }
        let gamma = libm::logf(rh / 100.0) + 17.62 * t / (243.12 + t);
        Ok(243.12 * gamma / (17.62 - gamma))
    }
}

/// HS4001 minimal driver — alias of [`HS40xxMinimal`] (same protocol).
pub type HS4001Minimal<I2C> = HS40xxMinimal<I2C>;
/// HS4003 minimal driver — alias of [`HS40xxMinimal`].
pub type HS4003Minimal<I2C> = HS40xxMinimal<I2C>;
/// HS4001 full driver — alias of [`HS40xxFull`].
pub type HS4001Full<I2C> = HS40xxFull<I2C>;
/// HS4003 full driver — alias of [`HS40xxFull`].
pub type HS4003Full<I2C> = HS40xxFull<I2C>;

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x54;

    fn w(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::write(ADDR, bytes.to_vec())
    }

    fn r(bytes: Vec<u8>) -> I2cTransaction {
        I2cTransaction::read(ADDR, bytes)
    }

    fn ht(h: u16, t: u16) -> Vec<u8> {
        let mut d = vec![(h >> 8) as u8, h as u8, (t >> 8) as u8, t as u8];
        d.push(crc8(&d));
        d
    }

    fn t_only(t: u16) -> Vec<u8> {
        let d = [(t >> 8) as u8, t as u8];
        vec![d[0], d[1], crc8(&[0, 0, d[0], d[1]])]
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn crc_datasheet_example() {
        assert_eq!(crc8(&[0x05, 0x80, 0x05, 0x80]), 0xF2);
    }

    #[test]
    fn constructor_no_bus_access() {
        let t: Vec<I2cTransaction> = vec![];
        let s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        s.release().done();
    }

    #[test]
    fn datasheet_example_frame() {
        let t = vec![w(&[0xF5]), r(vec![0x05, 0x80, 0x05, 0x80, 0xF2])];
        let mut s = HS40xxMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 8.59, 0.01) && close(temp, -25.8, 0.05));
        s.release().done();
    }

    #[test]
    fn full_scale_and_zero() {
        let t = vec![w(&[0xF5]), r(ht(0x3FFF, 0x3FFF)), w(&[0xF5]), r(ht(0, 0))];
        let mut s = HS40xxMinimal::new(I2cMock::new(&t), ADDR);
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 100.0, 1e-3) && close(temp, 125.0, 1e-3));
        let (temp, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 0.0, 1e-3) && close(temp, -40.0, 1e-3));
        s.release().done();
    }

    #[test]
    fn nack_is_retried() {
        let t = vec![
            w(&[0xF5]),
            r(vec![0; 5]).with_error(ErrorKind::Other),
            r(vec![0; 5]).with_error(ErrorKind::Other),
            r(ht(0x2000, 0x1000)),
        ];
        let mut s = HS40xxMinimal::new(I2cMock::new(&t), ADDR);
        let (_, rh) = s.read(&mut NoopDelay).unwrap();
        assert!(close(rh, 50.0, 0.01));
        s.release().done();
    }

    #[test]
    fn persistent_nack_errors() {
        let mut t = vec![w(&[0xF5])];
        for _ in 0..21 {
            t.push(r(vec![0; 5]).with_error(ErrorKind::Other));
        }
        let mut s = HS40xxMinimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(s.read(&mut NoopDelay), Err(Error::Bus(_))));
        s.release().done();
    }

    #[test]
    fn crc_mismatch_retried_once() {
        let mut bad = ht(0x2000, 0x1000);
        bad[4] ^= 0xFF;
        let t = vec![w(&[0xF5]), r(bad.clone()), w(&[0xF5]), r(ht(0x2000, 0x1000))];
        let mut s = HS40xxMinimal::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read(&mut NoopDelay).unwrap().1, 50.0, 0.01));
        s.release().done();
        let t = vec![w(&[0xF5]), r(bad.clone()), w(&[0xF5]), r(bad)];
        let mut s = HS40xxMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Crc));
        s.release().done();
    }

    #[test]
    fn single_values_hold_and_raw() {
        let t = vec![
            w(&[0xF3]), r(t_only(0x1000)),
            w(&[0xF5]), r(ht(0x2000, 0x1000)),
            w(&[0xE5]), r(ht(0x2000, 0x1000)),
            w(&[0xF5]), r(ht(0x1234, 0x0ABC)),
        ];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), 4096.0 / 16383.0 * 165.0 - 40.0, 1e-3));
        assert!(close(s.read_humidity(&mut NoopDelay).unwrap(), 50.0, 0.01));
        assert!(close(s.read_hold(&mut NoopDelay).unwrap().1, 50.0, 0.01));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (0x1234, 0x0ABC));
        s.release().done();
    }

    #[test]
    fn registers() {
        let t = vec![w(&[0xA7, 0x02]), r(vec![0x5A]), w(&[0xA6, 0x03, 0x0A])];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read_register(0x02, &mut NoopDelay).unwrap(), 0x5A);
        s.write_register(0x03, 0x0A).unwrap();
        s.release().done();
    }

    #[test]
    fn resolution() {
        let t = vec![
            w(&[0xA7, 0x00]), r(vec![0xF3]), w(&[0xA6, 0x00, 0xF2]),
            w(&[0xA7, 0x00]), r(vec![0xFF]), w(&[0xA6, 0x00, 0xFD]),
            w(&[0xA7, 0x00]), r(vec![0x0B]),
        ];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        s.set_resolution(Some(8), Some(12), &mut NoopDelay).unwrap();
        s.set_resolution(None, Some(10), &mut NoopDelay).unwrap();
        assert_eq!(s.set_resolution(Some(9), None, &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.get_resolution(&mut NoopDelay).unwrap(), (12, 14));
        s.release().done();
    }

    #[test]
    fn periodic_without_alerts() {
        let t = vec![
            w(&[0xA7, 0x03]), r(vec![0x00]),
            w(&[0xA7, 0x02]), r(vec![0x01]), w(&[0xA6, 0x02, 0xA1]),
            r(ht(0x2000, 0x1000)),
            w(&[0x30]),
        ];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        s.start_periodic(2.5, &mut NoopDelay).unwrap();
        assert_eq!(s.start_periodic(3.0, &mut NoopDelay), Err(Error::InvalidArgument));
        let p = s.read_periodic(&mut NoopDelay).unwrap();
        assert!(p.status == 0 && close(p.humidity_pct, 50.0, 0.01));
        s.stop_periodic().unwrap();
        s.release().done();
    }

    #[test]
    fn alerts_and_status() {
        let mut f = vec![0x0A];
        f.extend(ht(0x2000, 0x1000));
        let mut g = vec![0x02];
        g.extend(ht(0x2000, 0x1000));
        let t = vec![
            w(&[0xA7, 0x03]), r(vec![0x00]), w(&[0xA6, 0x03, 0x02]),
            r(f),
            r(g),
            w(&[0xA7, 0x03]), r(vec![0x02]), w(&[0xA6, 0x03, 0x00]),
        ];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.poll_interrupt(&mut NoopDelay), Err(Error::AlertsDisabled));
        s.enable_interrupt(SOURCE_RH_HIGH, &mut NoopDelay).unwrap();
        assert_eq!(s.read_periodic(&mut NoopDelay).unwrap().status, 0x0A);
        assert_eq!(s.poll_interrupt(&mut NoopDelay).unwrap(), 0x02);
        s.disable_interrupt(SOURCE_RH_HIGH, &mut NoopDelay).unwrap();
        s.release().done();
    }

    #[test]
    fn thresholds() {
        let t = vec![
            w(&[0xA6, 0x0F, 0x66]), w(&[0xA6, 0x10, 0x26]),
            w(&[0xA6, 0x0D, 0x33]), w(&[0xA6, 0x0E, 0x23]),
            w(&[0xA6, 0x07, 0xFF]), w(&[0xA6, 0x08, 0x3F]),
            w(&[0xA6, 0x09, 0x00]), w(&[0xA6, 0x0A, 0x00]),
        ];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        s.set_alert_thresholds(&AlertThresholds { rh_high: Some(60.0), rh_high_reset: Some(55.0), ..Default::default() }).unwrap();
        s.set_alert_thresholds(&AlertThresholds { temp_high: Some(125.0), temp_low: Some(-40.0), ..Default::default() }).unwrap();
        s.release().done();
    }

    #[test]
    fn polarity_id_and_reset() {
        let t = vec![
            w(&[0xA7, 0x02]), r(vec![0xB1]), w(&[0xA6, 0x02, 0xB0]),
            w(&[0xD7]), r(vec![0xDE, 0xAD, 0xBE, 0xEF]),
            w(&[0x30]), w(&[0xFE]),
        ];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        s.set_alert_polarity(false, &mut NoopDelay).unwrap();
        assert_eq!(s.read_id(&mut NoopDelay).unwrap(), 0xDEADBEEF);
        s.soft_reset(&mut NoopDelay).unwrap();
        s.release().done();
    }

    #[test]
    fn dew_point() {
        let h = (0.5 * 16383.0) as u16;
        let tt = ((25.0 + 40.0) / 165.0 * 16383.0) as u16;
        let t = vec![w(&[0xF5]), r(ht(h, tt)), w(&[0xF5]), r(ht(0, 0x1000))];
        let mut s = HS40xxFull::new(I2cMock::new(&t), ADDR);
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), 13.86, 0.1));
        assert!(close(s.dew_point(&mut NoopDelay).unwrap(), -273.15, 1e-3));
        s.release().done();
    }
}
