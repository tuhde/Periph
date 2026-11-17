//! INA260 — 36 V, 16-bit current/voltage/power monitor with integrated 2 mΩ shunt (Texas Instruments).
//!
//! Communicates over I²C (up to 400 kHz fast mode, 2.94 MHz HS mode). The
//! shunt is integrated and the current, bus-voltage and power registers have
//! fixed LSBs (1.25 mA, 1.25 mV, 10 mW), so no calibration is needed and
//! constructing a driver makes no bus access.
//!
//! ## Constants
//!
//! Alert functions ([`OCL`], [`UCL`], [`BOL`], [`BUL`], [`POL`], [`CNVR`]) are
//! passed to [`Ina260Full::set_alert`]; the `AVG_*`, `CT_*` and `MODE_*`
//! selectors are passed to [`Ina260Full::configure`] / [`Ina260Full::trigger`].
//! They live in this module (`periph::chips::power::ina260::…`) and are not
//! re-exported at the category level, to avoid clashing with the INA226 /
//! INA3221 constants of the same names.

use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_CONFIG: u8 = 0x00;
const REG_CURRENT: u8 = 0x01;
const REG_BUS: u8 = 0x02;
const REG_POWER: u8 = 0x03;
const REG_MASK: u8 = 0x06;
const REG_ALERT: u8 = 0x07;
const REG_MFR_ID: u8 = 0xFE;
const REG_DIE_ID: u8 = 0xFF;

/// Configuration register base: bits 14:12 must be written as `110`.
const CONFIG_BASE: u16 = 0x6000;

/// Alert function constant: over-current limit.
pub const OCL: u16 = 0x8000;
/// Alert function constant: under-current limit.
pub const UCL: u16 = 0x4000;
/// Alert function constant: bus voltage over-limit.
pub const BOL: u16 = 0x2000;
/// Alert function constant: bus voltage under-limit.
pub const BUL: u16 = 0x1000;
/// Alert function constant: power over-limit.
pub const POL: u16 = 0x0800;
/// Alert function constant: conversion ready.
pub const CNVR: u16 = 0x0400;
/// Alert Function Flag — readable from [`Ina260Full::alert_flags`].
pub const AFF: u16 = 0x0010;

/// Averaging selector: 1 sample.
pub const AVG_1: u8 = 0;
/// Averaging selector: 4 samples.
pub const AVG_4: u8 = 1;
/// Averaging selector: 16 samples.
pub const AVG_16: u8 = 2;
/// Averaging selector: 64 samples.
pub const AVG_64: u8 = 3;
/// Averaging selector: 128 samples.
pub const AVG_128: u8 = 4;
/// Averaging selector: 256 samples.
pub const AVG_256: u8 = 5;
/// Averaging selector: 512 samples.
pub const AVG_512: u8 = 6;
/// Averaging selector: 1024 samples.
pub const AVG_1024: u8 = 7;

/// Conversion time selector: 140 µs.
pub const CT_140US: u8 = 0;
/// Conversion time selector: 204 µs.
pub const CT_204US: u8 = 1;
/// Conversion time selector: 332 µs.
pub const CT_332US: u8 = 2;
/// Conversion time selector: 588 µs.
pub const CT_588US: u8 = 3;
/// Conversion time selector: 1.1 ms (power-on default).
pub const CT_1100US: u8 = 4;
/// Conversion time selector: 2.116 ms.
pub const CT_2116US: u8 = 5;
/// Conversion time selector: 4.156 ms.
pub const CT_4156US: u8 = 6;
/// Conversion time selector: 8.244 ms.
pub const CT_8244US: u8 = 7;

/// Operating mode: power-down.
pub const MODE_POWER_DOWN: u8 = 0;
/// Operating mode: shunt current, triggered.
pub const MODE_SHUNT_TRIGGERED: u8 = 1;
/// Operating mode: bus voltage, triggered.
pub const MODE_BUS_TRIGGERED: u8 = 2;
/// Operating mode: shunt current and bus voltage, triggered.
pub const MODE_SHUNT_BUS_TRIGGERED: u8 = 3;
/// Operating mode: shunt current, continuous.
pub const MODE_SHUNT_CONT: u8 = 5;
/// Operating mode: bus voltage, continuous.
pub const MODE_BUS_CONT: u8 = 6;
/// Operating mode: shunt current and bus voltage, continuous (power-on default).
pub const MODE_SHUNT_BUS_CONT: u8 = 7;

/// INA260 minimal driver — bus voltage, current, and power.
///
/// Relies on the chip's power-on state: MODE=7 (shunt+bus continuous),
/// VBUSCT=4 (1.1 ms), ISHCT=4 (1.1 ms), AVG=0 (1 sample). Nothing is written
/// at construction.
pub struct Ina260Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Ina260Minimal<I2C> {
    /// Create a new `Ina260Minimal`. Makes no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x40`–`0x4F`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { i2c, addr })
    }

    /// Read bus voltage.
    ///
    /// Returns voltage in volts. LSB = 1.25 mV.
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_BUS)? as f32 * 1.25e-3)
    }

    /// Read current through the integrated shunt.
    ///
    /// Returns current in amperes, signed. LSB = 1.25 mA; positive flows IN+ → IN−.
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_CURRENT)? as f32 * 1.25e-3)
    }

    /// Read calculated power.
    ///
    /// Returns power in watts, always positive. LSB = 10 mW.
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_POWER)? as f32 * 10e-3)
    }
}

/// INA260 full driver — extends [`Ina260Minimal`] with configuration and alert support.
///
/// Provides access to the Configuration Register, triggered conversions, alert
/// pin, conversion-ready and overflow flags, and power management (shutdown/wake).
pub struct Ina260Full<I2C> {
    inner: Ina260Minimal<I2C>,
    avg: u8,
    vbus_ct: u8,
    ish_ct: u8,
    mode: u8,
}

impl<I2C: I2c> Ina260Full<I2C> {
    /// Create a new `Ina260Full`. Makes no bus access.
    ///
    /// Same arguments as [`Ina260Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Ina260Minimal::new(i2c, addr)?;
        Ok(Self { inner, avg: 0, vbus_ct: 4, ish_ct: 4, mode: 7 })
    }

    /// Read bus voltage. Delegates to the inner [`Ina260Minimal`].
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.voltage()
    }

    /// Read current. Delegates to the inner [`Ina260Minimal`].
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        self.inner.current()
    }

    /// Read power. Delegates to the inner [`Ina260Minimal`].
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        self.inner.power()
    }

    fn write_config(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let config = CONFIG_BASE
            | ((self.avg as u16) << 9)
            | ((self.vbus_ct as u16) << 6)
            | ((self.ish_ct as u16) << 3)
            | (mode as u16 & 0x07);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)
    }

    /// Write the Configuration Register (bits 14:12 kept at `110`).
    ///
    /// # Arguments
    /// * `avg`     — Averaging count selector 0–7 ([`AVG_1`] … [`AVG_1024`]).
    /// * `vbus_ct` — Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
    /// * `ish_ct`  — Shunt current conversion time selector 0–7 (default 4 = 1.1 ms).
    /// * `mode`    — Operating mode 0–7 (7 = shunt+bus continuous).
    pub fn configure(&mut self, avg: u8, vbus_ct: u8, ish_ct: u8, mode: u8) -> Result<(), I2C::Error> {
        self.avg = avg & 0x07;
        self.vbus_ct = vbus_ct & 0x07;
        self.ish_ct = ish_ct & 0x07;
        self.mode = mode & 0x07;
        self.write_config(self.mode)
    }

    /// Start one triggered conversion with the current settings (rewrites the
    /// Configuration Register). Poll [`conversion_ready`](Self::conversion_ready) for completion.
    ///
    /// `mode` is [`MODE_SHUNT_TRIGGERED`], [`MODE_BUS_TRIGGERED`] or [`MODE_SHUNT_BUS_TRIGGERED`].
    pub fn trigger(&mut self, mode: u8) -> Result<(), I2C::Error> {
        self.write_config(mode)
    }

    /// Read the Conversion Ready Flag (CVRF) from the Mask/Enable Register.
    ///
    /// **Note:** reading Mask/Enable clears CVRF — read it last if also checking other flags.
    pub fn conversion_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)? & 0x0008 != 0)
    }

    /// Read the Math Overflow Flag (OVF) from the Mask/Enable Register.
    pub fn overflow(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)? & 0x0004 != 0)
    }

    /// Configure the alert pin function and threshold.
    ///
    /// Writes the Alert Limit register first, then Mask/Enable. Only one alert
    /// function can be active at a time. `limit` is in natural units — amperes
    /// for [`OCL`]/[`UCL`], volts for [`BOL`]/[`BUL`], watts for [`POL`]; ignored
    /// for [`CNVR`] (the limit register is not written).
    ///
    /// # Arguments
    /// * `function` — Alert function constant ([`OCL`], [`UCL`], [`BOL`], [`BUL`], [`POL`], [`CNVR`]).
    /// * `limit`    — Threshold in natural units.
    /// * `polarity` — `false` = active-low (default), `true` = active-high.
    /// * `latch`    — `false` = transparent (default), `true` = latch until Mask/Enable is read.
    pub fn set_alert(&mut self, function: u16, limit: f32, polarity: bool, latch: bool) -> Result<(), I2C::Error> {
        let raw: Option<i16> = if function == OCL || function == UCL || function == BOL || function == BUL {
            Some(round_f32(limit / 1.25e-3) as i16)
        } else if function == POL {
            Some(round_f32(limit / 10e-3) as i16)
        } else {
            None
        };
        if let Some(raw) = raw {
            write_reg(&mut self.inner.i2c, self.inner.addr, REG_ALERT, raw as u16)?;
        }
        let mask = function | if polarity { 0x0002 } else { 0 } | if latch { 0x0001 } else { 0 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK, mask)
    }

    /// Disable all alert functions (write Mask/Enable = 0).
    pub fn clear_alert(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK, 0)
    }

    /// Read the Mask/Enable Register (clears CVRF and, in latch mode, AFF).
    ///
    /// Returns the raw 16-bit value containing alert and status flags.
    pub fn alert_flags(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)
    }

    /// Reset all registers to power-on defaults (RST bit).
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, 0x8000)?;
        self.avg = 0;
        self.vbus_ct = 4;
        self.ish_ct = 4;
        self.mode = 7;
        Ok(())
    }

    /// Enter power-down mode (MODE = 000); the previous mode is kept for [`wake`](Self::wake).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        self.write_config(MODE_POWER_DOWN)
    }

    /// Restore the operating mode that was active before [`shutdown`](Self::shutdown).
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        self.write_config(self.mode)
    }

    /// Read the Manufacturer ID register. Expect `0x5449` (Texas Instruments).
    pub fn manufacturer_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MFR_ID)
    }

    /// Read the Die ID register. `(value >> 4)` is `0x227`; the low nibble is the revision.
    pub fn die_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_DIE_ID)
    }
}

/// Round half away from zero (no_std-safe replacement for `f32::round`).
fn round_f32(x: f32) -> f32 {
    if x < 0.0 { x - 0.5 } else { x + 0.5 }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    register::write_register(i2c, addr, reg.into(), 1, &[(value >> 8) as u8, (value & 0xFF) as u8])
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    register::read_register(i2c, addr, reg.into(), 1, &mut buf)?;
    Ok(((buf[0] as u16) << 8) | buf[1] as u16)
}

fn read_reg_signed<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<i16, I2C::Error> {
    Ok(read_reg(i2c, addr, reg)? as i16)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x40;

    #[test]
    fn full_api() {
        let transactions = vec![
            // voltage(): raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
            I2cTransaction::write_read(ADDR, vec![REG_BUS], vec![0x19, 0x00]),
            // current(): raw signed = -100 (0xFF9C) -> -0.125 A
            I2cTransaction::write_read(ADDR, vec![REG_CURRENT], vec![0xFF, 0x9C]),
            // current(): datasheet example 0x2710 -> 12.5 A
            I2cTransaction::write_read(ADDR, vec![REG_CURRENT], vec![0x27, 0x10]),
            // power(): datasheet example 0x3A7F = 14975 -> 149.75 W
            I2cTransaction::write_read(ADDR, vec![REG_POWER], vec![0x3A, 0x7F]),
            // configure(2, 3, 5, 6) -> 0x6000 | (2<<9) | (3<<6) | (5<<3) | 6 = 0x64EE
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x64, 0xEE]),
            // trigger(3) with the same settings -> 0x64EB
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x64, 0xEB]),
            // conversion_ready(): CVRF bit (0x0008) set
            I2cTransaction::write_read(ADDR, vec![REG_MASK], vec![0x00, 0x08]),
            // overflow(): OVF bit (0x0004) set
            I2cTransaction::write_read(ADDR, vec![REG_MASK], vec![0x00, 0x04]),
            // set_alert(POL, 1.5, true, true): limit 150 (0x0096) first, then mask 0x0803
            I2cTransaction::write(ADDR, vec![REG_ALERT, 0x00, 0x96]),
            I2cTransaction::write(ADDR, vec![REG_MASK, 0x08, 0x03]),
            // set_alert(OCL, 5.0): 4000 (0x0FA0), mask 0x8000
            I2cTransaction::write(ADDR, vec![REG_ALERT, 0x0F, 0xA0]),
            I2cTransaction::write(ADDR, vec![REG_MASK, 0x80, 0x00]),
            // set_alert(UCL, -1.0): -800 -> 0xFCE0
            I2cTransaction::write(ADDR, vec![REG_ALERT, 0xFC, 0xE0]),
            I2cTransaction::write(ADDR, vec![REG_MASK, 0x40, 0x00]),
            // set_alert(CNVR): no limit write
            I2cTransaction::write(ADDR, vec![REG_MASK, 0x04, 0x00]),
            // clear_alert()
            I2cTransaction::write(ADDR, vec![REG_MASK, 0x00, 0x00]),
            // alert_flags()
            I2cTransaction::write_read(ADDR, vec![REG_MASK], vec![0x08, 0x03]),
            // reset(): CONFIG = 0x8000
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x80, 0x00]),
            // shutdown(): MODE = 000 with default settings -> 0x6120
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x61, 0x20]),
            // wake(): MODE = 111 -> 0x6127
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x61, 0x27]),
            // manufacturer_id() / die_id()
            I2cTransaction::write_read(ADDR, vec![REG_MFR_ID], vec![0x54, 0x49]),
            I2cTransaction::write_read(ADDR, vec![REG_DIE_ID], vec![0x22, 0x70]),
        ];
        let i2c = I2cMock::new(&transactions);

        // Construction makes no bus access: the mock has no init transactions.
        let mut sensor = Ina260Full::new(i2c, ADDR).expect("init");

        assert_eq!(sensor.voltage().unwrap(), 8.0);
        assert!((sensor.current().unwrap() - (-0.125)).abs() < 1e-6);
        assert!((sensor.current().unwrap() - 12.5).abs() < 1e-4);
        assert!((sensor.power().unwrap() - 149.75).abs() < 1e-3);

        sensor.configure(2, 3, 5, 6).unwrap();
        sensor.trigger(MODE_SHUNT_BUS_TRIGGERED).unwrap();

        assert!(sensor.conversion_ready().unwrap());
        assert!(sensor.overflow().unwrap());

        sensor.set_alert(POL, 1.5, true, true).unwrap();
        sensor.set_alert(OCL, 5.0, false, false).unwrap();
        sensor.set_alert(UCL, -1.0, false, false).unwrap();
        sensor.set_alert(CNVR, 0.0, false, false).unwrap();
        sensor.clear_alert().unwrap();

        assert_eq!(sensor.alert_flags().unwrap(), 0x0803);

        sensor.reset().unwrap();
        sensor.shutdown().unwrap();
        sensor.wake().unwrap();

        assert_eq!(sensor.manufacturer_id().unwrap(), 0x5449);
        assert_eq!(sensor.die_id().unwrap(), 0x2270);

        sensor.inner.i2c.done();
    }
}
