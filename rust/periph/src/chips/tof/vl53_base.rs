//! Shared plumbing for ST's VL53 FlightSense Time-of-Flight ranging family.
//!
//! Internal (`pub(crate)`) — never used directly by applications. VL53L0X and
//! VL53L1X have different register maps (8-bit vs 16-bit register index), so
//! this holds no register addresses and no ranging logic, only what both
//! chips share: big-endian register access with a 1- or 2-byte index, the
//! bounded poll helper, the XSHUT boot wait, and the volatile re-addressing
//! helper. Each chip struct owns a [`Vl53Bus`] (composition). Rust drivers
//! expose `poll_interrupt` only, so there is no interrupt delivery here. See
//! `specs/tof/_vl53_base.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Default 7-bit I²C address of every family member.
pub(crate) const I2C_ADDRESS: u8 = 0x29;
/// Range < low threshold.
pub(crate) const SOURCE_LEVEL_LOW: u8 = 1;
/// Range > high threshold.
pub(crate) const SOURCE_LEVEL_HIGH: u8 = 2;
/// Range < low threshold or > high threshold.
pub(crate) const SOURCE_OUT_OF_WINDOW: u8 = 3;
/// A new measurement is available (driver default).
pub(crate) const SOURCE_NEW_SAMPLE_READY: u8 = 4;
/// low ≤ range ≤ high (VL53L1X only).
pub(crate) const SOURCE_IN_WINDOW: u8 = 5;

/// Poll attempts, one 1 ms delay each: the family's 500 ms timeout.
const POLL_ATTEMPTS: u32 = 500;
/// XSHUT-high → first I²C access (tBOOT ≤ 1.2 ms).
const BOOT_US: u32 = 1200;
/// Largest register write in the family (VL53L0X reference-SPAD map is 6 bytes).
const MAX_WRITE: usize = 16;

/// Register access over an `embedded-hal` I²C bus with a 1- or 2-byte
/// big-endian register index, plus the delay used for boot and polling.
pub(crate) struct Vl53Bus<I2C, D> {
    i2c: I2C,
    delay: D,
    addr: u8,
    index_bytes: usize,
}

impl<I2C: I2c, D: DelayNs> Vl53Bus<I2C, D> {
    /// `index_bytes` is the register index width on the wire, 1 or 2.
    pub(crate) fn new(i2c: I2C, addr: u8, delay: D, index_bytes: usize) -> Self {
        Self { i2c, delay, addr, index_bytes }
    }

    fn index(&self, reg: u16, buf: &mut [u8]) -> usize {
        if self.index_bytes == 2 {
            buf[0] = (reg >> 8) as u8;
            buf[1] = reg as u8;
            2
        } else {
            buf[0] = reg as u8;
            1
        }
    }

    pub(crate) fn wr_block(&mut self, reg: u16, data: &[u8]) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 2 + MAX_WRITE];
        let n = self.index(reg, &mut buf);
        let len = data.len().min(MAX_WRITE);
        buf[n..n + len].copy_from_slice(&data[..len]);
        self.i2c.write(self.addr, &buf[..n + len])
    }

    pub(crate) fn rd_block(&mut self, reg: u16, out: &mut [u8]) -> Result<(), I2C::Error> {
        let mut idx = [0u8; 2];
        let n = self.index(reg, &mut idx);
        self.i2c.write_read(self.addr, &idx[..n], out)
    }

    pub(crate) fn wr8(&mut self, reg: u16, value: u8) -> Result<(), I2C::Error> {
        self.wr_block(reg, &[value])
    }

    pub(crate) fn rd8(&mut self, reg: u16) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.rd_block(reg, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn wr16(&mut self, reg: u16, value: u16) -> Result<(), I2C::Error> {
        self.wr_block(reg, &value.to_be_bytes())
    }

    pub(crate) fn rd16(&mut self, reg: u16) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.rd_block(reg, &mut buf)?;
        Ok(u16::from_be_bytes(buf))
    }

    pub(crate) fn wr32(&mut self, reg: u16, value: u32) -> Result<(), I2C::Error> {
        self.wr_block(reg, &value.to_be_bytes())
    }

    pub(crate) fn rd32(&mut self, reg: u16) -> Result<u32, I2C::Error> {
        let mut buf = [0u8; 4];
        self.rd_block(reg, &mut buf)?;
        Ok(u32::from_be_bytes(buf))
    }

    /// Poll `predicate` up to 500 times with a 1 ms delay between attempts;
    /// `Ok(false)` means the 500 ms timeout expired.
    pub(crate) fn wait_until<F>(&mut self, mut predicate: F) -> Result<bool, I2C::Error>
    where
        F: FnMut(&mut Self) -> Result<bool, I2C::Error>,
    {
        for _ in 0..POLL_ATTEMPTS {
            if predicate(self)? {
                return Ok(true);
            }
            self.delay.delay_ms(1);
        }
        Ok(false)
    }

    /// Wait tBOOT (XSHUT is driven by the caller before construction).
    pub(crate) fn boot_wait(&mut self) {
        self.delay.delay_us(BOOT_US);
    }

    /// Write a new 7-bit address to `reg`; `Ok(false)` (no write) outside 0x08–0x77.
    pub(crate) fn set_address_reg(&mut self, reg: u16, address: u8) -> Result<bool, I2C::Error> {
        if !(0x08..=0x77).contains(&address) {
            return Ok(false);
        }
        self.wr8(reg, address & 0x7F)?;
        Ok(true)
    }

    /// Consume and return the I²C bus and delay.
    pub(crate) fn release(self) -> (I2C, D) {
        (self.i2c, self.delay)
    }
}
