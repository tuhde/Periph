//! ISL29125 — digital red, green and blue colour light sensor (Renesas / Intersil).
//!
//! RGB colour sensor with an on-chip IR-blocking filter, a 12/16-bit integrating
//! ADC (16 bit: 100 ms), two sensing ranges (375 lux and 10 000 lux full scale),
//! programmable IR compensation, and a threshold window with persistence on one
//! selectable colour channel that drives the open-drain INT pin. Results are
//! unsigned 16-bit counts, LSB first. The 7-bit I²C address is fixed at `0x44`.
//!
//! The driver uses register access (1-byte address, auto-increment on block
//! transfers). Reading `STATUS` clears its flags, so the driver keeps the flags
//! it has seen until [`Isl29125Full::poll_interrupt`] / [`Isl29125Full::status`]
//! hands them out.
//!
//! A delay provider is needed for [`Isl29125Minimal::read`] (waiting for a new
//! result) and [`Isl29125Full::soft_reset`]. The interrupt is exposed as
//! [`Isl29125Full::poll_interrupt`] only: wire your own GPIO to INT and call it
//! from the falling-edge handler.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// Fixed 7-bit I²C address.
pub const ISL29125_I2C_ADDRESS: u8 = 0x44;
/// Expected `DEVICE_ID` value.
pub const ISL29125_DEVICE_ID: u8 = 0x7D;
/// Interrupt source: selected channel outside the threshold window.
pub const ISL29125_SOURCE_THRESHOLD: u8 = 0x01;
/// Interrupt source: conversion complete.
pub const ISL29125_SOURCE_CONVERSION: u8 = 0x02;
/// `STATUS` bit 2: power-down or brown-out occurred.
pub const ISL29125_STATUS_BROWNOUT: u8 = 0x04;
/// `STATUS` bit 1: conversion completed.
pub const ISL29125_STATUS_CONVERSION: u8 = 0x02;
/// `STATUS` bit 0: threshold window crossed.
pub const ISL29125_STATUS_THRESHOLD: u8 = 0x01;

const REG_DEVICE_ID: u8 = 0x00;
const REG_CONFIG1: u8 = 0x01;
const REG_CONFIG2: u8 = 0x02;
const REG_CONFIG3: u8 = 0x03;
const REG_LOW_TH: u8 = 0x04;
const REG_HIGH_TH: u8 = 0x06;
const REG_STATUS: u8 = 0x08;
const REG_GREEN: u8 = 0x09;
const REG_RED: u8 = 0x0B;
const REG_BLUE: u8 = 0x0D;

const RESET_VALUE: u8 = 0x46;
const CFG1_SYNC: u8 = 0x20;
const CFG1_BITS_12: u8 = 0x10;
const CFG1_RNG_10K: u8 = 0x08;
const CFG1_MODE: u8 = 0x07;
const CFG3_CONVEN: u8 = 0x10;

const PERSISTENCE: [u8; 4] = [1, 2, 4, 8];
const POLL_STEP_MS: u32 = 10;
const WAIT_LIMIT_MS: u32 = 400;

/// The three channel counts of one conversion (unsigned 16 bit).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Isl29125Counts {
    /// Red count.
    pub red: u16,
    /// Green count.
    pub green: u16,
    /// Blue count.
    pub blue: u16,
}

/// Operating mode (`CONFIG1` bits 2:0); conversions are continuous.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Isl29125Mode {
    /// Power-down.
    PowerDown = 0,
    /// Green only.
    Green = 1,
    /// Red only.
    Red = 2,
    /// Blue only.
    Blue = 3,
    /// Standby (no conversion).
    Standby = 4,
    /// Green, red and blue.
    Rgb = 5,
    /// Green and red.
    GreenRed = 6,
    /// Green and blue.
    GreenBlue = 7,
}

/// Interrupt channel selection (`INTSEL`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Isl29125Channel {
    /// No channel (threshold interrupt off).
    None = 0,
    /// Green channel.
    Green = 1,
    /// Red channel.
    Red = 2,
    /// Blue channel.
    Blue = 3,
}

/// Errors returned by the driver.
#[derive(Debug)]
pub enum Isl29125Error<E> {
    /// I²C bus error.
    I2c(E),
    /// `DEVICE_ID` does not identify an ISL29125.
    NotFound(u8),
    /// An argument is outside the supported values; nothing was written.
    InvalidArgument,
}

impl<E> From<E> for Isl29125Error<E> {
    fn from(e: E) -> Self {
        Isl29125Error::I2c(e)
    }
}

/// ISL29125 — minimal interface: read the three channel counts.
pub struct Isl29125Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    pending: u8,
}

impl<I2C: I2c> Isl29125Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). `addr` is [`ISL29125_I2C_ADDRESS`].
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, pending: 0 }
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    pub(crate) fn update_reg(&mut self, reg: u8, set: u8, clear: u8) -> Result<(), I2C::Error> {
        let value = self.read_reg(reg)?;
        self.write_reg(reg, (value & !clear) | set)
    }

    pub(crate) fn read_status(&mut self) -> Result<u8, I2C::Error> {
        let value = self.read_reg(REG_STATUS)?;
        self.pending |=
            value & (ISL29125_STATUS_BROWNOUT | ISL29125_STATUS_CONVERSION | ISL29125_STATUS_THRESHOLD);
        Ok(value)
    }

    pub(crate) fn read_block(&mut self) -> Result<Isl29125Counts, I2C::Error> {
        let mut raw = [0u8; 6];
        register::read_register(&mut self.i2c, self.addr, REG_GREEN.into(), 1, &mut raw)?;
        Ok(Isl29125Counts {
            green: u16::from_le_bytes([raw[0], raw[1]]),
            red: u16::from_le_bytes([raw[2], raw[3]]),
            blue: u16::from_le_bytes([raw[4], raw[5]]),
        })
    }

    pub(crate) fn read_channel(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut raw = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut raw)?;
        Ok(u16::from_le_bytes(raw))
    }

    /// Start the sensor: read `DEVICE_ID` (must be `0x7D`), clear the brown-out
    /// flag and write `CONFIG1` = `0x0D` (RGB, 10 000 lux, 16 bit), `CONFIG2` =
    /// `0xBF` (maximum IR compensation), `CONFIG3` = `0x00`.
    pub fn init(&mut self) -> Result<(), Isl29125Error<I2C::Error>> {
        let id = self.read_reg(REG_DEVICE_ID)?;
        if id != ISL29125_DEVICE_ID {
            return Err(Isl29125Error::NotFound(id));
        }
        self.write_reg(REG_STATUS, 0x00)?;
        self.write_reg(REG_CONFIG3, 0x00)?;
        self.write_reg(REG_CONFIG2, 0xBF)?;
        self.write_reg(REG_CONFIG1, 0x0D)?;
        Ok(())
    }

    /// Read red, green and blue from one conversion. Waits for a new result (at
    /// most 400 ms), then block-reads `0x09`–`0x0E`.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Isl29125Counts, I2C::Error> {
        let mut waited = 0;
        while waited <= WAIT_LIMIT_MS {
            if self.read_status()? & ISL29125_STATUS_CONVERSION != 0 {
                break;
            }
            delay.delay_ms(POLL_STEP_MS);
            waited += POLL_STEP_MS;
        }
        self.read_block()
    }
}

/// ISL29125 — full interface: single-channel reads, mode, range, resolution,
/// synchronisation and IR-compensation configuration, thresholds, persistence,
/// interrupt polling.
pub struct Isl29125Full<I2C> {
    inner: Isl29125Minimal<I2C>,
}

impl<I2C: I2c> Isl29125Full<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Isl29125Minimal::new(i2c, addr) }
    }

    /// Start the sensor. See [`Isl29125Minimal::init`].
    pub fn init(&mut self) -> Result<(), Isl29125Error<I2C::Error>> {
        self.inner.init()
    }

    /// Read all three channels. See [`Isl29125Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Isl29125Counts, I2C::Error> {
        self.inner.read(delay)
    }

    /// Read the red channel (2-byte read).
    pub fn read_red(&mut self) -> Result<u16, I2C::Error> {
        self.inner.read_channel(REG_RED)
    }

    /// Read the green channel.
    pub fn read_green(&mut self) -> Result<u16, I2C::Error> {
        self.inner.read_channel(REG_GREEN)
    }

    /// Read the blue channel.
    pub fn read_blue(&mut self) -> Result<u16, I2C::Error> {
        self.inner.read_channel(REG_BLUE)
    }

    /// Block-read all three channels without waiting.
    pub fn read_rgb(&mut self) -> Result<Isl29125Counts, I2C::Error> {
        self.inner.read_block()
    }

    /// Report whether a conversion has completed. Reads `STATUS`, which clears
    /// its flags; the flags are kept for [`Self::poll_interrupt`] and [`Self::status`].
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_status()? & ISL29125_STATUS_CONVERSION != 0)
    }

    /// Select the operating mode (conversions are continuous).
    pub fn set_mode(&mut self, mode: Isl29125Mode) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_CONFIG1, mode as u8, CFG1_MODE)
    }

    /// Select the sensing range: 375 or 10000 lux full scale.
    pub fn set_range(&mut self, lux: u16) -> Result<(), Isl29125Error<I2C::Error>> {
        match lux {
            375 => self.inner.update_reg(REG_CONFIG1, 0, CFG1_RNG_10K)?,
            10000 => self.inner.update_reg(REG_CONFIG1, CFG1_RNG_10K, 0)?,
            _ => return Err(Isl29125Error::InvalidArgument),
        }
        Ok(())
    }

    /// Select the ADC resolution: 12 or 16 bit.
    pub fn set_resolution(&mut self, bits: u8) -> Result<(), Isl29125Error<I2C::Error>> {
        match bits {
            16 => self.inner.update_reg(REG_CONFIG1, 0, CFG1_BITS_12)?,
            12 => self.inner.update_reg(REG_CONFIG1, CFG1_BITS_12, 0)?,
            _ => return Err(Isl29125Error::InvalidArgument),
        }
        Ok(())
    }

    /// Select the conversion start source: `true` starts on the rising edge of
    /// INT (INT becomes an input), `false` on the I²C write.
    pub fn set_sync(&mut self, external: bool) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_CONFIG1, if external { CFG1_SYNC } else { 0 }, CFG1_SYNC)
    }

    /// Set the active IR compensation (`CONFIG2`): `offset` false = codes 0–63,
    /// true = codes 106–169; `adjust` 0–63.
    pub fn set_ir_compensation(&mut self, offset: bool, adjust: u8) -> Result<(), Isl29125Error<I2C::Error>> {
        if adjust > 63 {
            return Err(Isl29125Error::InvalidArgument);
        }
        self.inner.write_reg(REG_CONFIG2, if offset { 0x80 } else { 0 } | adjust)?;
        Ok(())
    }

    /// Read `DEVICE_ID` (`0x7D` for the ISL29125).
    pub fn device_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_DEVICE_ID)
    }

    /// Read `STATUS` (clearing the flags on the chip), with the flags seen since
    /// the last call merged in.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let value = self.inner.read_status()? | self.inner.pending;
        self.inner.pending = 0;
        Ok(value)
    }

    /// Reset all registers to their defaults (writes `0x46` to register `0x00`).
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_DEVICE_ID, RESET_VALUE)?;
        delay.delay_ms(10);
        self.inner.pending = 0;
        Ok(())
    }

    /// Set the 16-bit threshold window (`low` ≤ `high`).
    pub fn set_thresholds(&mut self, low: u16, high: u16) -> Result<(), Isl29125Error<I2C::Error>> {
        if low > high {
            return Err(Isl29125Error::InvalidArgument);
        }
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_LOW_TH.into(), 1, &low.to_le_bytes())?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_HIGH_TH.into(), 1, &high.to_le_bytes())?;
        Ok(())
    }

    /// Read the threshold window as `(low, high)`.
    pub fn get_thresholds(&mut self) -> Result<(u16, u16), I2C::Error> {
        let mut lo = [0u8; 2];
        let mut hi = [0u8; 2];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_LOW_TH.into(), 1, &mut lo)?;
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_HIGH_TH.into(), 1, &mut hi)?;
        Ok((u16::from_le_bytes(lo), u16::from_le_bytes(hi)))
    }

    /// Select the channel the threshold interrupt monitors.
    pub fn set_interrupt_channel(&mut self, channel: Isl29125Channel) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_CONFIG3, channel as u8, 0x03)
    }

    /// Set the number of integration cycles (1, 2, 4 or 8) before the interrupt asserts.
    pub fn set_persistence(&mut self, count: u8) -> Result<(), Isl29125Error<I2C::Error>> {
        let code = PERSISTENCE.iter().position(|&c| c == count).ok_or(Isl29125Error::InvalidArgument)?;
        self.inner.update_reg(REG_CONFIG3, (code as u8) << 2, 0x0C)?;
        Ok(())
    }

    /// Enable an interrupt source ([`ISL29125_SOURCE_THRESHOLD`] or
    /// [`ISL29125_SOURCE_CONVERSION`]). For the threshold source select the
    /// channel beforehand; green is used if none is set.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        if source == ISL29125_SOURCE_CONVERSION {
            self.inner.update_reg(REG_CONFIG3, CFG3_CONVEN, 0)
        } else if self.inner.read_reg(REG_CONFIG3)? & 0x03 == 0 {
            self.inner.update_reg(REG_CONFIG3, Isl29125Channel::Green as u8, 0)
        } else {
            Ok(())
        }
    }

    /// Disable an interrupt source (threshold: `INTSEL` = none; conversion: `CONVEN` = 0).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        if source == ISL29125_SOURCE_CONVERSION {
            self.inner.update_reg(REG_CONFIG3, 0, CFG3_CONVEN)
        } else {
            self.inner.update_reg(REG_CONFIG3, 0, 0x03)
        }
    }

    /// Read `STATUS` (clearing the flags) and return it; bit 0
    /// ([`ISL29125_STATUS_THRESHOLD`]) is set if the window was crossed. Wire
    /// your own GPIO to INT and call this from its falling-edge handler.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        self.status()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = ISL29125_I2C_ADDRESS;

    fn w(reg: u8, v: u8) -> T {
        T::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![v])
    }

    fn init_t() -> Vec<T> {
        vec![r(0, 0x7D), w(8, 0x00), w(3, 0x00), w(2, 0xBF), w(1, 0x0D)]
    }

    fn full(extra: Vec<T>) -> (I2cMock, Isl29125Full<I2cMock>) {
        let mut t = init_t();
        t.extend(extra);
        let i2c = I2cMock::new(&t);
        let mut chip = Isl29125Full::new(i2c.clone(), ADDR);
        chip.init().unwrap();
        (i2c, chip)
    }

    #[test]
    fn init_writes_defaults() {
        let (mut i2c, _chip) = full(vec![]);
        i2c.done();
    }

    #[test]
    fn init_rejects_wrong_id() {
        let mut i2c = I2cMock::new(&[r(0, 0x00)]);
        let mut chip = Isl29125Minimal::new(i2c.clone(), ADDR);
        assert!(matches!(chip.init(), Err(Isl29125Error::NotFound(0x00))));
        i2c.done();
    }

    #[test]
    fn read_waits_for_data_and_decodes_channels() {
        let block = vec![0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00];
        let (mut i2c, mut chip) = full(vec![r(8, 0x00), r(8, 0x02), T::write_read(ADDR, vec![0x09], block)]);
        let c = chip.read(&mut NoopDelay::new()).unwrap();
        assert_eq!(c, Isl29125Counts { red: 0xFFFF, green: 0x1234, blue: 1 });
        i2c.done();
    }

    #[test]
    fn single_channels_use_their_registers() {
        let (mut i2c, mut chip) = full(vec![
            T::write_read(ADDR, vec![0x0B], vec![0x01, 0x00]),
            T::write_read(ADDR, vec![0x09], vec![0x02, 0x01]),
            T::write_read(ADDR, vec![0x0D], vec![0x03, 0x00]),
        ]);
        assert_eq!(chip.read_red().unwrap(), 1);
        assert_eq!(chip.read_green().unwrap(), 0x0102);
        assert_eq!(chip.read_blue().unwrap(), 3);
        i2c.done();
    }

    #[test]
    fn configuration_is_read_modify_write() {
        let (mut i2c, mut chip) = full(vec![
            r(1, 0x0D), w(1, 0x0A),    // mode red
            r(1, 0x0A), w(1, 0x02),    // range 375
            r(1, 0x02), w(1, 0x0A),    // range 10000
            r(1, 0x0A), w(1, 0x1A),    // 12 bit
            r(1, 0x1A), w(1, 0x0A),    // 16 bit
            r(1, 0x0A), w(1, 0x2A),    // external sync
            w(2, 0xBF),                // IR compensation offset, adjust 0x3F
        ]);
        chip.set_mode(Isl29125Mode::Red).unwrap();
        chip.set_range(375).unwrap();
        chip.set_range(10000).unwrap();
        chip.set_resolution(12).unwrap();
        chip.set_resolution(16).unwrap();
        chip.set_sync(true).unwrap();
        chip.set_ir_compensation(true, 0x3F).unwrap();
        i2c.done();
    }

    #[test]
    fn invalid_arguments_write_nothing() {
        let (mut i2c, mut chip) = full(vec![]);
        assert!(matches!(chip.set_range(100), Err(Isl29125Error::InvalidArgument)));
        assert!(matches!(chip.set_resolution(8), Err(Isl29125Error::InvalidArgument)));
        assert!(matches!(chip.set_ir_compensation(false, 64), Err(Isl29125Error::InvalidArgument)));
        assert!(matches!(chip.set_persistence(3), Err(Isl29125Error::InvalidArgument)));
        assert!(matches!(chip.set_thresholds(5, 4), Err(Isl29125Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn soft_reset_writes_0x46() {
        let (mut i2c, mut chip) = full(vec![w(0, 0x46)]);
        chip.soft_reset(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn thresholds_encode_two_bytes() {
        let (mut i2c, mut chip) = full(vec![
            T::write(ADDR, vec![0x04, 0x34, 0x12]),
            T::write(ADDR, vec![0x06, 0xCD, 0xAB]),
            T::write_read(ADDR, vec![0x04], vec![0x34, 0x12]),
            T::write_read(ADDR, vec![0x06], vec![0xCD, 0xAB]),
        ]);
        chip.set_thresholds(0x1234, 0xABCD).unwrap();
        assert_eq!(chip.get_thresholds().unwrap(), (0x1234, 0xABCD));
        i2c.done();
    }

    #[test]
    fn interrupt_configuration() {
        let (mut i2c, mut chip) = full(vec![
            r(3, 0x00), w(3, 0x02),          // channel red
            r(3, 0x02), w(3, 0x0E),          // persistence 8
            r(3, 0x0E), w(3, 0x1E),          // enable conversion
            r(3, 0x1E), w(3, 0x0E),          // disable conversion
            r(3, 0x0E), w(3, 0x0C),          // disable threshold
            r(3, 0x0C), r(3, 0x0C), w(3, 0x0D),   // enable threshold -> green default
        ]);
        chip.set_interrupt_channel(Isl29125Channel::Red).unwrap();
        chip.set_persistence(8).unwrap();
        chip.enable_interrupt(ISL29125_SOURCE_CONVERSION).unwrap();
        chip.disable_interrupt(ISL29125_SOURCE_CONVERSION).unwrap();
        chip.disable_interrupt(ISL29125_SOURCE_THRESHOLD).unwrap();
        chip.enable_interrupt(ISL29125_SOURCE_THRESHOLD).unwrap();
        i2c.done();
    }

    #[test]
    fn status_keeps_flags_across_data_ready() {
        let (mut i2c, mut chip) = full(vec![r(8, 0x03), r(8, 0x00), r(8, 0x00), r(8, 0x04)]);
        assert!(chip.data_ready().unwrap());
        assert!(!chip.data_ready().unwrap());
        assert_eq!(chip.poll_interrupt().unwrap(), 0x03);
        assert_eq!(chip.status().unwrap(), 0x04);
        i2c.done();
    }

    #[test]
    fn device_id_reads_register_zero() {
        let (mut i2c, mut chip) = full(vec![r(0, 0x7D)]);
        assert_eq!(chip.device_id().unwrap(), 0x7D);
        i2c.done();
    }
}
