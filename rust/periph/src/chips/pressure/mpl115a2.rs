//! MPL115A2 — miniature I²C digital barometer, 50–115 kPa (NXP Semiconductors).
//!
//! Communicates over I²C (fixed address `0x60`). The chip returns 10-bit pressure and temperature
//! ADC counts plus four factory-programmed compensation coefficients (`a0`, `b1`, `b2`, `c12`);
//! the compensated pressure is
//! `Pcomp = a0 + (b1 + c12 * Tadc) * Padc + b2 * Tadc`, scaled from 0..1023 to 50..115 kPa.
//!
//! The coefficients are read once in `new`. Each reading writes `CONVERT`, waits 5 ms (the
//! caller supplies a [`DelayNs`]) and reads the four ADC bytes. The temperature is only an
//! approximation (`25 + (Tadc − 498) / −5.35`); the datasheet gives no formula.

use core::ops::{Deref, DerefMut};

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_PADC: u8 = 0x00;
const REG_COEF: u8 = 0x04;
const REG_CONVERT: u8 = 0x12;

const CONVERSION_MS: u32 = 5;
const TEMP_COUNTS_PER_C: f32 = -5.35;
const TEMP_REF_COUNTS: f32 = 498.0;
const TEMP_REF_C: f32 = 25.0;

/// MPL115A2 minimal driver — compensated pressure (Pa) and approximate temperature (°C).
pub struct Mpl115a2Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    a0: f32,
    b1: f32,
    b2: f32,
    c12: f32,
}

fn load<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<(f32, f32, f32, f32), I2C::Error> {
    let mut buf = [0u8; 8];
    register::read_register(i2c, addr, REG_COEF.into(), 1, &mut buf)?;
    Ok((
        i16::from_be_bytes([buf[0], buf[1]]) as f32 / 8.0,
        i16::from_be_bytes([buf[2], buf[3]]) as f32 / 8192.0,
        i16::from_be_bytes([buf[4], buf[5]]) as f32 / 16384.0,
        i16::from_be_bytes([buf[6], buf[7]]) as f32 / 16_777_216.0,
    ))
}

fn temperature_c(tadc: u16) -> f32 {
    TEMP_REF_C + (tadc as f32 - TEMP_REF_COUNTS) / TEMP_COUNTS_PER_C
}

impl<I2C: I2c> Mpl115a2Minimal<I2C> {
    /// Create the driver and read the coefficient block.
    ///
    /// `addr` is the 7-bit I²C address (`0x60`).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let (a0, b1, b2, c12) = load(&mut i2c, addr)?;
        Ok(Self { i2c, addr, a0, b1, b2, c12 })
    }

    fn convert<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, REG_CONVERT.into(), 1, &[0x00])?;
        delay.delay_ms(CONVERSION_MS);
        let mut buf = [0u8; 4];
        register::read_register(&mut self.i2c, self.addr, REG_PADC.into(), 1, &mut buf)?;
        Ok((u16::from_be_bytes([buf[0], buf[1]]) >> 6, u16::from_be_bytes([buf[2], buf[3]]) >> 6))
    }

    fn compensate_counts(&self, padc: u16, tadc: u16) -> f32 {
        let (p, t) = (padc as f32, tadc as f32);
        let pcomp = self.a0 + (self.b1 + self.c12 * t) * p + self.b2 * t;
        (pcomp * (115.0 - 50.0) / 1023.0 + 50.0) * 1000.0
    }

    /// Run one conversion and return the compensated pressure in pascals.
    pub fn read_pressure<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        let (padc, tadc) = self.convert(delay)?;
        Ok(self.compensate_counts(padc, tadc))
    }

    /// Run one conversion and return an approximate temperature in degrees Celsius
    /// (uncalibrated, accurate to a few degrees — use for trends only).
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        let (_, tadc) = self.convert(delay)?;
        Ok(temperature_c(tadc))
    }
}

/// MPL115A2 full driver — combined reading, raw counts, coefficients, hPa and altitude.
pub struct Mpl115a2Full<I2C> {
    base: Mpl115a2Minimal<I2C>,
}

impl<I2C> Deref for Mpl115a2Full<I2C> {
    type Target = Mpl115a2Minimal<I2C>;
    fn deref(&self) -> &Self::Target {
        &self.base
    }
}

impl<I2C> DerefMut for Mpl115a2Full<I2C> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.base
    }
}

impl<I2C: I2c> Mpl115a2Full<I2C> {
    /// Create the driver and read the coefficient block.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { base: Mpl115a2Minimal::new(i2c, addr)? })
    }

    /// Run one conversion and return `(pressure_pa, temperature_c)`.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), I2C::Error> {
        let (padc, tadc) = self.base.convert(delay)?;
        Ok((self.base.compensate_counts(padc, tadc), temperature_c(tadc)))
    }

    /// Run one conversion and return the raw 10-bit ADC counts `(padc, tadc)`.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16), I2C::Error> {
        self.base.convert(delay)
    }

    /// Return the cached coefficients `(a0, b1, b2, c12)`.
    pub fn coefficients(&self) -> (f32, f32, f32, f32) {
        (self.base.a0, self.base.b1, self.base.b2, self.base.c12)
    }

    /// Re-read the coefficient block (e.g. after a power cycle).
    pub fn reload_coefficients(&mut self) -> Result<(), I2C::Error> {
        let (a0, b1, b2, c12) = load(&mut self.base.i2c, self.base.addr)?;
        self.base.a0 = a0;
        self.base.b1 = b1;
        self.base.b2 = b2;
        self.base.c12 = c12;
        Ok(())
    }

    /// Compute pressure in pascals from raw counts and the cached coefficients (no bus access).
    pub fn compensate(&self, padc: u16, tadc: u16) -> f32 {
        self.base.compensate_counts(padc, tadc)
    }

    /// Run one conversion and return the pressure in hectopascals.
    pub fn pressure_hpa<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        Ok(self.base.read_pressure(delay)? / 100.0)
    }

    /// Compute the altitude in metres from a fresh pressure reading (barometric formula,
    /// informative). `sea_level_pa` is typically 101325.0.
    pub fn altitude<D: DelayNs>(&mut self, sea_level_pa: f32, delay: &mut D) -> Result<f32, I2C::Error> {
        let p = self.base.read_pressure(delay)?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_pa, 1.0 / 5.255)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x60;

    // Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
    // ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
    fn coef_tx() -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![REG_COEF], vec![0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8])
    }

    fn convert_tx() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write(ADDR, vec![REG_CONVERT, 0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_PADC], vec![0x66, 0x80, 0x7E, 0xC0]),
        ]
    }

    #[test]
    fn minimal_pressure_and_temperature() {
        let mut tx = vec![coef_tx()];
        tx.extend(convert_tx());
        tx.extend(convert_tx());
        let mut i2c = I2cMock::new(&tx);
        let mut chip = Mpl115a2Minimal::new(i2c.clone(), ADDR).expect("init");
        let mut delay = NoopDelay::new();
        let p = chip.read_pressure(&mut delay).unwrap();
        assert!((p - 96586.0).abs() < 30.0, "p = {}", p);
        let t = chip.read_temperature(&mut delay).unwrap();
        assert!((t - (25.0 + 9.0 / -5.35)).abs() < 1e-4, "t = {}", t);
        i2c.done();
    }

    #[test]
    fn full_api() {
        let mut tx = vec![coef_tx()];
        for _ in 0..4 {
            tx.extend(convert_tx());
        }
        tx.push(coef_tx());
        let mut i2c = I2cMock::new(&tx);
        let mut chip = Mpl115a2Full::new(i2c.clone(), ADDR).expect("init");
        let mut delay = NoopDelay::new();

        let (a0, b1, b2, c12) = chip.coefficients();
        assert!((a0 - 2009.75).abs() < 1e-3);
        assert!((b1 + 2.375854).abs() < 1e-4);
        assert!((b2 + 0.92047).abs() < 1e-4);
        assert!((c12 - 0.000790).abs() < 1e-5);

        let (p, t) = chip.read(&mut delay).unwrap();
        assert!((p - 96586.0).abs() < 30.0);
        assert!((t - (25.0 + 9.0 / -5.35)).abs() < 1e-4);
        assert_eq!(chip.read_raw(&mut delay).unwrap(), (410, 507));
        assert!((chip.compensate(410, 507) - p).abs() < 1e-2);
        assert!(chip.compensate(0, 0) > 40000.0 && chip.compensate(1023, 0) < 130000.0);
        assert!((chip.pressure_hpa(&mut delay).unwrap() - p / 100.0).abs() < 1e-3);
        let alt = chip.altitude(101325.0, &mut delay).unwrap();
        assert!((alt - 44330.0 * (1.0 - libm::powf(p / 101325.0, 1.0 / 5.255))).abs() < 0.5, "alt = {}", alt);
        chip.reload_coefficients().unwrap();
        i2c.done();
    }
}
