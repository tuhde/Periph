//! MS5611 — barometric pressure sensor (TE Connectivity), 10–1200 mbar, 24-bit ADC.
//!
//! Communicates over I²C at `0x77` (`CSB` = GND) or `0x76` (`CSB` = VDD). The chip is command
//! driven (no register pointer): single-byte commands reset it, start a conversion, read the
//! factory PROM (six calibration coefficients plus a CRC-4) and read the 24-bit ADC result. The
//! driver reads the PROM once in `init()`, waits the full conversion time and applies the integer
//! compensation with the second-order low-temperature correction. Pressure is reported in pascals
//! (1 Pa = 0.01 mbar, the chip's native unit). Construction does no bus access.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_RESET: u8 = 0x1E;
const CMD_CONVERT_D1: u8 = 0x40;
const CMD_CONVERT_D2: u8 = 0x50;
const CMD_ADC_READ: u8 = 0x00;
const CMD_PROM_READ: u8 = 0xA0;

const OSR_VALUES: [u16; 5] = [256, 512, 1024, 2048, 4096];
/// Maximum conversion time per OSR in microseconds (datasheet).
const T_CONV_US: [u32; 5] = [600, 1170, 2280, 4540, 9040];

/// Which quantity a conversion measures.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Conversion {
    /// D1: pressure.
    Pressure,
    /// D2: temperature.
    Temperature,
}

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// The PROM CRC-4 does not match.
    Crc,
    /// The ADC returned 0 (read too early, repeated, or no conversion started).
    NotReady,
    /// The oversampling ratio is not 256, 512, 1024, 2048 or 4096.
    InvalidOsr,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// CRC-4 of the PROM (application note AN520): polynomial x⁴ + x³ + 1 over the 16 bytes of the
/// eight words, MSB first; the low byte of word 7 (including the CRC nibble) counts as zero.
pub fn crc4(words: &[u16; 8]) -> u8 {
    let mut prom = *words;
    prom[7] &= 0xFF00;
    let mut rem: u16 = 0;
    for cnt in 0..16usize {
        if cnt & 1 == 1 {
            rem ^= prom[cnt >> 1] & 0x00FF;
        } else {
            rem ^= prom[cnt >> 1] >> 8;
        }
        for _ in 0..8 {
            rem = if rem & 0x8000 != 0 { (rem << 1) ^ 0x3000 } else { rem << 1 };
        }
    }
    ((rem >> 12) & 0x0F) as u8
}

fn osr_index(osr: u16) -> Option<usize> {
    OSR_VALUES.iter().position(|&v| v == osr)
}

fn compensate_raw(c: &[u16; 8], d1: u32, d2: u32, second_order: bool) -> (f32, f32) {
    let (c1, c2, c3, c4, c5, c6) = (c[1] as i64, c[2] as i64, c[3] as i64, c[4] as i64, c[5] as i64, c[6] as i64);
    let dt = d2 as i64 - c5 * 256;
    let mut temp = 2000 + dt * c6 / (1 << 23);
    let mut off = c2 * (1 << 16) + c4 * dt / (1 << 7);
    let mut sens = c1 * (1 << 15) + c3 * dt / (1 << 8);
    if second_order && temp < 2000 {
        let d = temp - 2000;
        let t2 = dt * dt / (1 << 31);
        let mut off2 = 5 * d * d / 2;
        let mut sens2 = 5 * d * d / 4;
        if temp < -1500 {
            let e = temp + 1500;
            off2 += 7 * e * e;
            sens2 += 11 * e * e / 2;
        }
        temp -= t2;
        off -= off2;
        sens -= sens2;
    }
    let p = ((d1 as i64 * sens) / (1 << 21) - off) / (1 << 15);
    (temp as f32 / 100.0, p as f32)
}

/// MS5611 minimal driver — temperature and pressure at OSR 4096.
///
/// `init()` resets the chip and reads and verifies the PROM; each `read()` converts D2 then D1
/// (waiting 10 ms each) and applies the first- and second-order compensation.
pub struct MS5611Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    osr_index: usize,
    prom: [u16; 8],
}

impl<I2C: I2c> MS5611Minimal<I2C> {
    /// Create a new `MS5611Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x77`, or `0x76` with `CSB` high).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, osr_index: 4, prom: [0; 8] }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn read_prom_words(&mut self) -> Result<[u16; 8], Error<I2C::Error>> {
        let mut words = [0u16; 8];
        for (n, word) in words.iter_mut().enumerate() {
            self.i2c.write(self.addr, &[CMD_PROM_READ + 2 * n as u8])?;
            let mut buf = [0u8; 2];
            self.i2c.read(self.addr, &mut buf)?;
            *word = u16::from_be_bytes(buf);
        }
        Ok(words)
    }

    fn read_adc_raw(&mut self) -> Result<u32, Error<I2C::Error>> {
        self.i2c.write(self.addr, &[CMD_ADC_READ])?;
        let mut buf = [0u8; 3];
        self.i2c.read(self.addr, &mut buf)?;
        let v = ((buf[0] as u32) << 16) | ((buf[1] as u32) << 8) | buf[2] as u32;
        if v == 0 {
            return Err(Error::NotReady);
        }
        Ok(v)
    }

    fn start(&mut self, kind: Conversion, index: usize) -> Result<(), Error<I2C::Error>> {
        let base = if kind == Conversion::Temperature { CMD_CONVERT_D2 } else { CMD_CONVERT_D1 };
        self.i2c.write(self.addr, &[base | ((index as u8) << 1)])?;
        Ok(())
    }

    fn convert<D: DelayNs>(&mut self, kind: Conversion, delay: &mut D) -> Result<u32, Error<I2C::Error>> {
        self.start(kind, self.osr_index)?;
        // maximum conversion time rounded up to whole milliseconds, plus 1 ms margin
        delay.delay_ms(T_CONV_US[self.osr_index].div_ceil(1000) + 1);
        self.read_adc_raw()
    }

    /// Reset (`0x1E`), wait 3 ms, read the eight PROM words and verify the CRC-4.
    ///
    /// [`Error::Crc`] if the PROM CRC does not match.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[CMD_RESET])?;
        delay.delay_ms(3);
        let words = self.read_prom_words()?;
        if crc4(&words) != (words[7] & 0x0F) as u8 {
            return Err(Error::Crc);
        }
        self.prom = words;
        Ok(())
    }

    /// Measure temperature and pressure.
    ///
    /// Returns `(temperature_c, pressure_pa)` in degrees Celsius and pascals.
    /// [`Error::NotReady`] if the ADC returns 0.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        let d2 = self.convert(Conversion::Temperature, delay)?;
        let d1 = self.convert(Conversion::Pressure, delay)?;
        Ok(compensate_raw(&self.prom, d1, d2, true))
    }
}

/// MS5611 full driver — single values, raw codes, oversampling, non-blocking conversion, PROM access, altitude.
///
/// Owns a [`MS5611Minimal`] and delegates `init` and `read`.
pub struct MS5611Full<I2C> {
    inner: MS5611Minimal<I2C>,
}

impl<I2C: I2c> MS5611Full<I2C> {
    /// Create a new `MS5611Full`; same arguments as [`MS5611Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: MS5611Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Reset, read and verify the PROM (see [`MS5611Minimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Measure temperature and pressure (see [`MS5611Minimal::read`]).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Convert D2 and D1 at the current OSR and return the raw 24-bit codes `(d1, d2)`.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u32, u32), Error<I2C::Error>> {
        let d2 = self.inner.convert(Conversion::Temperature, delay)?;
        let d1 = self.inner.convert(Conversion::Pressure, delay)?;
        Ok((d1, d2))
    }

    /// Measure and return the compensated pressure in pascals.
    pub fn read_pressure<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.read(delay)?.1)
    }

    /// Convert D2 only and return the temperature in °C (with the low-temperature correction).
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        let d2 = self.inner.convert(Conversion::Temperature, delay)?;
        Ok(compensate_raw(&self.inner.prom, 0, d2, true).0)
    }

    /// Set the oversampling ratio (256, 512, 1024, 2048 or 4096) used for both conversions.
    ///
    /// [`Error::InvalidOsr`] for any other value.
    pub fn set_oversampling(&mut self, osr: u16) -> Result<(), Error<I2C::Error>> {
        self.inner.osr_index = osr_index(osr).ok_or(Error::InvalidOsr)?;
        Ok(())
    }

    /// The current oversampling ratio (256 … 4096).
    pub fn get_oversampling(&self) -> u16 {
        OSR_VALUES[self.inner.osr_index]
    }

    /// Maximum conversion time in seconds for `osr`, or for the current ratio if `None`.
    ///
    /// [`Error::InvalidOsr`] for an invalid ratio.
    pub fn conversion_time(&self, osr: Option<u16>) -> Result<f32, Error<I2C::Error>> {
        let i = match osr {
            None => self.inner.osr_index,
            Some(v) => osr_index(v).ok_or(Error::InvalidOsr)?,
        };
        Ok(T_CONV_US[i] as f32 / 1.0e6)
    }

    /// Write a conversion command without waiting (non-blocking use); wait at least
    /// [`conversion_time`](Self::conversion_time) before [`read_adc`](Self::read_adc).
    ///
    /// `osr` is the oversampling ratio, or `None` for the current one.
    pub fn start_conversion(&mut self, kind: Conversion, osr: Option<u16>) -> Result<(), Error<I2C::Error>> {
        let i = match osr {
            None => self.inner.osr_index,
            Some(v) => osr_index(v).ok_or(Error::InvalidOsr)?,
        };
        self.inner.start(kind, i)
    }

    /// ADC read command plus 3 bytes: the result of the last conversion.
    ///
    /// Only valid after the conversion time; [`Error::NotReady`] if the chip returns 0.
    pub fn read_adc(&mut self) -> Result<u32, Error<I2C::Error>> {
        self.inner.read_adc_raw()
    }

    /// Pure computation with the stored coefficients (no bus access); `(temperature_c, pressure_pa)`.
    pub fn compensate(&self, d1: u32, d2: u32, second_order: bool) -> (f32, f32) {
        compensate_raw(&self.inner.prom, d1, d2, second_order)
    }

    /// Reset (`0x1E`), wait 3 ms and reload and verify the PROM.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Read the eight PROM words from the chip.
    pub fn read_prom(&mut self) -> Result<[u16; 8], Error<I2C::Error>> {
        self.inner.read_prom_words()
    }

    /// The calibration coefficients `[C1, …, C6]` read at init.
    pub fn coefficients(&self) -> [u16; 6] {
        let p = &self.inner.prom;
        [p[1], p[2], p[3], p[4], p[5], p[6]]
    }

    /// `true` if the CRC-4 of the PROM words read at init matches (no bus access).
    pub fn prom_crc_ok(&self) -> bool {
        crc4(&self.inner.prom) == (self.inner.prom[7] & 0x0F) as u8
    }

    /// The serial code (PROM word 7, bits 15:4).
    pub fn serial_code(&self) -> u16 {
        (self.inner.prom[7] >> 4) & 0x0FFF
    }

    /// Altitude in metres from one pressure reading (international barometric formula).
    pub fn altitude<D: DelayNs>(&mut self, delay: &mut D, sea_level_pa: f32) -> Result<f32, Error<I2C::Error>> {
        let p = self.read_pressure(delay)?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_pa, 1.0 / 5.255)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x77;
    const COEFFS: [u16; 6] = [40127, 36924, 23317, 23282, 33464, 28312];
    const D1_EX: u32 = 9085466;
    const D2_EX: u32 = 8569150;

    fn prom() -> [u16; 8] {
        let mut w = [0x3132, COEFFS[0], COEFFS[1], COEFFS[2], COEFFS[3], COEFFS[4], COEFFS[5], 0x5A3 << 4];
        w[7] |= crc4(&w) as u16;
        w
    }

    fn w(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::write(ADDR, bytes.to_vec())
    }

    fn r(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::read(ADDR, bytes.to_vec())
    }

    fn adc(v: u32) -> Vec<u8> {
        vec![(v >> 16) as u8, (v >> 8) as u8, v as u8]
    }

    fn init_transactions() -> Vec<I2cTransaction> {
        let mut t = vec![w(&[0x1E])];
        for (n, word) in prom().iter().enumerate() {
            t.push(w(&[0xA0 + 2 * n as u8]));
            t.push(r(&word.to_be_bytes()));
        }
        t
    }

    fn ready_full(extra: Vec<I2cTransaction>) -> MS5611Full<I2cMock> {
        let mut t = init_transactions();
        t.extend(extra);
        let mut s = MS5611Full::new(I2cMock::new(&t), ADDR);
        s.init(&mut NoopDelay).unwrap();
        s
    }

    fn read_cycle(d1: u32, d2: u32) -> Vec<I2cTransaction> {
        vec![w(&[0x58]), w(&[0x00]), r(&adc(d2)), w(&[0x48]), w(&[0x00]), r(&adc(d1))]
    }

    fn close(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn crc_roundtrip() {
        let p = prom();
        assert_eq!(crc4(&p), (p[7] & 0xF) as u8);
        assert_eq!(crc4(&[0; 8]), 0);
        let mut bad = p;
        bad[3] ^= 0x0100;
        assert_ne!(crc4(&bad), (bad[7] & 0xF) as u8);
    }

    #[test]
    fn init_sequence_and_prom() {
        let mut s = ready_full(vec![]);
        assert_eq!(s.coefficients(), COEFFS);
        assert!(s.prom_crc_ok());
        assert_eq!(s.serial_code(), 0x5A3);
        s.release().done();
    }

    #[test]
    fn init_rejects_bad_crc() {
        let mut t = vec![w(&[0x1E])];
        let mut p = prom();
        p[3] ^= 0x0100;
        for (n, word) in p.iter().enumerate() {
            t.push(w(&[0xA0 + 2 * n as u8]));
            t.push(r(&word.to_be_bytes()));
        }
        let mut s = MS5611Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.init(&mut NoopDelay), Err(Error::Crc));
        s.i2c.done();
    }

    #[test]
    fn datasheet_example() {
        let s = ready_full(vec![]);
        let (t, p) = s.compensate(D1_EX, D2_EX, true);
        assert!(close(t, 20.07, 0.001));
        assert!(close(p, 100009.0, 0.5));
        assert_eq!(s.compensate(D1_EX, D2_EX, false), (t, p));
        s.release().done();
    }

    #[test]
    fn second_order_branches() {
        let s = ready_full(vec![]);
        let (t, p) = s.compensate(D1_EX, D2_EX - 300_000, true);
        assert!(close(t, 9.55, 0.001) && close(p, 97972.0, 0.5));
        let (t, p) = s.compensate(D1_EX, D2_EX - 300_000, false);
        assert!(close(t, 9.96, 0.001) && close(p, 98061.0, 0.5));
        let (t, p) = s.compensate(D1_EX, D2_EX - 1_200_000, true);
        assert!(close(t, -27.09, 0.001) && close(p, 90615.0, 0.5));
        let (t, p) = s.compensate(D1_EX, D2_EX - 1_200_000, false);
        assert!(close(t, -20.42, 0.001) && close(p, 92219.0, 0.5));
        s.release().done();
    }

    #[test]
    fn minimal_read_commands() {
        let mut t = init_transactions();
        t.extend(read_cycle(D1_EX, D2_EX));
        let mut s = MS5611Minimal::new(I2cMock::new(&t), ADDR);
        s.init(&mut NoopDelay).unwrap();
        let (tc, p) = s.read(&mut NoopDelay).unwrap();
        assert!(close(tc, 20.07, 0.001) && close(p, 100009.0, 0.5));
        s.i2c.done();
    }

    #[test]
    fn adc_zero_is_not_ready() {
        let mut t = init_transactions();
        t.extend([w(&[0x58]), w(&[0x00]), r(&adc(0))]);
        let mut s = MS5611Minimal::new(I2cMock::new(&t), ADDR);
        s.init(&mut NoopDelay).unwrap();
        assert_eq!(s.read(&mut NoopDelay), Err(Error::NotReady));
        s.i2c.done();
    }

    #[test]
    fn single_values_and_raw() {
        let mut extra = read_cycle(D1_EX, D2_EX);
        extra.extend([w(&[0x58]), w(&[0x00]), r(&adc(D2_EX))]);
        extra.extend(read_cycle(D1_EX, D2_EX));
        let mut s = ready_full(extra);
        assert!(close(s.read_pressure(&mut NoopDelay).unwrap(), 100009.0, 0.5));
        assert!(close(s.read_temperature(&mut NoopDelay).unwrap(), 20.07, 0.001));
        assert_eq!(s.read_raw(&mut NoopDelay).unwrap(), (D1_EX, D2_EX));
        s.release().done();
    }

    #[test]
    fn oversampling_commands() {
        let mut extra = vec![];
        for i in 0..5u8 {
            extra.extend([w(&[0x50 | (i << 1)]), w(&[0x00]), r(&adc(D2_EX)), w(&[0x40 | (i << 1)]), w(&[0x00]), r(&adc(D1_EX))]);
        }
        let mut s = ready_full(extra);
        for osr in OSR_VALUES {
            s.set_oversampling(osr).unwrap();
            assert_eq!(s.get_oversampling(), osr);
            s.read(&mut NoopDelay).unwrap();
        }
        assert_eq!(s.set_oversampling(300), Err(Error::InvalidOsr));
        s.release().done();
    }

    #[test]
    fn conversion_time_table() {
        let s = ready_full(vec![]);
        let times: Vec<f32> = OSR_VALUES.iter().map(|&o| s.conversion_time(Some(o)).unwrap()).collect();
        assert_eq!(times, vec![0.0006, 0.00117, 0.00228, 0.00454, 0.00904]);
        assert_eq!(s.conversion_time(None).unwrap(), 0.00904);
        assert_eq!(s.conversion_time(Some(100)), Err(Error::InvalidOsr));
        s.release().done();
    }

    #[test]
    fn non_blocking_conversion() {
        let extra = vec![w(&[0x58]), w(&[0x42]), w(&[0x00]), r(&adc(D1_EX)), w(&[0x00]), r(&adc(0))];
        let mut s = ready_full(extra);
        s.start_conversion(Conversion::Temperature, None).unwrap();
        s.start_conversion(Conversion::Pressure, Some(512)).unwrap();
        assert_eq!(s.start_conversion(Conversion::Pressure, Some(99)), Err(Error::InvalidOsr));
        assert_eq!(s.read_adc().unwrap(), D1_EX);
        assert_eq!(s.read_adc(), Err(Error::NotReady));
        s.release().done();
    }

    #[test]
    fn reset_and_read_prom() {
        let mut extra = init_transactions();
        for (n, word) in prom().iter().enumerate() {
            extra.push(w(&[0xA0 + 2 * n as u8]));
            extra.push(r(&word.to_be_bytes()));
        }
        let mut s = ready_full(extra);
        s.reset(&mut NoopDelay).unwrap();
        assert_eq!(s.read_prom().unwrap(), prom());
        s.release().done();
    }

    #[test]
    fn altitude_formula() {
        let mut s = ready_full([read_cycle(D1_EX, D2_EX), read_cycle(D1_EX, D2_EX)].concat());
        let expected = 44330.0 * (1.0 - libm::powf(100009.0 / 101325.0, 1.0 / 5.255));
        let h = s.altitude(&mut NoopDelay, 101325.0).unwrap();
        assert!(close(h, expected, 0.01) && h > 0.0);
        assert!(close(s.altitude(&mut NoopDelay, 100009.0).unwrap(), 0.0, 0.01));
        s.release().done();
    }

    #[test]
    fn bus_error_propagates() {
        let t = [w(&[0x1E]).with_error(embedded_hal::i2c::ErrorKind::Other)];
        let mut s = MS5611Minimal::new(I2cMock::new(&t), ADDR);
        assert!(matches!(s.init(&mut NoopDelay), Err(Error::Bus(_))));
        s.i2c.done();
    }
}
