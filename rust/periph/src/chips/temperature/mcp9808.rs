//! MCP9808 — ±0.5°C maximum accuracy digital temperature sensor (Microchip).
//!
//! Band-gap temperature sensor with a delta-sigma ADC, read over I²C.
//! Measures ambient temperature with a user-selectable resolution (0.5 °C
//! down to 0.0625 °C) and drives an open-drain Alert output from three
//! programmable 0.25 °C-resolution boundaries (`TUPPER`/`TLOWER`/`TCRIT`) with
//! optional hysteresis. Eight selectable addresses (`0x18`–`0x1F`) via the
//! `A0`/`A1`/`A2` strap pins.
//!
//! Registers are 16-bit, big-endian, addressed through a non-incrementing
//! Register Pointer — see `specs/temperature/mcp9808.md`.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Mcp9808Full::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). The caller is responsible for wiring it into an ISR on the
//! Alert pin or a polling loop. The returned bits are a live comparison read
//! from `TA`; nothing is cleared by polling. Only an interrupt-mode Alert
//! output latches, and [`Mcp9808Full::clear_interrupt`] releases it.

use embedded_hal::i2c::I2c;

const REG_CONFIG: u8 = 0x01;
const REG_TUPPER: u8 = 0x02;
const REG_TLOWER: u8 = 0x03;
const REG_TCRIT: u8 = 0x04;
const REG_TA: u8 = 0x05;
const REG_MFR_ID: u8 = 0x06;
const REG_DEVICE_ID: u8 = 0x07;
const REG_RESOLUTION: u8 = 0x08;

// CONFIG (0x01) bits.
const CFG_THYST_SHIFT: u16 = 9;
const CFG_THYST_MASK: u16 = 0x0600;
const CFG_SHDN: u16 = 0x0100;
const CFG_CRIT_LOCK: u16 = 0x0080;
const CFG_WIN_LOCK: u16 = 0x0040;
const CFG_INT_CLEAR: u16 = 0x0020;
const CFG_ALERT_STAT: u16 = 0x0010;
const CFG_ALERT_CNT: u16 = 0x0008;
const CFG_ALERT_SEL: u16 = 0x0004;
const CFG_ALERT_POL: u16 = 0x0002;
const CFG_ALERT_MOD: u16 = 0x0001;
const CFG_LOCKS: u16 = 0x00C0;
// Writable bits: all but the unimplemented 15:11, the read-only ALERT_STAT,
// and the self-clearing INT_CLEAR (set only on purpose).
const CFG_WRITE_MASK: u16 = 0x07CF;

const RESOLUTIONS: [f32; 4] = [0.5, 0.25, 0.125, 0.0625];
const HYSTERESES: [f32; 4] = [0.0, 1.5, 3.0, 6.0];

/// Default 7-bit I²C address (`A0` = `A1` = `A2` = GND). Valid range `0x18`–`0x1F`.
pub const MCP9808_I2C_ADDRESS: u8 = 0x18;
/// Expected `MANUFACTURER_ID` register value.
pub const MCP9808_MANUFACTURER_ID: u16 = 0x0054;
/// Expected `DEVICE_ID` (upper byte of `DEVICE_ID_REV`).
pub const MCP9808_DEVICE_ID: u8 = 0x04;

/// Interrupt source: `TA` < `TLOWER`.
pub const MCP9808_SOURCE_LOWER: u8 = 0x01;
/// Interrupt source: `TA` > `TUPPER`.
pub const MCP9808_SOURCE_UPPER: u8 = 0x02;
/// Interrupt source: `TA` ≥ `TCRIT`.
pub const MCP9808_SOURCE_CRITICAL: u8 = 0x04;

/// Which boundaries drive the Alert output (`ALERT_SEL`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mcp9808AlertMode {
    /// `TUPPER`, `TLOWER` and `TCRIT`.
    All,
    /// `TCRIT` only.
    CriticalOnly,
}

/// Alert output behavior (`ALERT_MOD`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mcp9808AlertOutput {
    /// Follows the boundary state.
    Comparator,
    /// Latches until [`Mcp9808Full::clear_interrupt`].
    Interrupt,
}

/// Alert output polarity (`ALERT_POL`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mcp9808AlertPolarity {
    /// Needs an external pull-up (POR default).
    ActiveLow,
    /// Driven high when asserted.
    ActiveHigh,
}

/// Errors from the MCP9808 driver's checked operations.
#[derive(Debug)]
pub enum Mcp9808Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// `MANUFACTURER_ID` or `DEVICE_ID` did not match — wrong chip, wrong
    /// address, or a wiring problem.
    NotFound,
    /// A resolution or hysteresis value outside the supported set.
    InvalidArgument,
    /// `CRIT_LOCK` or `WIN_LOCK` is set, freezing the Alert configuration
    /// until the next power-on reset.
    Locked,
}

impl<E> From<E> for Mcp9808Error<E> {
    fn from(e: E) -> Self {
        Mcp9808Error::Bus(e)
    }
}

fn decode_temperature(raw16: u16) -> f32 {
    let mut raw = (raw16 & 0x1FFF) as i16;
    if raw & 0x1000 != 0 {
        raw -= 0x2000;
    }
    raw as f32 / 16.0
}

fn decode_limit(raw16: u16) -> f32 {
    let mut value = ((raw16 >> 2) & 0x3FF) as i16;
    if raw16 & 0x1000 != 0 {
        value -= 1024;
    }
    value as f32 / 4.0
}

/// Round half away from zero to 0.25 °C steps, clamp to the 11-bit
/// two's-complement range (−256.0 to 255.75 °C), place in bits 12:2.
fn encode_limit(celsius: f32) -> u16 {
    let quarters = if celsius >= 0.0 {
        (celsius * 4.0 + 0.5) as i32
    } else {
        -((-celsius * 4.0 + 0.5) as i32)
    };
    ((quarters.clamp(-1024, 1023) & 0x7FF) as u16) << 2
}

fn index_of_step(table: &[f32; 4], value: f32) -> Option<u16> {
    table.iter().position(|&s| {
        let d = value - s;
        d < 1e-4 && d > -1e-4
    }).map(|i| i as u16)
}

/// MCP9808 — minimal interface: read the ambient temperature.
pub struct Mcp9808Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Mcp9808Minimal<I2C> {
    /// Construct the driver and confirm the chip's identity
    /// (`MANUFACTURER_ID` = `0x0054`, `DEVICE_ID` byte = `0x04`; the revision
    /// byte is ignored). Makes no register writes — the POR default
    /// (continuous conversion at 0.0625 °C, Alert output disabled) already
    /// serves the primary use case.
    ///
    /// `addr` is `0x18`–`0x1F` per the board's `A0`/`A1`/`A2` strapping.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, Mcp9808Error<I2C::Error>> {
        let mut chip = Self { i2c, addr };
        if chip.read_reg(REG_MFR_ID)? != MCP9808_MANUFACTURER_ID
            || (chip.read_reg(REG_DEVICE_ID)? >> 8) as u8 != MCP9808_DEVICE_ID
        {
            return Err(Mcp9808Error::NotFound);
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

    /// Read the ambient temperature in °C.
    ///
    /// Masks off `TA`'s three boundary-status bits and decodes the 13-bit
    /// two's-complement value (0.0625 °C per LSB).
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_temperature(self.read_reg(REG_TA)?))
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// MCP9808 — full interface: extends [`Mcp9808Minimal`] with resolution
/// control, Shutdown mode, the `TUPPER`/`TLOWER`/`TCRIT` boundaries,
/// hysteresis, the one-way register locks, and the Alert output.
pub struct Mcp9808Full<I2C> {
    inner: Mcp9808Minimal<I2C>,
}

impl<I2C: I2c> Mcp9808Full<I2C> {
    /// Construct the driver; same identity check as [`Mcp9808Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, Mcp9808Error<I2C::Error>> {
        Ok(Self { inner: Mcp9808Minimal::new(i2c, addr)? })
    }

    /// Read the ambient temperature in °C. Delegates to
    /// [`Mcp9808Minimal::read_temperature`].
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.read_temperature()
    }

    fn read_config(&mut self) -> Result<u16, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONFIG)? & CFG_WRITE_MASK)
    }

    fn write_config(&mut self, value: u16) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONFIG, value & CFG_WRITE_MASK)
    }

    fn config_bit(&mut self, bit: u16) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONFIG)? & bit != 0)
    }

    /// Set the measurement resolution, one of 0.5, 0.25, 0.125, 0.0625 °C.
    ///
    /// Finer steps take longer to convert: 0.5 °C = 30 ms, 0.25 °C = 65 ms,
    /// 0.125 °C = 130 ms, 0.0625 °C = 250 ms (typical). Any other value
    /// returns [`Mcp9808Error::InvalidArgument`] without a bus transaction.
    pub fn set_resolution(&mut self, celsius: f32) -> Result<(), Mcp9808Error<I2C::Error>> {
        let code = index_of_step(&RESOLUTIONS, celsius).ok_or(Mcp9808Error::InvalidArgument)?;
        let addr = self.inner.addr;
        self.inner.i2c.write(addr, &[REG_RESOLUTION, code as u8])?;
        Ok(())
    }

    /// Read the measurement resolution step in °C.
    pub fn get_resolution(&mut self) -> Result<f32, I2C::Error> {
        let mut buf = [0u8; 1];
        let addr = self.inner.addr;
        self.inner.i2c.write_read(addr, &[REG_RESOLUTION], &mut buf)?;
        Ok(RESOLUTIONS[(buf[0] & 0x03) as usize])
    }

    /// Enter Shutdown (low-power) mode; `TA` holds its last value. No-op
    /// while either lock bit is set (the chip ignores `SHDN`=1 then).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        if config & CFG_LOCKS != 0 {
            return Ok(());
        }
        self.write_config(config | CFG_SHDN)
    }

    /// Leave Shutdown mode and resume continuous conversion.
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        self.write_config(config & !CFG_SHDN)
    }

    /// `true` if the sensor is in Shutdown mode (`SHDN` set).
    pub fn is_shutdown(&mut self) -> Result<bool, I2C::Error> {
        self.config_bit(CFG_SHDN)
    }

    /// Read the `TUPPER` boundary in °C (0.25 °C steps).
    pub fn get_upper_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_limit(self.inner.read_reg(REG_TUPPER)?))
    }

    /// Write the `TUPPER` boundary in °C, rounded to the nearest 0.25 °C and
    /// clamped to −256.0…255.75 °C (ignored by the chip while `WIN_LOCK` is set).
    pub fn set_upper_limit(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_TUPPER, encode_limit(celsius))
    }

    /// Read the `TLOWER` boundary in °C (0.25 °C steps).
    pub fn get_lower_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_limit(self.inner.read_reg(REG_TLOWER)?))
    }

    /// Write the `TLOWER` boundary in °C, rounded to the nearest 0.25 °C and
    /// clamped to −256.0…255.75 °C (ignored by the chip while `WIN_LOCK` is set).
    pub fn set_lower_limit(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_TLOWER, encode_limit(celsius))
    }

    /// Read the `TCRIT` boundary in °C (0.25 °C steps).
    pub fn get_critical_limit(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_limit(self.inner.read_reg(REG_TCRIT)?))
    }

    /// Write the `TCRIT` boundary in °C, rounded to the nearest 0.25 °C and
    /// clamped to −256.0…255.75 °C (ignored by the chip while `CRIT_LOCK` is set).
    pub fn set_critical_limit(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_TCRIT, encode_limit(celsius))
    }

    /// Set the boundary hysteresis, one of 0, 1.5, 3.0, 6.0 °C. Applies to
    /// the cooling edge only; ignored by the chip while either lock bit is
    /// set. Any other value returns [`Mcp9808Error::InvalidArgument`] without
    /// a bus transaction.
    pub fn set_hysteresis(&mut self, celsius: f32) -> Result<(), Mcp9808Error<I2C::Error>> {
        let code = index_of_step(&HYSTERESES, celsius).ok_or(Mcp9808Error::InvalidArgument)?;
        let config = self.read_config()? & !CFG_THYST_MASK;
        self.write_config(config | (code << CFG_THYST_SHIFT))?;
        Ok(())
    }

    /// Read the boundary hysteresis in °C.
    pub fn get_hysteresis(&mut self) -> Result<f32, I2C::Error> {
        let config = self.inner.read_reg(REG_CONFIG)?;
        Ok(HYSTERESES[((config & CFG_THYST_MASK) >> CFG_THYST_SHIFT) as usize])
    }

    /// Lock `TCRIT` (and `ALERT_SEL`/`POL`/`MOD`). Irreversible except by
    /// power-on reset.
    pub fn lock_critical_limit(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        self.write_config(config | CFG_CRIT_LOCK)
    }

    /// Lock `TUPPER`/`TLOWER` (and `ALERT_SEL`/`POL`/`MOD`). Irreversible
    /// except by power-on reset.
    pub fn lock_window_limits(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        self.write_config(config | CFG_WIN_LOCK)
    }

    /// `true` if `CRIT_LOCK` is set.
    pub fn is_critical_limit_locked(&mut self) -> Result<bool, I2C::Error> {
        self.config_bit(CFG_CRIT_LOCK)
    }

    /// `true` if `WIN_LOCK` is set.
    pub fn is_window_limits_locked(&mut self) -> Result<bool, I2C::Error> {
        self.config_bit(CFG_WIN_LOCK)
    }

    /// Configure the Alert output's source, mode and polarity together.
    /// Returns [`Mcp9808Error::Locked`] without writing if either lock bit
    /// is set.
    pub fn configure_alert(
        &mut self,
        mode: Mcp9808AlertMode,
        output: Mcp9808AlertOutput,
        polarity: Mcp9808AlertPolarity,
    ) -> Result<(), Mcp9808Error<I2C::Error>> {
        let mut config = self.read_config()?;
        if config & CFG_LOCKS != 0 {
            return Err(Mcp9808Error::Locked);
        }
        config &= !(CFG_ALERT_SEL | CFG_ALERT_POL | CFG_ALERT_MOD);
        if mode == Mcp9808AlertMode::CriticalOnly {
            config |= CFG_ALERT_SEL;
        }
        if polarity == Mcp9808AlertPolarity::ActiveHigh {
            config |= CFG_ALERT_POL;
        }
        if output == Mcp9808AlertOutput::Interrupt {
            config |= CFG_ALERT_MOD;
        }
        self.write_config(config)?;
        Ok(())
    }

    /// Enable the Alert output (`ALERT_CNT` = 1).
    pub fn enable_alert(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        self.write_config(config | CFG_ALERT_CNT)
    }

    /// Disable the Alert output (`ALERT_CNT` = 0).
    pub fn disable_alert(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        self.write_config(config & !CFG_ALERT_CNT)
    }

    /// `true` if the Alert output is currently asserted (`ALERT_STAT`).
    pub fn is_alert_asserted(&mut self) -> Result<bool, I2C::Error> {
        self.config_bit(CFG_ALERT_STAT)
    }

    /// Clear an asserted interrupt-mode Alert output (`INT_CLEAR` = 1). Has
    /// no effect in comparator mode.
    pub fn clear_interrupt(&mut self) -> Result<(), I2C::Error> {
        let config = self.read_config()?;
        self.inner.write_reg(REG_CONFIG, config | CFG_INT_CLEAR)
    }

    /// Read `TA`'s live boundary-status bits — a mask of
    /// [`MCP9808_SOURCE_LOWER`] / [`MCP9808_SOURCE_UPPER`] /
    /// [`MCP9808_SOURCE_CRITICAL`]. Nothing is cleared.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        Ok(((self.inner.read_reg(REG_TA)? >> 13) & 0x07) as u8)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x18;

    fn rd(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![(value >> 8) as u8, value as u8])
    }

    fn wr(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, (value >> 8) as u8, value as u8])
    }

    fn identity() -> Vec<I2cTransaction> {
        vec![rd(REG_MFR_ID, 0x0054), rd(REG_DEVICE_ID, 0x0401)]
    }

    #[test]
    fn identity_check() {
        let mut i2c = I2cMock::new(&[rd(REG_MFR_ID, 0x1234)]);
        assert!(matches!(Mcp9808Minimal::new(i2c.clone(), ADDR), Err(Mcp9808Error::NotFound)));
        i2c.done();

        let mut i2c = I2cMock::new(&[rd(REG_MFR_ID, 0x0054), rd(REG_DEVICE_ID, 0x0500)]);
        assert!(matches!(Mcp9808Minimal::new(i2c.clone(), ADDR), Err(Mcp9808Error::NotFound)));
        i2c.done();
    }

    #[test]
    fn minimal_temperature_decoding() {
        let mut t = identity();
        t.extend([rd(REG_TA, 0x0194), rd(REG_TA, 0xE194), rd(REG_TA, 0x1FF0), rd(REG_TA, 0x1E6C), rd(REG_TA, 0x0001)]);
        let mut i2c = I2cMock::new(&t);
        let mut sensor = Mcp9808Minimal::new(i2c.clone(), ADDR).expect("init");
        assert_eq!(sensor.read_temperature().unwrap(), 25.25);
        assert_eq!(sensor.read_temperature().unwrap(), 25.25); // flag bits masked
        assert_eq!(sensor.read_temperature().unwrap(), -1.0);
        assert_eq!(sensor.read_temperature().unwrap(), -25.25);
        assert_eq!(sensor.read_temperature().unwrap(), 0.0625);
        i2c.done();
    }

    #[test]
    fn full_limits_resolution_hysteresis() {
        let mut t = identity();
        t.extend([
            wr(REG_TUPPER, 0x0500),                       // 80.0
            rd(REG_TUPPER, 0x0500),
            wr(REG_TLOWER, 0x1E70),                       // -25.0
            rd(REG_TLOWER, 0x1E70),
            wr(REG_TCRIT, 0x1FB0),                        // -5.1 rounds to -5.0
            wr(REG_TCRIT, 0x0164),                        // 22.13 rounds to 22.25
            wr(REG_TUPPER, 0x0FFC),                       // clamps to 255.75
            rd(REG_TUPPER, 0x0FFC),
            wr(REG_TLOWER, 0x1000),                       // clamps to -256.0
            rd(REG_TLOWER, 0x1000),
            I2cTransaction::write(ADDR, vec![REG_RESOLUTION, 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_RESOLUTION], vec![0x01]),
            // set_resolution(0.3) is rejected without a bus transaction.
            rd(REG_CONFIG, 0x0000),                       // set_hysteresis(3.0)
            wr(REG_CONFIG, 0x0400),
            rd(REG_CONFIG, 0x0400),                       // get_hysteresis
            // set_hysteresis(2.0) is rejected without a bus transaction.
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Mcp9808Full::new(i2c.clone(), ADDR).expect("init");
        s.set_upper_limit(80.0).unwrap();
        assert_eq!(s.get_upper_limit().unwrap(), 80.0);
        s.set_lower_limit(-25.0).unwrap();
        assert_eq!(s.get_lower_limit().unwrap(), -25.0);
        s.set_critical_limit(-5.1).unwrap();
        s.set_critical_limit(22.13).unwrap();
        s.set_upper_limit(1000.0).unwrap();
        assert_eq!(s.get_upper_limit().unwrap(), 255.75);
        s.set_lower_limit(-1000.0).unwrap();
        assert_eq!(s.get_lower_limit().unwrap(), -256.0);
        s.set_resolution(0.25).unwrap();
        assert_eq!(s.get_resolution().unwrap(), 0.25);
        assert!(matches!(s.set_resolution(0.3), Err(Mcp9808Error::InvalidArgument)));
        s.set_hysteresis(3.0).unwrap();
        assert_eq!(s.get_hysteresis().unwrap(), 3.0);
        assert!(matches!(s.set_hysteresis(2.0), Err(Mcp9808Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn full_config_shutdown_locks_alert() {
        let mut t = identity();
        t.extend([
            rd(REG_CONFIG, 0x0400), wr(REG_CONFIG, 0x0500),   // shutdown keeps THYST
            rd(REG_CONFIG, 0x0500),                           // is_shutdown
            rd(REG_CONFIG, 0x0500), wr(REG_CONFIG, 0x0400),   // wake
            rd(REG_CONFIG, 0x0080),                           // shutdown while locked: no write
            rd(REG_CONFIG, 0x0000), wr(REG_CONFIG, 0x0080),   // lock_critical_limit
            rd(REG_CONFIG, 0x0080),                           // is_critical_limit_locked
            rd(REG_CONFIG, 0x0000), wr(REG_CONFIG, 0x0040),   // lock_window_limits
            rd(REG_CONFIG, 0x0040),                           // is_window_limits_locked
            rd(REG_CONFIG, 0x0000), wr(REG_CONFIG, 0x0007),   // configure_alert(CriticalOnly, Interrupt, ActiveHigh)
            rd(REG_CONFIG, 0x0040),                           // configure_alert while locked: no write
            rd(REG_CONFIG, 0x0000), wr(REG_CONFIG, 0x0008),   // enable_alert
            rd(REG_CONFIG, 0x0008), wr(REG_CONFIG, 0x0000),   // disable_alert
            rd(REG_CONFIG, 0x0019),                           // is_alert_asserted
            rd(REG_CONFIG, 0x0019), wr(REG_CONFIG, 0x0029),   // clear_interrupt: no ALERT_STAT, sets INT_CLEAR
            rd(REG_TA, 0x0194), rd(REG_TA, 0x2194), rd(REG_TA, 0xC194),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Mcp9808Full::new(i2c.clone(), ADDR).expect("init");
        s.shutdown().unwrap();
        assert!(s.is_shutdown().unwrap());
        s.wake().unwrap();
        s.shutdown().unwrap();
        s.lock_critical_limit().unwrap();
        assert!(s.is_critical_limit_locked().unwrap());
        s.lock_window_limits().unwrap();
        assert!(s.is_window_limits_locked().unwrap());
        s.configure_alert(Mcp9808AlertMode::CriticalOnly, Mcp9808AlertOutput::Interrupt,
                          Mcp9808AlertPolarity::ActiveHigh).unwrap();
        assert!(matches!(
            s.configure_alert(Mcp9808AlertMode::All, Mcp9808AlertOutput::Interrupt, Mcp9808AlertPolarity::ActiveLow),
            Err(Mcp9808Error::Locked)
        ));
        s.enable_alert().unwrap();
        s.disable_alert().unwrap();
        assert!(s.is_alert_asserted().unwrap());
        s.clear_interrupt().unwrap();
        assert_eq!(s.poll_interrupt().unwrap(), 0);
        assert_eq!(s.poll_interrupt().unwrap(), MCP9808_SOURCE_LOWER);
        assert_eq!(s.poll_interrupt().unwrap(), MCP9808_SOURCE_UPPER | MCP9808_SOURCE_CRITICAL);
        i2c.done();
    }
}
