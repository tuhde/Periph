//! 24AA02UID — 2 Kbit I²C EEPROM with 32-bit unique serial number.
//!
//! Communicates over I²C (up to 400 kHz fast mode). The chip has no
//! configuration registers; it is ready for use immediately after
//! power-on. [`Eeprom24Aa02UidMinimal::new`] and
//! [`Eeprom24Aa02UidFull::new`] perform no I²C traffic — the chip is
//! detected on the first read.
//!
//! Memory layout:
//! - 0x00-0x7F — 128 bytes general-purpose user EEPROM (R/W)
//! - 0x80-0xF9 — reserved, read-only
//! - 0xFA      — manufacturer code (always 0x29, Microchip)
//! - 0xFB      — device code (always 0x41)
//! - 0xFC-0xFF — 32-bit unique serial number, MSB first

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const ADDR_UID_BASE: u8 = 0xFC;
const ADDR_MFR_CODE: u8 = 0xFA;
const ADDR_DEV_CODE: u8 = 0xFB;
const PAGE_SIZE: u8 = 8;
const WRITE_CYCLE_MS: u32 = 5;

/// 24AA02UID minimal driver — UID read, single-byte user EEPROM read/write.
///
/// Default configuration (no registers to configure):
/// - User EEPROM is read/written as raw bytes (no interpretation)
/// - `write_byte` waits the worst-case write-cycle time before returning
///   (the C++ Connection interface does not propagate ACK/NACK, so the
///   bus is not polled)
/// - All addresses 0x80-0xFF are write-protected; writes are silently
///   ignored by the chip.
pub struct Eeprom24Aa02UidMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Eeprom24Aa02UidMinimal<I2C> {
    /// Create a new `Eeprom24Aa02UidMinimal`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (fixed at `0x50`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Read the chip's factory-programmed 32-bit unique serial number.
    ///
    /// Returns 4 bytes (MSB first) from 0xFC-0xFF.
    pub fn read_uid(&mut self) -> Result<[u8; 4], I2C::Error> {
        let mut buf = [0u8; 4];
        self.i2c.write_read(self.addr, &[ADDR_UID_BASE], &mut buf)?;
        Ok(buf)
    }

    /// Read a single byte from user EEPROM at 0x00-0x7F.
    ///
    /// # Arguments
    /// * `address` — Memory address 0-127.
    pub fn read_byte(&mut self, address: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.addr, &[address], &mut buf)?;
        Ok(buf[0])
    }

    /// Write a single byte to user EEPROM at 0x00-0x7F and wait for completion.
    ///
    /// Sends the byte, then waits the worst-case 5 ms internal write
    /// cycle before returning. Writes to 0x80-0xFF are accepted by the
    /// device but silently ignored (write-protected region).
    ///
    /// # Arguments
    /// * `address` — Memory address 0-255.
    /// * `value`   — Byte value 0-255.
    /// * `delay`   — Delay provider for timing requirements.
    pub fn write_byte<D: DelayNs>(
        &mut self,
        address: u8,
        value: u8,
        delay: &mut D,
    ) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[address, value])?;
        delay.delay_ms(WRITE_CYCLE_MS);
        Ok(())
    }
}

/// 24AA02UID full driver — extends [`Eeprom24Aa02UidMinimal`] with multi-byte read/write.
///
/// Adds sequential read, raw page write (8-byte page), arbitrary-length
/// write that automatically crosses page boundaries, and accessors for
/// the manufacturer and device codes in the upper (read-only) block.
pub struct Eeprom24Aa02UidFull<I2C> {
    inner: Eeprom24Aa02UidMinimal<I2C>,
}

impl<I2C: I2c> Eeprom24Aa02UidFull<I2C> {
    /// Create a new `Eeprom24Aa02UidFull`.
    ///
    /// Same arguments as [`Eeprom24Aa02UidMinimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Eeprom24Aa02UidMinimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Trigger the inner driver to read the chip's factory-programmed 32-bit unique serial number.
    pub fn read_uid(&mut self) -> Result<[u8; 4], I2C::Error> {
        self.inner.read_uid()
    }

    /// Trigger the inner driver to read a single byte from user EEPROM.
    pub fn read_byte(&mut self, address: u8) -> Result<u8, I2C::Error> {
        self.inner.read_byte(address)
    }

    /// Trigger the inner driver to write a single byte to user EEPROM.
    pub fn write_byte<D: DelayNs>(
        &mut self,
        address: u8,
        value: u8,
        delay: &mut D,
    ) -> Result<(), I2C::Error> {
        self.inner.write_byte(address, value, delay)
    }

    /// Sequential read of @p length bytes starting at @p address.
    ///
    /// The internal address pointer auto-increments; reads may cross any
    /// boundary and wrap at the end of the 256-byte address space.
    pub fn read(&mut self, address: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        self.inner.i2c.write_read(self.inner.addr, &[address], buf)
    }

    /// Write up to 8 bytes within a single 8-byte page.
    ///
    /// The caller is responsible for ensuring all bytes lie within the
    /// same page. Bytes that would overflow the page boundary wrap to the
    /// start of the same page (FIFO overwrite) — use `write` to handle
    /// boundaries automatically.
    pub fn write_page<D: DelayNs>(
        &mut self,
        address: u8,
        data: &[u8],
        delay: &mut D,
    ) -> Result<(), I2C::Error> {
        if data.is_empty() { return Ok(()); }
        let mut buf = [0u8; 1 + PAGE_SIZE as usize];
        buf[0] = address;
        let n = data.len().min(PAGE_SIZE as usize);
        buf[1..=n].copy_from_slice(&data[..n]);
        self.inner.i2c.write(self.inner.addr, &buf[..=n])?;
        delay.delay_ms(WRITE_CYCLE_MS);
        Ok(())
    }

    /// Write an arbitrary-length buffer, splitting at 8-byte page boundaries.
    ///
    /// Automatically splits the write into page-aligned chunks and waits
    /// for the write cycle of each chunk before continuing.
    pub fn write<D: DelayNs>(
        &mut self,
        mut address: u8,
        data: &[u8],
        delay: &mut D,
    ) -> Result<(), I2C::Error> {
        let mut offset = 0usize;
        let mut remaining = data.len();
        while remaining > 0 {
            let page_offset = address & (PAGE_SIZE - 1);
            let chunk = (PAGE_SIZE - page_offset) as usize;
            let chunk = chunk.min(remaining);
            self.write_page(address, &data[offset..offset + chunk], delay)?;
            offset += chunk;
            address += chunk as u8;
            remaining -= chunk;
        }
        Ok(())
    }

    /// Read the manufacturer code at 0xFA.
    ///
    /// Returns the manufacturer code; expect 0x29 (Microchip).
    pub fn read_manufacturer_code(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_byte(ADDR_MFR_CODE)
    }

    /// Read the device code at 0xFB.
    ///
    /// Returns the device code; expect 0x41 (I²C 2-Kbit EEPROM).
    pub fn read_device_code(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_byte(ADDR_DEV_CODE)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x50;

    #[test]
    fn full_api() {
        let transactions = vec![
            // read_uid(): 0xFC-0xFF, MSB first.
            I2cTransaction::write_read(ADDR, vec![ADDR_UID_BASE], vec![0xAA, 0xBB, 0xCC, 0xDD]),
            // read_byte(0x10)
            I2cTransaction::write_read(ADDR, vec![0x10], vec![0x42]),
            // write_byte(0x10, 0x99)
            I2cTransaction::write(ADDR, vec![0x10, 0x99]),
            // read(0x05, 4 bytes)
            I2cTransaction::write_read(ADDR, vec![0x05], vec![1, 2, 3, 4]),
            // write_page(0x08, [10, 20, 30])
            I2cTransaction::write(ADDR, vec![0x08, 10, 20, 30]),
            // write(0x05, 10 bytes) spanning the 0x08 page boundary:
            // [0x05,0x06,0x07] (3 bytes, page 0) then [0x08..0x0E] (7 bytes, page 1).
            I2cTransaction::write(ADDR, vec![0x05, 100, 101, 102]),
            I2cTransaction::write(ADDR, vec![0x08, 103, 104, 105, 106, 107, 108, 109]),
            // read_manufacturer_code()
            I2cTransaction::write_read(ADDR, vec![ADDR_MFR_CODE], vec![0x29]),
            // read_device_code()
            I2cTransaction::write_read(ADDR, vec![ADDR_DEV_CODE], vec![0x41]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut delay = NoopDelay::new();

        let mut eeprom = Eeprom24Aa02UidFull::new(i2c, ADDR);

        assert_eq!(eeprom.read_uid().unwrap(), [0xAA, 0xBB, 0xCC, 0xDD]);
        assert_eq!(eeprom.read_byte(0x10).unwrap(), 0x42);

        eeprom.write_byte(0x10, 0x99, &mut delay).unwrap();

        let mut buf4 = [0u8; 4];
        eeprom.read(0x05, &mut buf4).unwrap();
        assert_eq!(buf4, [1, 2, 3, 4]);

        eeprom.write_page(0x08, &[10, 20, 30], &mut delay).unwrap();

        let data10: Vec<u8> = (100..110).collect();
        eeprom.write(0x05, &data10, &mut delay).unwrap();

        assert_eq!(eeprom.read_manufacturer_code().unwrap(), 0x29);
        assert_eq!(eeprom.read_device_code().unwrap(), 0x41);

        eeprom.inner.i2c.done();
    }
}
