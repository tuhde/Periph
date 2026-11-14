//! AT24C64 — 64 Kbit (8 KiB) I²C EEPROM (Atmel / Microchip).
//!
//! Writes are self-timed (10 ms max (20 ms at 1.8 V)): after the STOP that ends a write the
//! chip does not acknowledge its address until the cycle has finished. The
//! driver completes every write with **ACK polling** (an address-only write
//! that is acknowledged once the chip is ready), which is why the writing
//! methods take a [`DelayNs`] provider. The chip has no configuration
//! registers; [`At24c64Minimal::new`] and [`At24c64Full::new`] perform
//! no I²C traffic.
//!
//! Memory layout: 0x0000-0x1FFF, 13-bit address sent big-endian (2 bytes),
//! 256 pages of 32 bytes. A write that crosses a page boundary would wrap
//! inside the page and corrupt data, so [`At24c64Full::write`] splits at
//! page boundaries; sequential accesses also wrap silently in hardware at
//! 0x1FFF, which this driver rejects with [`Error::OutOfRange`].
//!
//! WP high inhibits writes to the upper quadrant 0x1800–0x1FFF; the driver cannot detect it —
//! verify with a read-back if the pin is wired.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const SIZE: usize = 0x2000;
const PAGE: usize = 32;
const REG_BYTES: u8 = 2;
/// tWR = 10 ms max (20 ms at 1.8 V), plus margin.
const WRITE_TIMEOUT_MS: u32 = 25;
/// Largest `max_chunk` accepted (limited by the register helper's buffer).
const MAX_CHUNK_LIMIT: usize = 32;

/// Driver error: an I²C bus error, an address-range violation or a write-cycle timeout.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// Address or range outside 0x0000-0x1FFF (no silent wrap-around).
    OutOfRange,
    /// `write_page` data would cross a 32-byte page boundary or exceed a page.
    PageBoundary,
    /// The chip did not acknowledge its address again within the write-cycle timeout.
    Timeout,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// AT24C64 minimal driver — single-byte persistent storage.
///
/// Default configuration: nothing to configure; the full 8 KiB is
/// addressable and `write_byte` returns after the write cycle has finished.
pub struct At24c64Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> At24c64Minimal<I2C> {
    /// Create a new `At24c64Minimal`; no bus traffic.
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

    /// ACK-poll: an address-only write is acknowledged once the write cycle has ended.
    fn wait_ready<D: DelayNs>(&mut self, timeout_ms: u32, delay: &mut D) -> bool {
        let mut t = 0;
        loop {
            if register::write_register(&mut self.i2c, self.addr, 0, REG_BYTES, &[]).is_ok() {
                return true;
            }
            if t >= timeout_ms {
                return false;
            }
            delay.delay_ms(1);
            t += 1;
        }
    }

    fn commit<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        if self.wait_ready(WRITE_TIMEOUT_MS, delay) {
            Ok(())
        } else {
            Err(Error::Timeout)
        }
    }

    /// Read a single byte (random read).
    ///
    /// # Arguments
    /// * `address` — Memory address 0-8191.
    pub fn read_byte(&mut self, address: u16) -> Result<u8, Error<I2C::Error>> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, address.into(), REG_BYTES, &mut buf)?;
        Ok(buf[0])
    }

    /// Write a single byte, then ACK-poll until the write cycle completes.
    ///
    /// # Arguments
    /// * `address` — Memory address 0-8191.
    /// * `value`   — Byte value 0-255.
    /// * `delay`   — Delay provider for the 1 ms polling interval.
    ///
    /// Returns [`Error::Timeout`] if the chip does not answer again in time.
    pub fn write_byte<D: DelayNs>(&mut self, address: u16, value: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        register::write_register(&mut self.i2c, self.addr, address.into(), REG_BYTES, &[value])?;
        self.commit(delay)
    }
}

/// AT24C64 full driver — extends [`At24c64Minimal`] with block access and page writes.
///
/// Adds chunked sequential read, page-boundary-aware write, page write,
/// range fill, array and page size, explicit ACK polling and a settable
/// `max_chunk`.
pub struct At24c64Full<I2C> {
    inner: At24c64Minimal<I2C>,
    max_chunk: usize,
}

fn check_range<E>(address: u16, length: usize) -> Result<(), Error<E>> {
    if address as usize + length > SIZE {
        Err(Error::OutOfRange)
    } else {
        Ok(())
    }
}

impl<I2C: I2c> At24c64Full<I2C> {
    /// Create a new `At24c64Full`; same arguments as [`At24c64Minimal::new`].
    /// `max_chunk` defaults to 30.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: At24c64Minimal::new(i2c, addr), max_chunk: 30 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Read a single byte; delegates to the inner driver.
    pub fn read_byte(&mut self, address: u16) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_byte(address)
    }

    /// Write a single byte and ACK-poll; delegates to the inner driver.
    pub fn write_byte<D: DelayNs>(&mut self, address: u16, value: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.write_byte(address, value, delay)
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

    /// Array size in bytes (8192).
    pub fn size(&self) -> usize {
        SIZE
    }

    /// Write page size in bytes (32).
    pub fn page_size(&self) -> usize {
        PAGE
    }

    /// ACK-poll until the chip answers its device address again; `true` if it did within `timeout_ms`.
    pub fn wait_ready<D: DelayNs>(&mut self, timeout_ms: u32, delay: &mut D) -> bool {
        self.inner.wait_ready(timeout_ms, delay)
    }

    /// Sequential read of `buf.len()` bytes starting at `address`, in `max_chunk` pieces.
    ///
    /// Returns [`Error::OutOfRange`] if the range does not lie inside 0x0000-0x1FFF.
    pub fn read(&mut self, address: u16, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        check_range(address, buf.len())?;
        let mut addr = address as u32;
        for chunk in buf.chunks_mut(self.max_chunk) {
            register::read_register(&mut self.inner.i2c, self.inner.addr, addr, REG_BYTES, chunk)?;
            addr += chunk.len() as u32;
        }
        Ok(())
    }

    /// Write `data` starting at `address`, split at 32-byte page boundaries and `max_chunk`.
    ///
    /// The write cycle is ACK-polled after every chunk. Returns
    /// [`Error::OutOfRange`] if the range does not lie inside 0x0000-0x1FFF
    /// and [`Error::Timeout`] if a write cycle does not complete.
    pub fn write<D: DelayNs>(&mut self, address: u16, data: &[u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        check_range(address, data.len())?;
        let mut addr = address as usize;
        let mut off = 0;
        while off < data.len() {
            let n = (PAGE - addr % PAGE).min(self.max_chunk).min(data.len() - off);
            register::write_register(&mut self.inner.i2c, self.inner.addr, addr as u32, REG_BYTES, &data[off..off + n])?;
            self.inner.commit(delay)?;
            addr += n;
            off += n;
        }
        Ok(())
    }

    /// Write within a single page (sent in `max_chunk` pieces), ACK-polled.
    ///
    /// Returns [`Error::PageBoundary`] if `data` would cross a page boundary or exceed 32
    /// bytes and [`Error::OutOfRange`] if it runs outside 0x0000-0x1FFF.
    pub fn write_page<D: DelayNs>(&mut self, address: u16, data: &[u8], delay: &mut D) -> Result<(), Error<I2C::Error>> {
        check_range(address, data.len())?;
        if data.len() > PAGE || (address as usize % PAGE) + data.len() > PAGE {
            return Err(Error::PageBoundary);
        }
        self.write(address, data, delay)
    }

    /// Write `value` over `length` bytes starting at `address`, page by page.
    ///
    /// Returns [`Error::OutOfRange`] if the range does not lie inside 0x0000-0x1FFF.
    pub fn fill<D: DelayNs>(&mut self, address: u16, length: usize, value: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        check_range(address, length)?;
        let block = [value; MAX_CHUNK_LIMIT];
        let mut addr = address as usize;
        let mut remaining = length;
        while remaining > 0 {
            let n = (PAGE - addr % PAGE).min(self.max_chunk).min(remaining);
            register::write_register(&mut self.inner.i2c, self.inner.addr, addr as u32, REG_BYTES, &block[..n])?;
            self.inner.commit(delay)?;
            addr += n;
            remaining -= n;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x50;

    fn poll() -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![0x00, 0x00])
    }

    #[test]
    fn minimal_byte_access() {
        let t = vec![
            I2cTransaction::write_read(ADDR, vec![0x12, 0x34], vec![0x42]),
            I2cTransaction::write(ADDR, vec![0x00, 0x10, 0x99]),
            poll(),
        ];
        let mut chip = At24c64Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(chip.read_byte(0x1234).unwrap(), 0x42);
        chip.write_byte(0x0010, 0x99, &mut NoopDelay).unwrap();
        chip.i2c.done();
    }

    #[test]
    fn ack_polling_retries_then_succeeds() {
        let t = vec![
            I2cTransaction::write(ADDR, vec![0x00, 0x01, 0x07]),
            poll().with_error(ErrorKind::Other),
            poll().with_error(ErrorKind::Other),
            poll(),
        ];
        let mut chip = At24c64Minimal::new(I2cMock::new(&t), ADDR);
        chip.write_byte(0x0001, 0x07, &mut NoopDelay).unwrap();
        chip.i2c.done();
    }

    #[test]
    fn write_cycle_timeout() {
        let mut t = vec![I2cTransaction::write(ADDR, vec![0x00, 0x01, 0x07])];
        for _ in 0..=WRITE_TIMEOUT_MS {
            t.push(poll().with_error(ErrorKind::Other));
        }
        let mut chip = At24c64Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(chip.write_byte(0x0001, 0x07, &mut NoopDelay), Err(Error::Timeout));
        chip.i2c.done();
    }

    #[test]
    fn page_split_write() {
        // 70 bytes from 0x0070 with 32-byte pages: 16 (to the boundary), 30, 2 (to the next boundary), 22.
        let data: Vec<u8> = (0..70).collect();
        let t = vec![
            I2cTransaction::write(ADDR, [vec![0x00, 0x70], data[..16].to_vec()].concat()),
            poll(),
            I2cTransaction::write(ADDR, [vec![0x00, 0x80], data[16..46].to_vec()].concat()),
            poll(),
            I2cTransaction::write(ADDR, [vec![0x00, 0x9E], data[46..48].to_vec()].concat()),
            poll(),
            I2cTransaction::write(ADDR, [vec![0x00, 0xA0], data[48..].to_vec()].concat()),
            poll(),
        ];
        let mut chip = At24c64Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(chip.size(), 8192);
        assert_eq!(chip.page_size(), 32);
        assert_eq!(chip.max_chunk(), 30);
        chip.write(0x0070, &data, &mut NoopDelay).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn read_and_fill() {
        let t = vec![
            I2cTransaction::write_read(ADDR, vec![0x01, 0x00], vec![9; 8]),
            I2cTransaction::write_read(ADDR, vec![0x01, 0x08], vec![9; 2]),
            // max_chunk = 8: fill 20 bytes at 0x0300 -> 8 + 8 + 4
            I2cTransaction::write(ADDR, [vec![0x03, 0x00], vec![0xAB; 8]].concat()),
            poll(),
            I2cTransaction::write(ADDR, [vec![0x03, 0x08], vec![0xAB; 8]].concat()),
            poll(),
            I2cTransaction::write(ADDR, [vec![0x03, 0x10], vec![0xAB; 4]].concat()),
            poll(),
        ];
        let mut chip = At24c64Full::new(I2cMock::new(&t), ADDR);
        chip.set_max_chunk(8);
        assert_eq!(chip.max_chunk(), 8);
        let mut buf = [0u8; 10];
        chip.read(0x0100, &mut buf).unwrap();
        assert_eq!(buf, [9u8; 10]);
        chip.fill(0x0300, 20, 0xAB, &mut NoopDelay).unwrap();
        chip.inner.i2c.done();
    }

    #[test]
    fn write_page_rules() {
        let page: Vec<u8> = (0..16).collect();
        let t = vec![
            I2cTransaction::write(ADDR, [vec![0x01, 0x80], page.clone()].concat()),
            poll(),
        ];
        let mut chip = At24c64Full::new(I2cMock::new(&t), ADDR);
        chip.write_page(0x0180, &page, &mut NoopDelay).unwrap();
        assert_eq!(chip.write_page(0x017C, &[0; 8], &mut NoopDelay), Err(Error::PageBoundary));
        assert_eq!(chip.write_page(0x0400, &[0; 33], &mut NoopDelay), Err(Error::PageBoundary));
        assert_eq!(chip.write_page(0x1FFF, &[0; 2], &mut NoopDelay), Err(Error::OutOfRange));
        chip.inner.i2c.done();
    }

    #[test]
    fn range_rules() {
        let t = vec![I2cTransaction::write(ADDR, vec![0x1F, 0xFE, 1, 2]), poll()];
        let mut chip = At24c64Full::new(I2cMock::new(&t), ADDR);
        chip.write(0x1FFE, &[1, 2], &mut NoopDelay).unwrap();
        assert_eq!(chip.write(0x1FFF, &[1, 2], &mut NoopDelay), Err(Error::OutOfRange));
        let mut buf = [0u8; 0x20];
        assert_eq!(chip.read(0x1FF0, &mut buf), Err(Error::OutOfRange));
        assert_eq!(chip.fill(0x1FFF, 2, 0, &mut NoopDelay), Err(Error::OutOfRange));
        chip.inner.i2c.done();
    }

    #[test]
    fn wait_ready_reports() {
        let t = vec![poll().with_error(ErrorKind::Other), poll()];
        let mut chip = At24c64Full::new(I2cMock::new(&t), ADDR);
        assert!(chip.wait_ready(5, &mut NoopDelay));
        chip.inner.i2c.done();
    }
}
