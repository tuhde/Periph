//! TMP117 — ±0.1°C high-accuracy, low-power digital temperature sensor (Texas Instruments).
//!
//! NIST-traceable 16-bit temperature sensor (0.0078125 °C per LSB) read over
//! an I²C/SMBus-compatible bus. Offers continuous, one-shot and shutdown
//! conversion modes with selectable averaging and cycle time, a
//! window-alert / latching-Therm / Data-Ready `ALERT` output, and EEPROM
//! persistence of its configuration, limit and offset registers plus
//! general-purpose scratch storage. Four selectable addresses (`0x48`–`0x4B`)
//! via the 4-level `ADD0` strap.
//!
//! Registers are 16-bit, big-endian, addressed through a non-incrementing
//! Register Pointer — see `specs/temperature/tmp117.md`.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Tmp117Full::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). The caller is responsible for wiring it into an ISR on the
//! `ALERT` pin or a polling loop. In Alert mode the read that polls the flags
//! also clears them (a hardware side effect); in Therm mode `HIGH_Alert`
//! clears only once the result drops below `TLOW_LIMIT`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_TEMP_RESULT: u8 = 0x00;
const REG_CONFIG: u8 = 0x01;
const REG_THIGH: u8 = 0x02;
const REG_TLOW: u8 = 0x03;
const REG_EEPROM_UL: u8 = 0x04;
const REG_EEPROM1: u8 = 0x05;
const REG_EEPROM2: u8 = 0x06;
const REG_TEMP_OFFSET: u8 = 0x07;
const REG_EEPROM3: u8 = 0x08;
const REG_DEVICE_ID: u8 = 0x0F;

// CONFIGURATION (0x01) bits.
const CFG_HIGH_ALERT: u16 = 0x8000;
const CFG_LOW_ALERT: u16 = 0x4000;
const CFG_DATA_READY: u16 = 0x2000;
const CFG_MOD_SHIFT: u16 = 10;
const CFG_MOD_MASK: u16 = 0x0C00;
const CFG_CONV_SHIFT: u16 = 7;
const CFG_CONV_MASK: u16 = 0x0380;
const CFG_AVG_SHIFT: u16 = 5;
const CFG_AVG_MASK: u16 = 0x0060;
const CFG_TNA: u16 = 0x0010;
const CFG_POL: u16 = 0x0008;
const CFG_DR_ALERT: u16 = 0x0004;
const CFG_SOFT_RESET: u16 = 0x0002;
// Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only on purpose.
const CFG_WRITE_MASK: u16 = 0x0FFC;

// EEPROM_UL (0x04) bits.
const EUN: u16 = 0x8000;
const EEPROM_BUSY: u16 = 0x4000;

/// Conversion cycle times in s, indexed by `CONV[2:0]` (no-averaging column).
const CYCLES: [f32; 8] = [0.0155, 0.125, 0.25, 0.5, 1.0, 4.0, 8.0, 16.0];
/// Averaging counts, indexed by `AVG[1:0]`.
const AVERAGINGS: [u8; 4] = [0, 8, 32, 64];

const LSB_C: f32 = 0.0078125;

/// Default 7-bit I²C address (`ADD0` = GND). Valid range `0x48`–`0x4B`.
pub const TMP117_I2C_ADDRESS: u8 = 0x48;
/// Expected `DEVICE_ID` bits 11:0 (bits 15:12 are the silicon revision).
pub const TMP117_DEVICE_ID: u16 = 0x117;

/// Interrupt source: result > `THIGH_LIMIT` (`HIGH_Alert`).
pub const TMP117_SOURCE_HIGH: u8 = 0x01;
/// Interrupt source: result < `TLOW_LIMIT` (`LOW_Alert`; Alert mode only).
pub const TMP117_SOURCE_LOW: u8 = 0x02;

/// Conversion mode (`MOD[1:0]`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tmp117Mode {
    /// Continuous conversion (POR default).
    Continuous,
    /// No conversions; `TEMP_RESULT` holds its last value.
    Shutdown,
    /// One conversion, then Shutdown.
    OneShot,
}

/// `ALERT` behavior (`T/nA`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tmp117AlertMode {
    /// Window alert: `HIGH_Alert` and `LOW_Alert` (POR default).
    Alert,
    /// Latching thermostat; `TLOW_LIMIT` is the reset threshold.
    Therm,
}

/// `ALERT` pin polarity (`POL`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tmp117AlertPolarity {
    /// Needs an external pull-up (POR default).
    ActiveLow,
    /// Driven high when asserted.
    ActiveHigh,
}

/// `ALERT` pin function (`DR/Alert`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tmp117AlertPinFunction {
    /// Reflects the alert/Therm status (POR default).
    Alert,
    /// Reflects `Data_Ready`.
    DataReady,
}

/// Decoded conversion configuration, as returned by [`Tmp117Full::get_config`].
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Tmp117Config {
    /// Conversion mode.
    pub mode: Tmp117Mode,
    /// Conversions averaged per result: 0, 8, 32 or 64.
    pub averaging: u8,
    /// `CONV[2:0]` cycle time in s (no-averaging column).
    pub cycle_seconds: f32,
}

/// Errors from the TMP117 driver's checked operations.
#[derive(Debug)]
pub enum Tmp117Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// `DEVICE_ID` bits 11:0 were not `0x117` — wrong chip, wrong address,
    /// or a wiring problem.
    NotFound,
    /// An averaging count or EEPROM scratch slot outside the supported set.
    InvalidArgument,
}

impl<E> From<E> for Tmp117Error<E> {
    fn from(e: E) -> Self {
        Tmp117Error::Bus(e)
    }
}

fn abs(x: f32) -> f32 {
    if x < 0.0 { -x } else { x }
}

fn decode_temperature(raw16: u16) -> f32 {
    raw16 as i16 as f32 * LSB_C
}

/// Round half away from zero to 0.0078125 °C steps and clamp to the 16-bit
/// two's-complement range (−256.0 to 255.9921875 °C).
fn encode_temperature(celsius: f32) -> u16 {
    let steps = celsius / LSB_C;
    let value = if steps >= 0.0 { (steps + 0.5) as i32 } else { -((-steps + 0.5) as i32) };
    value.clamp(-32768, 32767) as i16 as u16
}

/// TMP117 — minimal interface: read the temperature.
pub struct Tmp117Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Tmp117Minimal<I2C> {
    /// Construct the driver and confirm the chip's identity (`DEVICE_ID`
    /// bits 11:0 = `0x117`; the revision nibble is ignored). Makes no
    /// register writes — the POR/EEPROM default (continuous conversion,
    /// 8-conversion averaging, 1 s cycle, Alert mode) already serves the
    /// primary use case.
    ///
    /// `addr` is `0x48`–`0x4B` per the board's `ADD0` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, Tmp117Error<I2C::Error>> {
        let mut chip = Self { i2c, addr };
        if chip.read_reg(REG_DEVICE_ID)? & 0x0FFF != TMP117_DEVICE_ID {
            return Err(Tmp117Error::NotFound);
        }
        Ok(chip)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn write_reg(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[reg, (value >> 8) as u8, value as u8])
    }

    /// Read the temperature in °C.
    ///
    /// Decodes `TEMP_RESULT`'s 16-bit two's-complement value (0.0078125 °C
    /// per LSB). Returns −256.0 until the first conversion after power-up
    /// completes.
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_temperature(self.read_reg(REG_TEMP_RESULT)?))
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// TMP117 — full interface: extends [`Tmp117Minimal`] with conversion mode,
/// averaging and cycle-time control, one-shot triggering, both temperature
/// limits, the calibration offset, soft reset, EEPROM persistence and scratch
/// storage, and the `ALERT` output.
pub struct Tmp117Full<I2C> {
    inner: Tmp117Minimal<I2C>,
}

impl<I2C: I2c> Tmp117Full<I2C> {
    /// Construct the driver; same identity check as [`Tmp117Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, Tmp117Error<I2C::Error>> {
        Ok(Self { inner: Tmp117Minimal::new(i2c, addr)? })
    }

    /// Read the temperature in °C. Delegates to
    /// [`Tmp117Minimal::read_temperature`].
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.read_temperature()
    }

    fn read_config(&mut self) -> Result<u16, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONFIG)? & CFG_WRITE_MASK)
    }

    fn write_config(&mut self, value: u16) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONFIG, value & CFG_WRITE_MASK)
    }

    /// Set conversion mode, averaging (0, 8, 32 or 64) and cycle time in s.
    ///
    /// The cycle time is matched to the nearest `CONV[2:0]` step from the
    /// no-averaging column (15.5 ms, 125 ms, 250 ms, 500 ms, 1 s, 4 s, 8 s,
    /// 16 s); at higher averaging the hardware lengthens short cycles
    /// automatically. The Alert configuration bits are preserved. An
    /// unsupported averaging returns [`Tmp117Error::InvalidArgument`] without
    /// a bus transaction.
    pub fn configure(
        &mut self,
        mode: Tmp117Mode,
        averaging: u8,
        cycle_seconds: f32,
    ) -> Result<(), Tmp117Error<I2C::Error>> {
        let avg = AVERAGINGS.iter().position(|&a| a == averaging).ok_or(Tmp117Error::InvalidArgument)? as u16;
        let mut conv = 0usize;
        for (code, &cycle) in CYCLES.iter().enumerate() {
            if abs(cycle - cycle_seconds) < abs(CYCLES[conv] - cycle_seconds) {
                conv = code;
            }
        }
        let mod_bits: u16 = match mode {
            Tmp117Mode::Continuous => 0x00,
            Tmp117Mode::Shutdown => 0x01,
            Tmp117Mode::OneShot => 0x03,
        };
        let mut config = self.read_config()? & !(CFG_MOD_MASK | CFG_CONV_MASK | CFG_AVG_MASK);
        config |= (mod_bits << CFG_MOD_SHIFT) | ((conv as u16) << CFG_CONV_SHIFT) | (avg << CFG_AVG_SHIFT);
        self.write_config(config)?;
        Ok(())
    }

    /// Read the conversion mode, averaging and cycle time
    /// (`cycle_seconds` is the no-averaging `CONV[2:0]` step).
    pub fn get_config(&mut self) -> Result<Tmp117Config, I2C::Error> {
        let config = self.inner.read_reg(REG_CONFIG)?;
        let mode = match (config & CFG_MOD_MASK) >> CFG_MOD_SHIFT {
            0x01 => Tmp117Mode::Shutdown,
            0x03 => Tmp117Mode::OneShot,
            _ => Tmp117Mode::Continuous, // MOD = 10 reads back as continuous
        };
        Ok(Tmp117Config {
            mode,
            averaging: AVERAGINGS[((config & CFG_AVG_MASK) >> CFG_AVG_SHIFT) as usize],
            cycle_seconds: CYCLES[((config & CFG_CONV_MASK) >> CFG_CONV_SHIFT) as usize],
        })
    }

    /// `true` if the sensor is in Shutdown mode (`MOD[1:0]` = Shutdown).
    pub fn is_shutdown(&mut self) -> Result<bool, I2C::Error> {
        Ok((self.inner.read_reg(REG_CONFIG)? & CFG_MOD_MASK) >> CFG_MOD_SHIFT == 0x01)
    }

    /// Start a single conversion (`MOD[1:0]` = One-Shot); the sensor returns
    /// to Shutdown once the conversion (including averaging) completes.
    pub fn trigger_one_shot(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()? & !CFG_MOD_MASK;
        self.write_config(config | (0x03 << CFG_MOD_SHIFT))
    }

    /// `true` if a fresh conversion result is available (`Data_Ready`).
    /// Reading this flag clears it (as does reading `TEMP_RESULT`).
    pub fn is_data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONFIG)? & CFG_DATA_READY != 0)
    }

    /// Read `THIGH_LIMIT` in °C.
    pub fn get_high_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_temperature(self.inner.read_reg(REG_THIGH)?))
    }

    /// Write `THIGH_LIMIT` in °C, rounded to the nearest 0.0078125 °C and
    /// clamped to −256.0…255.9921875 °C.
    pub fn set_high_limit(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_THIGH, encode_temperature(celsius))
    }

    /// Read `TLOW_LIMIT` in °C.
    pub fn get_low_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_temperature(self.inner.read_reg(REG_TLOW)?))
    }

    /// Write `TLOW_LIMIT` in °C, rounded to the nearest 0.0078125 °C and
    /// clamped to −256.0…255.9921875 °C. In Therm mode this is
    /// `HIGH_Alert`'s reset threshold (hysteresis).
    pub fn set_low_limit(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_TLOW, encode_temperature(celsius))
    }

    /// Read `TEMP_OFFSET` (calibration offset) in °C.
    pub fn get_temperature_offset(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_temperature(self.inner.read_reg(REG_TEMP_OFFSET)?))
    }

    /// Write `TEMP_OFFSET` in °C, added to every result after linearization;
    /// rounded to the nearest 0.0078125 °C and clamped to −256.0…255.9921875 °C.
    pub fn set_temperature_offset(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_TEMP_OFFSET, encode_temperature(celsius))
    }

    /// Software reset (`Soft_Reset` = 1), then wait the 2 ms reset time.
    /// Reloads `CONFIGURATION`, `THIGH_LIMIT`, `TLOW_LIMIT` and `TEMP_OFFSET`
    /// from EEPROM.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONFIG, CFG_SOFT_RESET)?;
        delay.delay_ms(2);
        Ok(())
    }

    /// Unlock the EEPROM (`EUN` = 1). While unlocked, writes to
    /// `CONFIGURATION`, `THIGH_LIMIT`, `TLOW_LIMIT`, `TEMP_OFFSET` and
    /// `EEPROM2` also program the EEPROM as the new power-on default. Poll
    /// [`Self::is_eeprom_busy`] after each such write.
    pub fn unlock_eeprom(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_EEPROM_UL, EUN)
    }

    /// Lock the EEPROM (`EUN` = 0); register writes become volatile only.
    pub fn lock_eeprom(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_EEPROM_UL, 0x0000)
    }

    /// `true` while an EEPROM programming operation is in progress
    /// (`EEPROM_Busy`).
    pub fn is_eeprom_busy(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_EEPROM_UL)? & EEPROM_BUSY != 0)
    }

    /// Read general-purpose EEPROM scratch register `slot` (1, 2 or 3 —
    /// `EEPROM1`/`EEPROM2`/`EEPROM3`; slots 1 and 3 hold factory
    /// NIST-traceability data). Any other slot returns
    /// [`Tmp117Error::InvalidArgument`] without a bus transaction.
    pub fn read_eeprom_scratch(&mut self, slot: u8) -> Result<u16, Tmp117Error<I2C::Error>> {
        let reg = match slot {
            1 => REG_EEPROM1,
            2 => REG_EEPROM2,
            3 => REG_EEPROM3,
            _ => return Err(Tmp117Error::InvalidArgument),
        };
        Ok(self.inner.read_reg(reg)?)
    }

    /// Write the general-purpose `EEPROM2` scratch register. Only slot 2 is
    /// writable — `EEPROM1`/`EEPROM3` hold factory NIST-traceability data;
    /// any other slot returns [`Tmp117Error::InvalidArgument`] without a bus
    /// transaction. Persists across power cycles only while the EEPROM is
    /// unlocked.
    pub fn write_eeprom_scratch(&mut self, slot: u8, value: u16) -> Result<(), Tmp117Error<I2C::Error>> {
        if slot != 2 {
            return Err(Tmp117Error::InvalidArgument);
        }
        self.inner.write_reg(REG_EEPROM2, value)?;
        Ok(())
    }

    /// Configure the `ALERT` output's mode, polarity and pin function together.
    pub fn configure_alert(
        &mut self,
        mode: Tmp117AlertMode,
        polarity: Tmp117AlertPolarity,
        pin_function: Tmp117AlertPinFunction,
    ) -> Result<(), I2C::Error> {
        let mut config = self.read_config()? & !(CFG_TNA | CFG_POL | CFG_DR_ALERT);
        if mode == Tmp117AlertMode::Therm {
            config |= CFG_TNA;
        }
        if polarity == Tmp117AlertPolarity::ActiveHigh {
            config |= CFG_POL;
        }
        if pin_function == Tmp117AlertPinFunction::DataReady {
            config |= CFG_DR_ALERT;
        }
        self.write_config(config)
    }

    /// Read `CONFIGURATION`'s `HIGH_Alert` / `LOW_Alert` flags — a mask of
    /// [`TMP117_SOURCE_HIGH`] / [`TMP117_SOURCE_LOW`]. In Alert mode this read
    /// also clears both flags; in Therm mode `HIGH_Alert` clears only once the
    /// result drops below `TLOW_LIMIT`.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let config = self.inner.read_reg(REG_CONFIG)?;
        let mut status = 0;
        if config & CFG_HIGH_ALERT != 0 {
            status |= TMP117_SOURCE_HIGH;
        }
        if config & CFG_LOW_ALERT != 0 {
            status |= TMP117_SOURCE_LOW;
        }
        Ok(status)
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

    fn identity() -> Vec<I2cTransaction> {
        vec![rd(REG_DEVICE_ID, 0x1117)]
    }

    #[test]
    fn identity_check() {
        let mut i2c = I2cMock::new(&[rd(REG_DEVICE_ID, 0x0118)]);
        assert!(matches!(Tmp117Minimal::new(i2c.clone(), ADDR), Err(Tmp117Error::NotFound)));
        i2c.done();

        let mut i2c = I2cMock::new(&[rd(REG_DEVICE_ID, 0x2117)]);
        assert!(Tmp117Minimal::new(i2c.clone(), ADDR).is_ok());
        i2c.done();
    }

    #[test]
    fn minimal_temperature_decoding() {
        let mut t = identity();
        t.extend([
            rd(REG_TEMP_RESULT, 0x0C80), rd(REG_TEMP_RESULT, 0xFFFF), rd(REG_TEMP_RESULT, 0xF380),
            rd(REG_TEMP_RESULT, 0x8000), rd(REG_TEMP_RESULT, 0x7FFF),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut sensor = Tmp117Minimal::new(i2c.clone(), ADDR).expect("init");
        assert_eq!(sensor.read_temperature().unwrap(), 25.0);
        assert_eq!(sensor.read_temperature().unwrap(), -0.0078125);
        assert_eq!(sensor.read_temperature().unwrap(), -25.0);
        assert_eq!(sensor.read_temperature().unwrap(), -256.0);
        assert_eq!(sensor.read_temperature().unwrap(), 255.9921875);
        i2c.done();
    }

    #[test]
    fn full_limits_and_offset() {
        let mut t = identity();
        t.extend([
            wr(REG_THIGH, 0x0F00), rd(REG_THIGH, 0x0F00),     // 30.0
            wr(REG_TLOW, 0xFAE0), rd(REG_TLOW, 0xFAE0),       // -10.25
            wr(REG_TLOW, 0x0001),                             // 0.004 rounds to one LSB
            wr(REG_THIGH, 0x7FFF),                            // clamps high
            wr(REG_TLOW, 0x8000),                             // clamps low
            wr(REG_TEMP_OFFSET, 0xFFC0), rd(REG_TEMP_OFFSET, 0xFFC0),  // -0.5
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp117Full::new(i2c.clone(), ADDR).expect("init");
        s.set_high_limit(30.0).unwrap();
        assert_eq!(s.get_high_limit().unwrap(), 30.0);
        s.set_low_limit(-10.25).unwrap();
        assert_eq!(s.get_low_limit().unwrap(), -10.25);
        s.set_low_limit(0.004).unwrap();
        s.set_high_limit(1000.0).unwrap();
        s.set_low_limit(-1000.0).unwrap();
        s.set_temperature_offset(-0.5).unwrap();
        assert_eq!(s.get_temperature_offset().unwrap(), -0.5);
        i2c.done();
    }

    #[test]
    fn full_conversion_config() {
        let mut t = identity();
        t.extend([
            rd(REG_CONFIG, 0x0220),                           // get_config default
            rd(REG_CONFIG, 0x0220), wr(REG_CONFIG, 0x07E0),   // configure(Shutdown, 64, 16 s)
            rd(REG_CONFIG, 0x07E0),                           // is_shutdown
            rd(REG_CONFIG, 0x07E0), wr(REG_CONFIG, 0x0000),   // configure(Continuous, 0, 0.01 s)
            rd(REG_CONFIG, 0x0000), wr(REG_CONFIG, 0x0120),   // configure(Continuous, 8, 0.3 s) -> 250 ms
            rd(REG_CONFIG, 0x0120), wr(REG_CONFIG, 0x0E40),   // configure(OneShot, 32, 2 s) -> 1 s
            rd(REG_CONFIG, 0x0800),                           // get_config: MOD=10 is continuous
            rd(REG_CONFIG, 0xF01C), wr(REG_CONFIG, 0x023C),   // configure preserves alert bits
            // configure with averaging 16 is rejected without a bus transaction.
            rd(REG_CONFIG, 0xE660), wr(REG_CONFIG, 0x0E60),   // trigger_one_shot
            rd(REG_CONFIG, 0x2220),                           // is_data_ready
            wr(REG_CONFIG, 0x0002),                           // reset
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp117Full::new(i2c.clone(), ADDR).expect("init");
        assert_eq!(s.get_config().unwrap(),
                   Tmp117Config { mode: Tmp117Mode::Continuous, averaging: 8, cycle_seconds: 1.0 });
        s.configure(Tmp117Mode::Shutdown, 64, 16.0).unwrap();
        assert!(s.is_shutdown().unwrap());
        s.configure(Tmp117Mode::Continuous, 0, 0.01).unwrap();
        s.configure(Tmp117Mode::Continuous, 8, 0.3).unwrap();
        s.configure(Tmp117Mode::OneShot, 32, 2.0).unwrap();
        assert_eq!(s.get_config().unwrap().mode, Tmp117Mode::Continuous);
        s.configure(Tmp117Mode::Continuous, 8, 1.0).unwrap();
        assert!(matches!(s.configure(Tmp117Mode::Continuous, 16, 1.0), Err(Tmp117Error::InvalidArgument)));
        s.trigger_one_shot().unwrap();
        assert!(s.is_data_ready().unwrap());
        s.reset(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn full_eeprom_alert_poll() {
        let mut t = identity();
        t.extend([
            wr(REG_EEPROM_UL, 0x8000),                        // unlock_eeprom
            wr(REG_EEPROM_UL, 0x0000),                        // lock_eeprom
            rd(REG_EEPROM_UL, 0x4000), rd(REG_EEPROM_UL, 0x8000),  // is_eeprom_busy x2
            rd(REG_EEPROM1, 0x1111), rd(REG_EEPROM2, 0x2222), rd(REG_EEPROM3, 0x3333),
            wr(REG_EEPROM2, 0xBEEF),
            // slot 4 read and slot 1/3 writes are rejected without a bus transaction.
            rd(REG_CONFIG, 0x0220), wr(REG_CONFIG, 0x023C),   // configure_alert(Therm, ActiveHigh, DataReady)
            rd(REG_CONFIG, 0x023C), wr(REG_CONFIG, 0x0220),   // configure_alert defaults
            rd(REG_CONFIG, 0x2220), rd(REG_CONFIG, 0x8220), rd(REG_CONFIG, 0x4220), rd(REG_CONFIG, 0xC220),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp117Full::new(i2c.clone(), ADDR).expect("init");
        s.unlock_eeprom().unwrap();
        s.lock_eeprom().unwrap();
        assert!(s.is_eeprom_busy().unwrap());
        assert!(!s.is_eeprom_busy().unwrap());
        assert_eq!(s.read_eeprom_scratch(1).unwrap(), 0x1111);
        assert_eq!(s.read_eeprom_scratch(2).unwrap(), 0x2222);
        assert_eq!(s.read_eeprom_scratch(3).unwrap(), 0x3333);
        s.write_eeprom_scratch(2, 0xBEEF).unwrap();
        assert!(matches!(s.read_eeprom_scratch(4), Err(Tmp117Error::InvalidArgument)));
        assert!(matches!(s.write_eeprom_scratch(1, 0), Err(Tmp117Error::InvalidArgument)));
        assert!(matches!(s.write_eeprom_scratch(3, 0), Err(Tmp117Error::InvalidArgument)));
        s.configure_alert(Tmp117AlertMode::Therm, Tmp117AlertPolarity::ActiveHigh,
                          Tmp117AlertPinFunction::DataReady).unwrap();
        s.configure_alert(Tmp117AlertMode::Alert, Tmp117AlertPolarity::ActiveLow,
                          Tmp117AlertPinFunction::Alert).unwrap();
        assert_eq!(s.poll_interrupt().unwrap(), 0);
        assert_eq!(s.poll_interrupt().unwrap(), TMP117_SOURCE_HIGH);
        assert_eq!(s.poll_interrupt().unwrap(), TMP117_SOURCE_LOW);
        assert_eq!(s.poll_interrupt().unwrap(), TMP117_SOURCE_HIGH | TMP117_SOURCE_LOW);
        i2c.done();
    }
}
