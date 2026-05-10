//! INA226 — 36 V, 16-bit current/voltage/power monitor (Texas Instruments).
//!
//! Communicates over I²C (up to 400 kHz fast mode, 2.94 MHz HS mode).
//! Current and power readings require the Calibration Register to be programmed;
//! both [`Ina226Minimal::new`] and [`Ina226Full::new`] do this automatically.
//!
//! ## Alert function constants
//!
//! Pass one of [`SOL`], [`SUL`], [`BOL`], [`BUL`], [`POL`], or [`CNVR`]
//! to [`Ina226Full::set_alert`].

use embedded_hal::i2c::I2c;

const REG_CONFIG: u8 = 0x00;
const REG_SHUNT: u8 = 0x01;
const REG_BUS: u8 = 0x02;
const REG_POWER: u8 = 0x03;
const REG_CURRENT: u8 = 0x04;
const REG_CAL: u8 = 0x05;
const REG_MASK: u8 = 0x06;
const REG_ALERT: u8 = 0x07;
const REG_MFR_ID: u8 = 0xFE;
const REG_DIE_ID: u8 = 0xFF;

const CONFIG_DEFAULT: u16 = 0x4127;

/// Alert function constant: shunt voltage over-limit.
pub const SOL: u16 = 0x8000;
/// Alert function constant: shunt voltage under-limit.
pub const SUL: u16 = 0x4000;
/// Alert function constant: bus voltage over-limit.
pub const BOL: u16 = 0x2000;
/// Alert function constant: bus voltage under-limit.
pub const BUL: u16 = 0x1000;
/// Alert function constant: power over-limit.
pub const POL: u16 = 0x0800;
/// Alert function constant: conversion ready.
pub const CNVR: u16 = 0x0400;
/// Alert Function Flag — readable from [`Ina226Full::alert_flags`].
pub const AFF: u16 = 0x0010;

/// INA226 minimal driver — bus voltage, shunt voltage, current, and power.
///
/// Writes the Calibration Register automatically at construction. The default
/// configuration baked in is: MODE=7 (shunt+bus continuous), VBUSCT=4 (1.1 ms),
/// VSHCT=4 (1.1 ms), AVG=0 (1 sample).
pub struct Ina226Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    current_lsb: f32,
    cal: u16,
}

impl<I2C: I2c> Ina226Minimal<I2C> {
    /// Create a new `Ina226Minimal` and program the Calibration Register.
    ///
    /// # Arguments
    /// * `i2c`         — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`        — 7-bit device address (typically `0x40`–`0x4F`).
    /// * `r_shunt`     — Shunt resistor value in ohms.
    /// * `max_current` — Maximum expected current in amperes; determines the current LSB.
    pub fn new(mut i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let current_lsb = max_current / 32768.0;
        let cal = (0.00512 / (current_lsb * r_shunt)) as u16;
        write_reg(&mut i2c, addr, REG_CONFIG, CONFIG_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_CAL, cal)?;
        Ok(Self { i2c, addr, current_lsb, cal })
    }

    /// Read bus voltage.
    ///
    /// Returns voltage in volts. LSB = 1.25 mV.
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_BUS)? as f32 * 1.25e-3)
    }

    /// Read differential shunt voltage.
    ///
    /// Returns voltage in volts, signed. LSB = 2.5 µV.
    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_SHUNT)? as f32 * 2.5e-6)
    }

    /// Read calculated current through the shunt.
    ///
    /// Returns current in amperes, signed.
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_CURRENT)? as f32 * self.current_lsb)
    }

    /// Read calculated power.
    ///
    /// Returns power in watts. Formula: raw × 25 × current_LSB.
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_POWER)? as f32 * 25.0 * self.current_lsb)
    }
}

/// INA226 full driver — extends [`Ina226Minimal`] with configuration and alert support.
///
/// Provides access to the Configuration Register, alert pin, conversion-ready
/// and overflow flags, and power management (shutdown/wake).
pub struct Ina226Full<I2C> {
    inner: Ina226Minimal<I2C>,
    mode: u8,
}

impl<I2C: I2c> Ina226Full<I2C> {
    /// Create a new `Ina226Full` and program the Calibration Register.
    ///
    /// Same arguments as [`Ina226Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let inner = Ina226Minimal::new(i2c, addr, r_shunt, max_current)?;
        Ok(Self { inner, mode: 0x07 })
    }

    /// Read bus voltage. Delegates to the inner [`Ina226Minimal`].
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.voltage()
    }

    /// Read shunt voltage. Delegates to the inner [`Ina226Minimal`].
    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.shunt_voltage()
    }

    /// Read current. Delegates to the inner [`Ina226Minimal`].
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        self.inner.current()
    }

    /// Read power. Delegates to the inner [`Ina226Minimal`].
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        self.inner.power()
    }

    /// Write the Configuration Register.
    ///
    /// # Arguments
    /// * `avg`     — Averaging count selector 0–7 (0 = 1 sample … 7 = 1024 samples).
    /// * `vbus_ct` — Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
    /// * `vsh_ct`  — Shunt voltage conversion time selector 0–7 (default 4 = 1.1 ms).
    /// * `mode`    — Operating mode 0–7 (7 = shunt+bus continuous).
    pub fn configure(&mut self, avg: u8, vbus_ct: u8, vsh_ct: u8, mode: u8) -> Result<(), I2C::Error> {
        let config = ((avg as u16 & 0x07) << 9)
            | ((vbus_ct as u16 & 0x07) << 6)
            | ((vsh_ct as u16 & 0x07) << 3)
            | (mode as u16 & 0x07);
        self.mode = mode & 0x07;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)
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
    /// Only one alert function can be active at a time. `limit` is in natural
    /// units — volts for [`SOL`]/[`SUL`]/[`BOL`]/[`BUL`], watts for [`POL`].
    ///
    /// # Arguments
    /// * `function` — Alert function constant ([`SOL`], [`SUL`], [`BOL`], [`BUL`], [`POL`], [`CNVR`]).
    /// * `limit`    — Threshold in natural units.
    /// * `polarity` — `false` = active-low (default), `true` = active-high.
    /// * `latch`    — `false` = transparent (default), `true` = latch until Mask/Enable is read.
    pub fn set_alert(&mut self, function: u16, limit: f32, polarity: bool, latch: bool) -> Result<(), I2C::Error> {
        let raw: u16 = if function == SOL || function == SUL {
            (limit / 2.5e-6) as u16
        } else if function == BOL || function == BUL {
            (limit / 1.25e-3) as u16
        } else if function == POL {
            (limit / (25.0 * self.inner.current_lsb)) as u16
        } else {
            0
        };
        let mask = function | if polarity { 0x0002 } else { 0 } | if latch { 0x0001 } else { 0 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK, mask)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ALERT, raw)
    }

    /// Read the Mask/Enable Register.
    ///
    /// Returns the raw 16-bit value containing alert and status flags.
    pub fn alert_flags(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)
    }

    /// Reset all registers to power-on defaults and re-write the Calibration Register.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, 0x8000)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CAL, self.inner.cal)
    }

    /// Enter power-down mode (MODE = 000), saving the current mode for [`wake`](Self::wake).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        self.mode = (config & 0x07) as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config & 0xFFF8)
    }

    /// Restore the operating mode saved by [`shutdown`](Self::shutdown).
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (config & 0xFFF8) | self.mode as u16)
    }

    /// Read the Manufacturer ID register. Expect `0x5449` (Texas Instruments).
    pub fn manufacturer_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MFR_ID)
    }

    /// Read the Die ID register. Expect `0x2260`.
    pub fn die_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_DIE_ID)
    }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    let buf = [reg, (value >> 8) as u8, (value & 0xFF) as u8];
    i2c.write(addr, &buf)
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(((buf[0] as u16) << 8) | buf[1] as u16)
}

fn read_reg_signed<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<i16, I2C::Error> {
    Ok(read_reg(i2c, addr, reg)? as i16)
}
