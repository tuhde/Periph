//! LM25066 — hot-swap controller / PMBus power monitor (Texas Instruments).
//!
//! Communicates over I²C / SMBus (PMBus command codes, 16-bit values little-endian) at one of
//! 27 addresses selected by the three-state ADR0/ADR1/ADR2 pins (`0x10`–`0x17`, `0x40`–`0x47`,
//! `0x50`–`0x5A`). Telemetry is PMBus DIRECT and converted with coefficients that depend on the
//! sense resistor and the CL pin setting.
//!
//! The constructors perform no bus access and never write the chip. The chip switches a power
//! MOSFET: [`Lm25066Full::operation_off`] cuts the load rail and [`Lm25066Full::set_gate_mask`]
//! can stop hardware faults from switching the MOSFET off (which may destroy it); neither is ever
//! called by the drivers themselves. The Full driver's interrupt support is
//! [`Lm25066Full::poll_interrupt`] only: the caller wires it into an ISR (falling edge on SMBA)
//! or a polling loop.
//!
//! ## Constants
//!
//! The `SOURCE_*` alert-mask bit constants live in this module
//! (`periph::chips::power::lm25066::…`) and are not re-exported at the category level.

use embedded_hal::i2c::I2c;

use crate::connection::register;

const CMD_OPERATION: u8 = 0x01;
const CMD_CLEAR_FAULTS: u8 = 0x03;
const CMD_CAPABILITY: u8 = 0x19;
const CMD_VOUT_UV_WARN: u8 = 0x43;
const CMD_OT_FAULT: u8 = 0x4F;
const CMD_OT_WARN: u8 = 0x51;
const CMD_VIN_OV_WARN: u8 = 0x57;
const CMD_VIN_UV_WARN: u8 = 0x58;
const CMD_STATUS_BYTE: u8 = 0x78;
const CMD_STATUS_WORD: u8 = 0x79;
const CMD_STATUS_VOUT: u8 = 0x7A;
const CMD_STATUS_INPUT: u8 = 0x7C;
const CMD_STATUS_TEMP: u8 = 0x7D;
const CMD_STATUS_CML: u8 = 0x7E;
const CMD_STATUS_MFR: u8 = 0x80;
const CMD_READ_VIN: u8 = 0x88;
const CMD_READ_VOUT: u8 = 0x8B;
const CMD_READ_TEMP: u8 = 0x8D;
const CMD_MFR_ID: u8 = 0x99;
const CMD_MFR_MODEL: u8 = 0x9A;
const CMD_MFR_REVISION: u8 = 0x9B;
const CMD_READ_VAUX: u8 = 0xD0;
const CMD_READ_IIN: u8 = 0xD1;
const CMD_READ_PIN: u8 = 0xD2;
const CMD_IIN_WARN: u8 = 0xD3;
const CMD_PIN_WARN: u8 = 0xD4;
const CMD_READ_PIN_PEAK: u8 = 0xD5;
const CMD_CLEAR_PIN_PEAK: u8 = 0xD6;
const CMD_GATE_MASK: u8 = 0xD7;
const CMD_ALERT_MASK: u8 = 0xD8;
const CMD_DEVICE_SETUP: u8 = 0xD9;
const CMD_BLOCK_READ: u8 = 0xDA;
const CMD_SAMPLES_FOR_AVG: u8 = 0xDB;
const CMD_READ_AVG_VIN: u8 = 0xDC;
const CMD_READ_AVG_VOUT: u8 = 0xDD;
const CMD_READ_AVG_IIN: u8 = 0xDE;
const CMD_READ_AVG_PIN: u8 = 0xDF;
const CMD_BLACK_BOX: u8 = 0xE0;
const CMD_DIAGNOSTIC: u8 = 0xE1;
const CMD_AVG_BLOCK_READ: u8 = 0xE2;

/// Alert source: VOUT under-voltage warning.
pub const SOURCE_VOUT_UV_WARN: u16 = 0x8000;
/// Alert source: input current limit warning.
pub const SOURCE_IIN_WARN: u16 = 0x4000;
/// Alert source: VIN under-voltage warning.
pub const SOURCE_VIN_UV_WARN: u16 = 0x2000;
/// Alert source: VIN over-voltage warning.
pub const SOURCE_VIN_OV_WARN: u16 = 0x1000;
/// Alert source: Power Good negated (masked by default).
pub const SOURCE_POWER_GOOD: u16 = 0x0800;
/// Alert source: over-temperature warning.
pub const SOURCE_OT_WARN: u16 = 0x0400;
/// Alert source: over-power limit warning.
pub const SOURCE_PIN_OP_WARN: u16 = 0x0100;
/// Alert source: external MOSFET shorted.
pub const SOURCE_MOSFET_SHORTED: u16 = 0x0040;
/// Alert source: VIN under-voltage fault (masked by default).
pub const SOURCE_VIN_UV_FAULT: u16 = 0x0020;
/// Alert source: VIN over-voltage fault.
pub const SOURCE_VIN_OV_FAULT: u16 = 0x0010;
/// Alert source: IIN / PFET over-current fault.
pub const SOURCE_IIN_FAULT: u16 = 0x0008;
/// Alert source: over-temperature fault.
pub const SOURCE_OT_FAULT: u16 = 0x0004;
/// Alert source: communication fault.
pub const SOURCE_CML_FAULT: u16 = 0x0002;
/// Alert source: circuit-breaker fault.
pub const SOURCE_CB_FAULT: u16 = 0x0001;

/// Errors returned by the LM25066 drivers.
#[derive(Debug)]
pub enum Lm25066Error<E> {
    /// Underlying I²C bus error.
    I2c(E),
    /// A block read returned an unexpected count byte.
    BlockLength,
    /// An argument was outside the range the chip accepts.
    InvalidArgument,
}

impl<E> From<E> for Lm25066Error<E> {
    fn from(e: E) -> Self {
        Lm25066Error::I2c(e)
    }
}

/// Converted `BLOCK_READ` / `AVG_BLOCK_READ` / `BLACK_BOX_READ` telemetry.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Lm25066Block {
    /// Diagnostic word at the time of the block.
    pub diagnostic: u16,
    /// Input current in A.
    pub current: f32,
    /// Output voltage in V.
    pub voltage_out: f32,
    /// Input voltage in V.
    pub voltage_in: f32,
    /// Input power in W.
    pub power: f32,
    /// MOSFET temperature in °C.
    pub temperature: f32,
}

/// All warning / fault thresholds; `None` where disabled.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Lm25066Limits {
    /// VIN over-voltage warning in V.
    pub vin_ov_warn: Option<f32>,
    /// VIN under-voltage warning in V.
    pub vin_uv_warn: Option<f32>,
    /// VOUT under-voltage warning in V.
    pub vout_uv_warn: Option<f32>,
    /// Over-temperature warning in °C.
    pub ot_warn: Option<f32>,
    /// Over-temperature fault in °C.
    pub ot_fault: Option<f32>,
    /// Input over-current warning in A.
    pub iin_warn: Option<f32>,
    /// Input over-power warning in W.
    pub pin_warn: Option<f32>,
}

// PMBus DIRECT coefficients (m, b, R).
#[derive(Clone, Copy)]
struct Coef {
    m: f32,
    b: f32,
    r: i32,
}

const VOLT: Coef = Coef { m: 22070.0, b: -1800.0, r: -2 };
const VAUX: Coef = Coef { m: 3546.0, b: -3.0, r: 0 };
const TEMP: Coef = Coef { m: 16000.0, b: 0.0, r: -3 };

fn pow10(r: i32) -> f32 {
    let mut s = 1.0f32;
    let mut i = 0;
    while i < r {
        s *= 10.0;
        i += 1;
    }
    while i > r {
        s /= 10.0;
        i -= 1;
    }
    s
}

fn from_y(y: u16, c: Coef) -> f32 {
    (y as f32 * pow10(-c.r) - c.b) / c.m
}

fn to_y(x: f32, c: Coef) -> u16 {
    let v = (c.m * x + c.b) * pow10(c.r);
    let y = if v >= 0.0 { (v + 0.5) as i32 } else { (v - 0.5) as i32 };
    y.clamp(0, 0x0FFE) as u16
}

fn amp_coef(rs: f32, high: bool) -> Coef {
    if high {
        Coef { m: 6854.0 * rs, b: -3100.0, r: -2 }
    } else {
        Coef { m: 13661.0 * rs, b: -5200.0, r: -2 }
    }
}

fn watt_coef(rs: f32, high: bool) -> Coef {
    if high {
        Coef { m: 369.0 * rs, b: -1900.0, r: -2 }
    } else {
        Coef { m: 736.0 * rs, b: -3300.0, r: -2 }
    }
}

fn temp_c(word: u16) -> f32 {
    (word as i16) as f32 / 16.0
}

/// LM25066 minimal driver — VIN, VOUT, input current, input power and MOSFET temperature.
///
/// The constructor performs no bus access and never writes the chip.
pub struct Lm25066Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    rs: f32,
    amp: Coef,
    watt: Coef,
}

impl<I2C: I2c> Lm25066Minimal<I2C> {
    /// Create a new `Lm25066Minimal`. No bus access.
    ///
    /// # Arguments
    /// * `i2c`                — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`               — 7-bit device address (`0x10`–`0x17`, `0x40`–`0x47`, `0x50`–`0x5A`).
    /// * `r_sense_mohm`       — Sense resistor in milliohms.
    /// * `current_limit_high` — `false` = CL pin at GND (25 mV), `true` = CL pin at VDD (46 mV).
    pub fn new(i2c: I2C, addr: u8, r_sense_mohm: f32, current_limit_high: bool) -> Self {
        Self {
            i2c,
            addr,
            rs: r_sense_mohm,
            amp: amp_coef(r_sense_mohm, current_limit_high),
            watt: watt_coef(r_sense_mohm, current_limit_high),
        }
    }

    fn read_word(&mut self, cmd: u8) -> Result<u16, Lm25066Error<I2C::Error>> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, cmd as u32, 1, &mut buf)?;
        Ok(buf[0] as u16 | ((buf[1] as u16) << 8))
    }

    fn write_word(&mut self, cmd: u8, value: u16) -> Result<(), Lm25066Error<I2C::Error>> {
        Ok(register::write_register(&mut self.i2c, self.addr, cmd as u32, 1, &[(value & 0xFF) as u8, (value >> 8) as u8])?)
    }

    fn read_byte(&mut self, cmd: u8) -> Result<u8, Lm25066Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, cmd as u32, 1, &mut buf)?;
        Ok(buf[0])
    }

    fn write_byte(&mut self, cmd: u8, value: u8) -> Result<(), Lm25066Error<I2C::Error>> {
        Ok(register::write_register(&mut self.i2c, self.addr, cmd as u32, 1, &[value])?)
    }

    fn read_y(&mut self, cmd: u8) -> Result<u16, Lm25066Error<I2C::Error>> {
        Ok(self.read_word(cmd)? & 0x0FFF)
    }

    /// Read the input voltage in volts (`READ_VIN`).
    pub fn voltage_in(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        Ok(from_y(self.read_y(CMD_READ_VIN)?, VOLT))
    }

    /// Read the output voltage in volts (`READ_VOUT`).
    pub fn voltage_out(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        Ok(from_y(self.read_y(CMD_READ_VOUT)?, VOLT))
    }

    /// Read the instantaneous input current in amperes (`MFR_READ_IIN`).
    pub fn current(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        let amp = self.amp;
        Ok(from_y(self.read_y(CMD_READ_IIN)?, amp))
    }

    /// Read the instantaneous input power in watts (`MFR_READ_PIN`).
    pub fn power(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        let watt = self.watt;
        Ok(from_y(self.read_y(CMD_READ_PIN)?, watt))
    }

    /// Read the external MOSFET temperature in °C (`READ_TEMPERATURE_1`).
    pub fn temperature(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        Ok(temp_c(self.read_word(CMD_READ_TEMP)?))
    }
}

/// LM25066 full driver — extends [`Lm25066Minimal`] with averaging, limits, status, control and alerts.
///
/// WARNING: [`operation_off`](Self::operation_off) cuts the load rail and
/// [`set_gate_mask`](Self::set_gate_mask) can disable fault shut-off of the MOSFET.
pub struct Lm25066Full<I2C> {
    inner: Lm25066Minimal<I2C>,
}

impl<I2C: I2c> Lm25066Full<I2C> {
    /// Create a new `Lm25066Full`. No bus access. Same arguments as [`Lm25066Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_sense_mohm: f32, current_limit_high: bool) -> Self {
        Self { inner: Lm25066Minimal::new(i2c, addr, r_sense_mohm, current_limit_high) }
    }

    /// Read the input voltage in volts.
    pub fn voltage_in(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> { self.inner.voltage_in() }
    /// Read the output voltage in volts.
    pub fn voltage_out(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> { self.inner.voltage_out() }
    /// Read the instantaneous input current in amperes.
    pub fn current(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> { self.inner.current() }
    /// Read the instantaneous input power in watts.
    pub fn power(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> { self.inner.power() }
    /// Read the external MOSFET temperature in °C.
    pub fn temperature(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> { self.inner.temperature() }

    fn block(&mut self, cmd: u8) -> Result<Lm25066Block, Lm25066Error<I2C::Error>> {
        let mut raw = [0u8; 13];
        register::read_register(&mut self.inner.i2c, self.inner.addr, cmd as u32, 1, &mut raw)?;
        if raw[0] != 12 {
            return Err(Lm25066Error::BlockLength);
        }
        let w = |i: usize| raw[1 + 2 * i] as u16 | ((raw[2 + 2 * i] as u16) << 8);
        let (amp, watt) = (self.inner.amp, self.inner.watt);
        Ok(Lm25066Block {
            diagnostic: w(0),
            current: from_y(w(1) & 0x0FFF, amp),
            voltage_out: from_y(w(2) & 0x0FFF, VOLT),
            voltage_in: from_y(w(3) & 0x0FFF, VOLT),
            power: from_y(w(4) & 0x0FFF, watt),
            temperature: temp_c(w(5)),
        })
    }

    fn text<const N: usize, const M: usize>(&mut self, cmd: u8) -> Result<[u8; N], Lm25066Error<I2C::Error>> {
        let mut raw = [0u8; M];
        register::read_register(&mut self.inner.i2c, self.inner.addr, cmd as u32, 1, &mut raw)?;
        if raw[0] as usize != N {
            return Err(Lm25066Error::BlockLength);
        }
        let mut out = [0u8; N];
        out.copy_from_slice(&raw[1..]);
        Ok(out)
    }

    /// Read VAUX in volts (`READ_VAUX`, 0 … 1.16 V at the pin).
    pub fn voltage_aux(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        Ok(from_y(self.inner.read_y(CMD_READ_VAUX)?, VAUX))
    }

    /// `BLOCK_READ`: time-aligned, converted telemetry.
    pub fn read_block(&mut self) -> Result<Lm25066Block, Lm25066Error<I2C::Error>> { self.block(CMD_BLOCK_READ) }
    /// `AVG_BLOCK_READ`: like [`read_block`](Self::read_block) with averaged telemetry.
    pub fn read_average(&mut self) -> Result<Lm25066Block, Lm25066Error<I2C::Error>> { self.block(CMD_AVG_BLOCK_READ) }
    /// `BLACK_BOX_READ`: the snapshot latched at the first SMBA since [`clear_faults`](Self::clear_faults).
    pub fn black_box(&mut self) -> Result<Lm25066Block, Lm25066Error<I2C::Error>> { self.block(CMD_BLACK_BOX) }

    /// Averaged VIN in volts.
    pub fn voltage_in_avg(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        Ok(from_y(self.inner.read_y(CMD_READ_AVG_VIN)?, VOLT))
    }
    /// Averaged VOUT in volts.
    pub fn voltage_out_avg(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        Ok(from_y(self.inner.read_y(CMD_READ_AVG_VOUT)?, VOLT))
    }
    /// Averaged input current in amperes.
    pub fn current_avg(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        let amp = self.inner.amp;
        Ok(from_y(self.inner.read_y(CMD_READ_AVG_IIN)?, amp))
    }
    /// Averaged input power in watts.
    pub fn power_avg(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        let watt = self.inner.watt;
        Ok(from_y(self.inner.read_y(CMD_READ_AVG_PIN)?, watt))
    }
    /// Peak input power since reset / [`clear_power_peak`](Self::clear_power_peak), in watts.
    pub fn power_peak(&mut self) -> Result<f32, Lm25066Error<I2C::Error>> {
        let watt = self.inner.watt;
        Ok(from_y(self.inner.read_y(CMD_READ_PIN_PEAK)?, watt))
    }
    /// Reset the peak-power register (`CLEAR_PIN_PEAK`, send byte).
    pub fn clear_power_peak(&mut self) -> Result<(), Lm25066Error<I2C::Error>> {
        Ok(register::write_register(&mut self.inner.i2c, self.inner.addr, CMD_CLEAR_PIN_PEAK as u32, 1, &[])?)
    }

    /// Average over `2^exponent` samples; `exponent` must be 0–12 (else [`Lm25066Error::InvalidArgument`]).
    pub fn set_averaging(&mut self, exponent: u8) -> Result<(), Lm25066Error<I2C::Error>> {
        if exponent > 12 {
            return Err(Lm25066Error::InvalidArgument);
        }
        self.inner.write_byte(CMD_SAMPLES_FOR_AVG, exponent)
    }
    /// Read `SAMPLES_FOR_AVG` (the exponent).
    pub fn get_averaging(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_SAMPLES_FOR_AVG) }

    fn set_limit(&mut self, cmd: u8, value: Option<f32>, c: Coef, disabled: u16) -> Result<(), Lm25066Error<I2C::Error>> {
        let y = match value {
            Some(x) => to_y(x, c),
            None => disabled,
        };
        self.inner.write_word(cmd, y)
    }

    fn get_limit(&mut self, cmd: u8, c: Coef, disabled: u16) -> Result<Option<f32>, Lm25066Error<I2C::Error>> {
        let y = self.inner.read_y(cmd)?;
        Ok(if y == disabled { None } else { Some(from_y(y, c)) })
    }

    /// VIN over-voltage warning in volts; `None` disables.
    pub fn set_vin_ov_warn(&mut self, volts: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        self.set_limit(CMD_VIN_OV_WARN, volts, VOLT, 0x0FFF)
    }
    /// VIN under-voltage warning in volts; `None` disables.
    pub fn set_vin_uv_warn(&mut self, volts: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        self.set_limit(CMD_VIN_UV_WARN, volts, VOLT, 0x0000)
    }
    /// VOUT under-voltage warning in volts; `None` disables.
    pub fn set_vout_uv_warn(&mut self, volts: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        self.set_limit(CMD_VOUT_UV_WARN, volts, VOLT, 0x0000)
    }
    /// Over-temperature warning in °C; `None` disables.
    pub fn set_ot_warn(&mut self, celsius: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        self.set_limit(CMD_OT_WARN, celsius, TEMP, 0x0FFF)
    }
    /// Over-temperature fault in °C; `None` disables.
    pub fn set_ot_fault(&mut self, celsius: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        self.set_limit(CMD_OT_FAULT, celsius, TEMP, 0x0FFF)
    }
    /// Input over-current warning in amperes; `None` disables.
    pub fn set_iin_warn(&mut self, amps: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        let amp = self.inner.amp;
        self.set_limit(CMD_IIN_WARN, amps, amp, 0x0FFF)
    }
    /// Input over-power warning in watts; `None` disables.
    pub fn set_pin_warn(&mut self, watts: Option<f32>) -> Result<(), Lm25066Error<I2C::Error>> {
        let watt = self.inner.watt;
        self.set_limit(CMD_PIN_WARN, watts, watt, 0x0FFF)
    }

    /// Read all warning / fault thresholds (`None` where disabled).
    pub fn get_limits(&mut self) -> Result<Lm25066Limits, Lm25066Error<I2C::Error>> {
        let (amp, watt) = (self.inner.amp, self.inner.watt);
        Ok(Lm25066Limits {
            vin_ov_warn: self.get_limit(CMD_VIN_OV_WARN, VOLT, 0x0FFF)?,
            vin_uv_warn: self.get_limit(CMD_VIN_UV_WARN, VOLT, 0x0000)?,
            vout_uv_warn: self.get_limit(CMD_VOUT_UV_WARN, VOLT, 0x0000)?,
            ot_warn: self.get_limit(CMD_OT_WARN, TEMP, 0x0FFF)?,
            ot_fault: self.get_limit(CMD_OT_FAULT, TEMP, 0x0FFF)?,
            iin_warn: self.get_limit(CMD_IIN_WARN, amp, 0x0FFF)?,
            pin_warn: self.get_limit(CMD_PIN_WARN, watt, 0x0FFF)?,
        })
    }

    /// `STATUS_BYTE`.
    pub fn status_byte(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_STATUS_BYTE) }
    /// `STATUS_WORD`.
    pub fn status_word(&mut self) -> Result<u16, Lm25066Error<I2C::Error>> { self.inner.read_word(CMD_STATUS_WORD) }
    /// `STATUS_VOUT`.
    pub fn status_vout(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_STATUS_VOUT) }
    /// `STATUS_INPUT`.
    pub fn status_input(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_STATUS_INPUT) }
    /// `STATUS_TEMPERATURE`.
    pub fn status_temperature(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_STATUS_TEMP) }
    /// `STATUS_CML`.
    pub fn status_cml(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_STATUS_CML) }
    /// `STATUS_MFR_SPECIFIC`.
    pub fn status_mfr(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_STATUS_MFR) }
    /// `READ_DIAGNOSTIC_WORD`: all faults and warnings.
    pub fn diagnostic_word(&mut self) -> Result<u16, Lm25066Error<I2C::Error>> { self.inner.read_word(CMD_DIAGNOSTIC) }

    /// `CLEAR_FAULTS` (send byte); does not switch the MOSFET on.
    pub fn clear_faults(&mut self) -> Result<(), Lm25066Error<I2C::Error>> {
        Ok(register::write_register(&mut self.inner.i2c, self.inner.addr, CMD_CLEAR_FAULTS as u32, 1, &[])?)
    }
    /// `OPERATION` ← `0x80`: switches the load on.
    pub fn operation_on(&mut self) -> Result<(), Lm25066Error<I2C::Error>> { self.inner.write_byte(CMD_OPERATION, 0x80) }
    /// `OPERATION` ← `0x00`: CUTS THE LOAD RAIL.
    pub fn operation_off(&mut self) -> Result<(), Lm25066Error<I2C::Error>> { self.inner.write_byte(CMD_OPERATION, 0x00) }
    /// `true` if `OPERATION` reads `0x80` and the `STATUS_BYTE` OFF bit is clear.
    pub fn is_on(&mut self) -> Result<bool, Lm25066Error<I2C::Error>> {
        let op = self.inner.read_byte(CMD_OPERATION)?;
        let st = self.inner.read_byte(CMD_STATUS_BYTE)?;
        Ok(op == 0x80 && st & 0x40 == 0)
    }

    /// Read `DEVICE_SETUP`.
    pub fn device_setup(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_DEVICE_SETUP) }

    /// Override the RETRY / CL / CB pin settings from software; only the `Some` arguments are
    /// overridden. `retry_code` is 0–7 (bits 7:5: 0 pin, 1 none, 2 = 1 retry, 3 = 2, 4 = 4, 5 = 8,
    /// 6 = 16, 7 = unlimited); a larger value returns [`Lm25066Error::InvalidArgument`]. Updates
    /// the driver coefficients when `current_limit_high` is given.
    pub fn configure_device(
        &mut self, retry_code: Option<u8>, current_limit_high: Option<bool>, cb_ratio_high: Option<bool>,
    ) -> Result<(), Lm25066Error<I2C::Error>> {
        let mut value = 0u8;
        if let Some(r) = retry_code {
            if r > 7 {
                return Err(Lm25066Error::InvalidArgument);
            }
            value |= r << 5;
        }
        if let Some(h) = current_limit_high {
            value |= 0x04 | if h { 0x10 } else { 0 };
        }
        if let Some(h) = cb_ratio_high {
            value |= 0x02 | if h { 0x08 } else { 0 };
        }
        self.inner.write_byte(CMD_DEVICE_SETUP, value)?;
        if let Some(h) = current_limit_high {
            let rs = self.inner.rs;
            self.inner.amp = amp_coef(rs, h);
            self.inner.watt = watt_coef(rs, h);
        }
        Ok(())
    }

    /// Write `GATE_MASK`. DANGEROUS: can disable fault shut-off of the MOSFET. Only bits 5, 4 and 2
    /// may be set (else [`Lm25066Error::InvalidArgument`]).
    pub fn set_gate_mask(&mut self, mask: u8) -> Result<(), Lm25066Error<I2C::Error>> {
        if mask & !0x34 != 0 {
            return Err(Lm25066Error::InvalidArgument);
        }
        self.inner.write_byte(CMD_GATE_MASK, mask)
    }
    /// Read `GATE_MASK`.
    pub fn get_gate_mask(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_GATE_MASK) }
    /// Read `CAPABILITY`.
    pub fn capability(&mut self) -> Result<u8, Lm25066Error<I2C::Error>> { self.inner.read_byte(CMD_CAPABILITY) }

    /// `MFR_ID` as raw ASCII bytes (`NSC`).
    pub fn manufacturer_id(&mut self) -> Result<[u8; 3], Lm25066Error<I2C::Error>> { self.text::<3, 4>(CMD_MFR_ID) }
    /// `MFR_MODEL` as raw ASCII bytes, NUL padded (`LM25066\0`).
    pub fn model(&mut self) -> Result<[u8; 8], Lm25066Error<I2C::Error>> { self.text::<8, 9>(CMD_MFR_MODEL) }
    /// `MFR_REVISION` as raw ASCII bytes (`AA`).
    pub fn revision(&mut self) -> Result<[u8; 2], Lm25066Error<I2C::Error>> { self.text::<2, 3>(CMD_MFR_REVISION) }

    /// Unmask alert source bit(s) in `ALERT_MASK` (read-modify-write); use the `SOURCE_*` constants.
    pub fn enable_interrupt(&mut self, source: u16) -> Result<(), Lm25066Error<I2C::Error>> {
        let m = self.inner.read_word(CMD_ALERT_MASK)?;
        self.inner.write_word(CMD_ALERT_MASK, m & !source)
    }
    /// Mask alert source bit(s) in `ALERT_MASK` (read-modify-write); use the `SOURCE_*` constants.
    pub fn disable_interrupt(&mut self, source: u16) -> Result<(), Lm25066Error<I2C::Error>> {
        let m = self.inner.read_word(CMD_ALERT_MASK)?;
        self.inner.write_word(CMD_ALERT_MASK, m | source)
    }
    /// Read `READ_DIAGNOSTIC_WORD` (does not clear; call [`clear_faults`](Self::clear_faults)).
    /// The caller is responsible for wiring this into an ISR (falling edge on SMBA) or a polling loop.
    pub fn poll_interrupt(&mut self) -> Result<u16, Lm25066Error<I2C::Error>> { self.inner.read_word(CMD_DIAGNOSTIC) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x40;

    fn rd(cmd: u8, value: u16) -> T {
        T::write_read(ADDR, vec![cmd], vec![(value & 0xFF) as u8, (value >> 8) as u8])
    }
    fn rdb(cmd: u8, value: u8) -> T {
        T::write_read(ADDR, vec![cmd], vec![value])
    }
    fn wr(cmd: u8, value: u16) -> T {
        T::write(ADDR, vec![cmd, (value & 0xFF) as u8, (value >> 8) as u8])
    }
    fn wrb(cmd: u8, value: u8) -> T {
        T::write(ADDR, vec![cmd, value])
    }
    fn blk(cmd: u8, words: [u16; 6]) -> T {
        let mut d = vec![12u8];
        for w in words {
            d.push((w & 0xFF) as u8);
            d.push((w >> 8) as u8);
        }
        T::write_read(ADDR, vec![cmd], d)
    }
    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }

    #[test]
    fn minimal_cl_gnd() {
        let t = [rd(0x88, 2000), rd(0x8B, 0xF000 | 1500), rd(0xD1, 648), rd(0xD2, 1000), rd(0x8D, 0x0190), rd(0x8D, 0xFF00)];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Minimal::new(i2c.clone(), ADDR, 5.0, false);
        assert!(near(c.voltage_in().unwrap(), 201800.0 / 22070.0));
        assert!(near(c.voltage_out().unwrap(), 151800.0 / 22070.0));
        assert!(near(c.current().unwrap(), 70000.0 / 68305.0));
        assert!(near(c.power().unwrap(), 103300.0 / (736.0 * 5.0)));
        assert!(near(c.temperature().unwrap(), 25.0));
        assert!(near(c.temperature().unwrap(), -16.0));
        i2c.done();
    }

    #[test]
    fn minimal_cl_vdd() {
        let t = [rd(0xD1, 648), rd(0xD2, 1000)];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Minimal::new(i2c.clone(), ADDR, 5.0, true);
        assert!(near(c.current().unwrap(), 67900.0 / (6854.0 * 5.0)));
        assert!(near(c.power().unwrap(), 101900.0 / (369.0 * 5.0)));
        i2c.done();
    }

    #[test]
    fn constructor_makes_no_bus_access() {
        let mut i2c = I2cMock::new(&[]);
        let _c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        i2c.done();
    }

    #[test]
    fn blocks_and_aux() {
        let t = [
            rd(0xD0, 3546 - 3),
            blk(0xDA, [0x0460, 648, 1500, 2000, 1000, 0x0190]),
            blk(0xE2, [0, 0, 0, 0, 0, 0]),
            blk(0xE0, [1, 0, 0, 0, 0, 0]),
            T::write_read(ADDR, vec![0xDA], vec![5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        assert!(near(c.voltage_aux().unwrap(), 1.0));
        let b = c.read_block().unwrap();
        assert_eq!(b.diagnostic, 0x0460);
        assert!(near(b.current, 70000.0 / 13661.0));
        assert!(near(b.voltage_out, 151800.0 / 22070.0));
        assert!(near(b.voltage_in, 201800.0 / 22070.0));
        assert!(near(b.power, 103300.0 / 736.0));
        assert!(near(b.temperature, 25.0));
        assert!(near(c.read_average().unwrap().voltage_in, 1800.0 / 22070.0));
        assert_eq!(c.black_box().unwrap().diagnostic, 1);
        assert!(matches!(c.read_block(), Err(Lm25066Error::BlockLength)));
        i2c.done();
    }

    #[test]
    fn averages_and_peak() {
        let t = [
            rd(0xDC, 2000), rd(0xDD, 0), rd(0xDE, 648), rd(0xDF, 0), rd(0xD5, 1000),
            T::write(ADDR, vec![0xD6]),
            wrb(0xDB, 6), rdb(0xDB, 6),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        assert!(near(c.voltage_in_avg().unwrap(), 201800.0 / 22070.0));
        c.voltage_out_avg().unwrap();
        assert!(near(c.current_avg().unwrap(), 70000.0 / 13661.0));
        c.power_avg().unwrap();
        assert!(near(c.power_peak().unwrap(), 103300.0 / 736.0));
        c.clear_power_peak().unwrap();
        c.set_averaging(6).unwrap();
        assert!(matches!(c.set_averaging(13), Err(Lm25066Error::InvalidArgument)));
        assert_eq!(c.get_averaging().unwrap(), 6);
        i2c.done();
    }

    #[test]
    fn limits() {
        let y_iin = ((13661.0f32 * 1.02 - 5200.0) / 100.0 + 0.5) as u16;
        let t = [
            wr(0x58, 0x088D), wr(0x58, 0), wr(0x57, 0x0FFF), wr(0x4F, 0x0960), wr(0x51, 0x07D0),
            wr(0xD4, 0x0FFE), wr(0xD3, y_iin),
            rd(0x57, 0x0FFF), rd(0x58, 0), rd(0x43, 0), rd(0x51, 0x07D0), rd(0x4F, 0x0960), rd(0xD3, 0x0FFF), rd(0xD4, 0x0FFF),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        c.set_vin_uv_warn(Some(10.0)).unwrap();
        c.set_vin_uv_warn(None).unwrap();
        c.set_vin_ov_warn(None).unwrap();
        c.set_ot_fault(Some(150.0)).unwrap();
        c.set_ot_warn(Some(125.0)).unwrap();
        c.set_pin_warn(Some(1e9)).unwrap();
        c.set_iin_warn(Some(1.02)).unwrap();
        let l = c.get_limits().unwrap();
        assert!(l.vin_ov_warn.is_none() && l.vin_uv_warn.is_none() && l.vout_uv_warn.is_none());
        assert!(l.iin_warn.is_none() && l.pin_warn.is_none());
        assert!(near(l.ot_fault.unwrap(), 150.0) && near(l.ot_warn.unwrap(), 125.0));
        i2c.done();
    }

    #[test]
    fn status_and_control() {
        let t = [
            rdb(0x78, 0x49), rd(0x79, 0x3849), rdb(0x7A, 0x20), rdb(0x7C, 0x10), rdb(0x7D, 0x80),
            rdb(0x7E, 2), rdb(0x80, 0x10), rd(0xE1, 0x0460), rd(0xE1, 0x0460),
            T::write(ADDR, vec![0x03]), wrb(0x01, 0x80), wrb(0x01, 0x00),
            rdb(0x01, 0x80), rdb(0x78, 0x00), rdb(0x01, 0x80), rdb(0x78, 0x40),
            rdb(0x19, 0xB0),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        assert_eq!(c.status_byte().unwrap(), 0x49);
        assert_eq!(c.status_word().unwrap(), 0x3849);
        assert_eq!(c.status_vout().unwrap(), 0x20);
        assert_eq!(c.status_input().unwrap(), 0x10);
        assert_eq!(c.status_temperature().unwrap(), 0x80);
        assert_eq!(c.status_cml().unwrap(), 2);
        assert_eq!(c.status_mfr().unwrap(), 0x10);
        assert_eq!(c.diagnostic_word().unwrap(), 0x0460);
        assert_eq!(c.poll_interrupt().unwrap(), 0x0460);
        c.clear_faults().unwrap();
        c.operation_on().unwrap();
        c.operation_off().unwrap();
        assert!(c.is_on().unwrap());
        assert!(!c.is_on().unwrap());
        assert_eq!(c.capability().unwrap(), 0xB0);
        i2c.done();
    }

    #[test]
    fn device_setup_gate_mask_and_alerts() {
        let t = [
            wrb(0xD9, 0xE0 | 0x04 | 0x10 | 0x02), rd(0xD1, 648), rdb(0xD9, 0x16),
            wrb(0xD7, 0x04), rdb(0xD7, 0x04),
            rd(0xD8, 0x0820), wr(0xD8, 0x0800), rd(0xD8, 0x0800), wr(0xD8, 0x2800),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        c.configure_device(Some(7), Some(true), Some(false)).unwrap();
        assert!(near(c.current().unwrap(), 67900.0 / 6854.0));
        assert!(matches!(c.configure_device(Some(8), None, None), Err(Lm25066Error::InvalidArgument)));
        assert_eq!(c.device_setup().unwrap(), 0x16);
        c.set_gate_mask(0x04).unwrap();
        assert!(matches!(c.set_gate_mask(0x01), Err(Lm25066Error::InvalidArgument)));
        assert_eq!(c.get_gate_mask().unwrap(), 0x04);
        c.enable_interrupt(SOURCE_VIN_UV_FAULT).unwrap();
        c.disable_interrupt(SOURCE_VIN_UV_WARN).unwrap();
        i2c.done();
    }

    #[test]
    fn identity_strings() {
        let t = [
            T::write_read(ADDR, vec![0x99], vec![3, b'N', b'S', b'C']),
            T::write_read(ADDR, vec![0x9A], vec![8, b'L', b'M', b'2', b'5', b'0', b'6', b'6', 0]),
            T::write_read(ADDR, vec![0x9B], vec![2, b'A', b'A']),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Lm25066Full::new(i2c.clone(), ADDR, 1.0, false);
        assert_eq!(&c.manufacturer_id().unwrap(), b"NSC");
        assert_eq!(&c.model().unwrap(), b"LM25066\0");
        assert_eq!(&c.revision().unwrap(), b"AA");
        i2c.done();
    }
}
