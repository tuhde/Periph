//! MB85RC256V — 256 Kbit (32 KiB) I²C FRAM (ferroelectric RAM).
//!
//! Non-volatile like EEPROM, but every write completes at bus speed: no
//! write cycle, no page buffer, no ACK polling and at least 10¹² write
//! cycles per byte. The chip has no configuration registers; it is ready
//! for use immediately after power-on. [`Mb85rcMinimal::new`] and
//! [`Mb85rcFull::new`] perform no I²C traffic.
//!
//! Memory layout: 0x0000-0x7FFF, 15-bit address sent big-endian (2 bytes).
//! Sequential accesses wrap silently in hardware at 0x7FFF; this driver
//! rejects any range that would wrap with [`Error::OutOfRange`].
//!
//! The 3-byte Device ID is read at the reserved I²C address 0x7C. Since
//! `embedded-hal` addresses the device per call, [`Mb85rcFull::read_device_id`]
//! reuses the same bus. Some hosts (Linux `i2c-dev`) refuse 0x78-0x7F; the
//! bus error is returned then.

use embedded_hal::i2c::I2c;

use crate::connection::register;

const SIZE: usize = 0x8000;
const REG_BYTES: u8 = 2;
const ID_ADDR: u8 = 0x7C;
const ID_WORD_BASE: u8 = 0xA0;
/// Largest `max_chunk` accepted (limited by the register helper's buffer).
const MAX_CHUNK_LIMIT: usize = 32;

/// Driver error: either an I²C bus error or an address-range violation.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// Address or range outside 0x0000-0x7FFF (no silent wrap-around).
    OutOfRange,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// MB85RC minimal driver — single-byte persistent storage.
///
/// Default configuration: nothing to configure; the full 32 KiB is
/// addressable and `write_byte` returns as soon as the transfer completes.
pub struct Mb85rcMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Mb85rcMinimal<I2C> {
    /// Create a new `Mb85rcMinimal`; no bus traffic.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address `0x50`-`0x57` (set by pins A2:A0).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Read a single byte (random read).
    ///
    /// # Arguments
    /// * `address` — Memory address 0-32767.
    pub fn read_byte(&mut self, address: u16) -> Result<u8, Error<I2C::Error>> {
        if address as usize >= SIZE {
            return Err(Error::OutOfRange);
        }
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, address.into(), REG_BYTES, &mut buf)?;
        Ok(buf[0])
    }

    /// Write a single byte; returns when the transfer completes (no polling).
    ///
    /// # Arguments
    /// * `address` — Memory address 0-32767.
    /// * `value`   — Byte value 0-255.
    pub fn write_byte(&mut self, address: u16, value: u8) -> Result<(), Error<I2C::Error>> {
        if address as usize >= SIZE {
            return Err(Error::OutOfRange);
        }
        register::write_register(&mut self.i2c, self.addr, address.into(), REG_BYTES, &[value])?;
        Ok(())
    }
}

/// MB85RC full driver — extends [`Mb85rcMinimal`] with block access and Device ID.
///
/// Adds chunked sequential read/write, range fill, the array size, a
/// settable `max_chunk` and the Device ID.
pub struct Mb85rcFull<I2C> {
    inner: Mb85rcMinimal<I2C>,
    max_chunk: usize,
}

fn check_range<E>(address: u16, length: usize) -> Result<(), Error<E>> {
    if address as usize + length > SIZE {
        Err(Error::OutOfRange)
    } else {
        Ok(())
    }
}

impl<I2C: I2c> Mb85rcFull<I2C> {
    /// Create a new `Mb85rcFull`; same arguments as [`Mb85rcMinimal::new`].
    /// `max_chunk` defaults to 30.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Mb85rcMinimal::new(i2c, addr), max_chunk: 30 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Read a single byte; delegates to the inner driver.
    pub fn read_byte(&mut self, address: u16) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_byte(address)
    }

    /// Write a single byte; delegates to the inner driver.
    pub fn write_byte(&mut self, address: u16, value: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.write_byte(address, value)
    }

    /// Maximum data bytes per bus transaction (default 30).
    pub fn max_chunk(&self) -> usize {
        self.max_chunk
    }

    /// Set the maximum data bytes per bus transaction, clamped to 1-32.
    ///
    /// Arduino `Wire` limits transfers to 32 bytes including the 2 address
    /// bytes, hence the default of 30.
    pub fn set_max_chunk(&mut self, value: usize) {
        self.max_chunk = value.clamp(1, MAX_CHUNK_LIMIT);
    }

    /// Array size in bytes (32768).
    pub fn size(&self) -> usize {
        SIZE
    }

    /// Sequential read of `buf.len()` bytes starting at `address`, in `max_chunk` pieces.
    ///
    /// Returns [`Error::OutOfRange`] if the range does not lie inside 0x0000-0x7FFF.
    pub fn read(&mut self, address: u16, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        check_range(address, buf.len())?;
        let mut addr = address;
        for chunk in buf.chunks_mut(self.max_chunk) {
            register::read_register(&mut self.inner.i2c, self.inner.addr, addr.into(), REG_BYTES, chunk)?;
            addr = addr.wrapping_add(chunk.len() as u16);
        }
        Ok(())
    }

    /// Sequential write of `data` starting at `address`, in `max_chunk` pieces.
    ///
    /// Each chunk is a fresh transaction; no delay between chunks. Returns
    /// [`Error::OutOfRange`] if the range does not lie inside 0x0000-0x7FFF.
    pub fn write(&mut self, address: u16, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        check_range(address, data.len())?;
        let mut addr = address;
        for chunk in data.chunks(self.max_chunk) {
            register::write_register(&mut self.inner.i2c, self.inner.addr, addr.into(), REG_BYTES, chunk)?;
            addr = addr.wrapping_add(chunk.len() as u16);
        }
        Ok(())
    }

    /// Write `value` over `length` bytes starting at `address`, chunked.
    ///
    /// Returns [`Error::OutOfRange`] if the range does not lie inside 0x0000-0x7FFF.
    pub fn fill(&mut self, address: u16, length: usize, value: u8) -> Result<(), Error<I2C::Error>> {
        check_range(address, length)?;
        let block = [value; MAX_CHUNK_LIMIT];
        let mut addr = address;
        let mut remaining = length;
        while remaining > 0 {
            let n = remaining.min(self.max_chunk);
            register::write_register(&mut self.inner.i2c, self.inner.addr, addr.into(), REG_BYTES, &block[..n])?;
            addr = addr.wrapping_add(n as u16);
            remaining -= n;
        }
        Ok(())
    }

    /// Read the Device ID via the reserved address 0x7C.
    ///
    /// Sends the device address word `0xA0 + (A2A1A0 × 2)` followed by a
    /// repeated START and a 3-byte read. Returns
    /// `(manufacturer, density, product)` = `(0x00A, 0x5, 0x10)` for the
    /// MB85RC256V.
    pub fn read_device_id(&mut self) -> Result<(u16, u8, u8), Error<I2C::Error>> {
        let word = ID_WORD_BASE + ((self.inner.addr & 0x07) << 1);
        let mut raw = [0u8; 3];
        register::read_register(&mut self.inner.i2c, ID_ADDR, word.into(), 1, &mut raw)?;
        let manufacturer = ((raw[0] as u16) << 4) | (raw[1] >> 4) as u16;
        Ok((manufacturer, raw[1] & 0x0F, raw[2]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x50;

    #[test]
    fn minimal_byte_access() {
        let t = vec![
            I2cTransaction::write_read(ADDR, vec![0x12, 0x34], vec![0x42]),
            I2cTransaction::write(ADDR, vec![0x00, 0x10, 0x99]),
        ];
        let mut chip = Mb85rcMinimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(chip.read_byte(0x1234).unwrap(), 0x42);
        chip.write_byte(0x0010, 0x99).unwrap();
        assert_eq!(chip.read_byte(0x8000), Err(Error::OutOfRange));
        assert_eq!(chip.write_byte(0x8000, 1), Err(Error::OutOfRange));
        chip.i2c.done();
    }

    #[test]
    fn chunked_write_read_fill() {
        let data: Vec<u8> = (0..70).collect();
        let t = vec![
            I2cTransaction::write(ADDR, [vec![0x01, 0x00], data[..30].to_vec()].concat()),
            I2cTransaction::write(ADDR, [vec![0x01, 30], data[30..60].to_vec()].concat()),
            I2cTransaction::write(ADDR, [vec![0x01, 60], data[60..].to_vec()].concat()),
            I2cTransaction::write_read(ADDR, vec![0x01, 0x00], data[..30].to_vec()),
            I2cTransaction::write_read(ADDR, vec![0x01, 30], data[30..60].to_vec()),
            I2cTransaction::write_read(ADDR, vec![0x01, 60], data[60..].to_vec()),
            // max_chunk = 8: fill 20 bytes -> 8 + 8 + 4
            I2cTransaction::write(ADDR, [vec![0x03, 0x00], vec![0xAB; 8]].concat()),
            I2cTransaction::write(ADDR, [vec![0x03, 0x08], vec![0xAB; 8]].concat()),
            I2cTransaction::write(ADDR, [vec![0x03, 0x10], vec![0xAB; 4]].concat()),
        ];
        let mut chip = Mb85rcFull::new(I2cMock::new(&t), ADDR);
        assert_eq!(chip.size(), 32768);
        assert_eq!(chip.max_chunk(), 30);
        chip.write(0x0100, &data).unwrap();
        let mut buf = [0u8; 70];
        chip.read(0x0100, &mut buf).unwrap();
        assert_eq!(&buf[..], &data[..]);
        chip.set_max_chunk(8);
        assert_eq!(chip.max_chunk(), 8);
        chip.fill(0x0300, 20, 0xAB).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn range_rules() {
        let t = vec![I2cTransaction::write(ADDR, vec![0x7F, 0xFE, 1, 2])];
        let mut chip = Mb85rcFull::new(I2cMock::new(&t), ADDR);
        chip.write(0x7FFE, &[1, 2]).unwrap();
        assert_eq!(chip.write(0x7FFF, &[1, 2]), Err(Error::OutOfRange));
        let mut buf = [0u8; 0x20];
        assert_eq!(chip.read(0x7FF0, &mut buf), Err(Error::OutOfRange));
        assert_eq!(chip.fill(0x7FFF, 2, 0), Err(Error::OutOfRange));
        chip.inner.i2c.done();
    }

    #[test]
    fn device_id() {
        // A2A1A0 = 3 -> address word 0xA6.
        let t = vec![I2cTransaction::write_read(0x7C, vec![0xA6], vec![0x00, 0xA5, 0x10])];
        let mut chip = Mb85rcFull::new(I2cMock::new(&t), 0x53);
        assert_eq!(chip.read_device_id().unwrap(), (0x00A, 0x5, 0x10));
        chip.inner.i2c.done();
    }
}
