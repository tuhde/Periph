//! 23X1024 — 1-Mbit SPI serial SRAM (Microchip 23LC1024 / 23A1024).
//!
//! Communicates over SPI (Mode 0, up to 20 MHz, MSB first). The chip is
//! volatile but has unlimited cycles and zero write time: a write is complete
//! when CS is released, with no polling. Every transaction is one instruction
//! byte, a 24-bit big-endian address and data, with CS held low for the whole
//! frame:
//!
//! ```text
//! READ   0x03 + A23..A0 -> data out
//! WRITE  0x02 + A23..A0 + data
//! RDMR   0x05           -> MODE register
//! WRMR   0x01 + MODE
//! RSTIO  0xFF           (return to SPI mode from SDI/SQI)
//! ```
//!
//! The instruction is not a register address, so the driver takes the bare
//! `SpiDevice` and builds each frame itself.
//!
//! ## Default configuration
//!
//! - Sequential mode (`MODE` = 0x40), written and read back at construction;
//!   a mismatch (floating/missing MISO, wrong SPI mode, chip stuck in SDI/SQI
//!   mode) panics.
//! - Full 128 KiB addressable; SDI/SQI bus modes are never used.
//!
//! ## Constants
//!
//! Operating modes: [`SRAM23X1024_MODE_BYTE`], [`SRAM23X1024_MODE_PAGE`],
//! [`SRAM23X1024_MODE_SEQUENTIAL`]

use embedded_hal::spi::{Operation, SpiDevice};

const CMD_READ:  u8 = 0x03;
const CMD_WRITE: u8 = 0x02;
const CMD_RDMR:  u8 = 0x05;
const CMD_WRMR:  u8 = 0x01;
const CMD_RSTIO: u8 = 0xFF;
const MODE_MASK: u8 = 0xC0;

const SIZE: u32 = 131_072;
const PAGE_SIZE: u32 = 32;
const MAX_CHUNK_LIMIT: usize = 256;

/// Operating mode: Byte (each READ/WRITE transfers exactly one data byte).
pub const SRAM23X1024_MODE_BYTE: u8 = 0x00;
/// Operating mode: Page (auto-increment wraps within the 32-byte page).
pub const SRAM23X1024_MODE_PAGE: u8 = 0x80;
/// Operating mode: Sequential (auto-increment across the whole array; power-on default).
pub const SRAM23X1024_MODE_SEQUENTIAL: u8 = 0x40;

fn header(command: u8, address: u32) -> [u8; 4] {
    [command, (address >> 16) as u8, (address >> 8) as u8, address as u8]
}

fn read_mode<SPI: SpiDevice>(spi: &mut SPI) -> Result<u8, SPI::Error> {
    let mut out = [0u8; 1];
    spi.transaction(&mut [Operation::Write(&[CMD_RDMR]), Operation::Read(&mut out)])?;
    Ok(out[0])
}

fn write_mode<SPI: SpiDevice>(spi: &mut SPI, mode: u8) -> Result<(), SPI::Error> {
    spi.transaction(&mut [Operation::Write(&[CMD_WRMR, mode])])
}

fn read_raw<SPI: SpiDevice>(spi: &mut SPI, address: u32, buf: &mut [u8]) -> Result<(), SPI::Error> {
    let hdr = header(CMD_READ, address);
    spi.transaction(&mut [Operation::Write(&hdr), Operation::Read(buf)])
}

fn write_raw<SPI: SpiDevice>(spi: &mut SPI, address: u32, data: &[u8]) -> Result<(), SPI::Error> {
    let hdr = header(CMD_WRITE, address);
    spi.transaction(&mut [Operation::Write(&hdr), Operation::Write(data)])
}

/// 23X1024 minimal driver — single-byte read/write.
///
/// Sets Sequential mode and verifies it with a MODE register round-trip at
/// construction.
pub struct Sram23x1024Minimal<SPI: SpiDevice> {
    spi: SPI,
}

impl<SPI: SpiDevice> Sram23x1024Minimal<SPI> {
    /// Create a new `Sram23x1024Minimal` and run the chip's initialization sequence.
    ///
    /// # Arguments
    /// * `spi` — Configured `SpiDevice` (Mode 0, MSB first) bound to the chip's CS pin.
    ///
    /// # Panics
    /// If the MODE register does not read back as 0x40.
    pub fn new(spi: SPI) -> Result<Self, SPI::Error> {
        let mut s = Self { spi };
        s.init()?;
        Ok(s)
    }

    /// Select Sequential mode and verify it.
    pub fn init(&mut self) -> Result<(), SPI::Error> {
        write_mode(&mut self.spi, SRAM23X1024_MODE_SEQUENTIAL)?;
        let mode = read_mode(&mut self.spi)?;
        if mode != SRAM23X1024_MODE_SEQUENTIAL {
            panic!("23X1024 MODE register: expected 0x40, got 0x{:02X} (check wiring and SPI Mode 0)", mode);
        }
        Ok(())
    }

    /// Read one byte (READ, 0x03). Panics if `address` is not 0–131071.
    pub fn read_byte(&mut self, address: u32) -> Result<u8, SPI::Error> {
        assert!(address < SIZE, "address out of range");
        let mut out = [0u8; 1];
        read_raw(&mut self.spi, address, &mut out)?;
        Ok(out[0])
    }

    /// Write one byte (WRITE, 0x02); complete when CS is released. Panics if `address` is not 0–131071.
    pub fn write_byte(&mut self, address: u32, value: u8) -> Result<(), SPI::Error> {
        assert!(address < SIZE, "address out of range");
        write_raw(&mut self.spi, address, &[value])
    }
}

/// 23X1024 full driver — extends [`Sram23x1024Minimal`] with block access, operating modes and I/O reset.
///
/// `read`/`write`/`fill` have linear-address semantics in every mode: the
/// driver splits transfers into transactions of at most `max_chunk` data
/// bytes and, in Page/Byte mode, at the 32-byte page / single-byte boundaries
/// the hardware would otherwise wrap or truncate at. Ranges that would leave
/// the array panic instead of silently wrapping around.
pub struct Sram23x1024Full<SPI: SpiDevice> {
    spi: SPI,
    mode: u8,
    max_chunk: usize,
}

impl<SPI: SpiDevice> Sram23x1024Full<SPI> {
    /// Create a new `Sram23x1024Full` and run the chip's initialization sequence.
    ///
    /// # Panics
    /// If the MODE register does not read back as 0x40.
    pub fn new(spi: SPI) -> Result<Self, SPI::Error> {
        let mut s = Self { spi, mode: SRAM23X1024_MODE_SEQUENTIAL, max_chunk: MAX_CHUNK_LIMIT };
        write_mode(&mut s.spi, SRAM23X1024_MODE_SEQUENTIAL)?;
        let mode = read_mode(&mut s.spi)?;
        if mode != SRAM23X1024_MODE_SEQUENTIAL {
            panic!("23X1024 MODE register: expected 0x40, got 0x{:02X} (check wiring and SPI Mode 0)", mode);
        }
        Ok(s)
    }

    /// Read one byte (READ, 0x03). Panics if `address` is not 0–131071.
    pub fn read_byte(&mut self, address: u32) -> Result<u8, SPI::Error> {
        assert!(address < SIZE, "address out of range");
        let mut out = [0u8; 1];
        read_raw(&mut self.spi, address, &mut out)?;
        Ok(out[0])
    }

    /// Write one byte (WRITE, 0x02); complete when CS is released. Panics if `address` is not 0–131071.
    pub fn write_byte(&mut self, address: u32, value: u8) -> Result<(), SPI::Error> {
        assert!(address < SIZE, "address out of range");
        write_raw(&mut self.spi, address, &[value])
    }

    fn chunk_len(&self, address: u32, remaining: usize) -> usize {
        let mut n = remaining.min(self.max_chunk);
        if self.mode == SRAM23X1024_MODE_BYTE {
            return 1;
        }
        if self.mode == SRAM23X1024_MODE_PAGE {
            n = n.min((PAGE_SIZE - (address % PAGE_SIZE)) as usize);
        }
        n
    }

    fn check_range(address: u32, length: usize) {
        assert!(
            (address as usize).checked_add(length).map_or(false, |end| end <= SIZE as usize),
            "range leaves the 128 KiB array"
        );
    }

    /// Block read filling `buf` starting at `address`; `address + buf.len()` must be <= 131072.
    pub fn read(&mut self, address: u32, buf: &mut [u8]) -> Result<(), SPI::Error> {
        Self::check_range(address, buf.len());
        let mut address = address;
        let mut pos = 0;
        while pos < buf.len() {
            let n = self.chunk_len(address, buf.len() - pos);
            read_raw(&mut self.spi, address, &mut buf[pos..pos + n])?;
            address += n as u32;
            pos += n;
        }
        Ok(())
    }

    /// Block write of `data` starting at `address` (same range rule as `read`).
    pub fn write(&mut self, address: u32, data: &[u8]) -> Result<(), SPI::Error> {
        Self::check_range(address, data.len());
        let mut address = address;
        let mut pos = 0;
        while pos < data.len() {
            let n = self.chunk_len(address, data.len() - pos);
            write_raw(&mut self.spi, address, &data[pos..pos + n])?;
            address += n as u32;
            pos += n;
        }
        Ok(())
    }

    /// Write `value` over `length` bytes starting at `address` (chunked).
    pub fn fill(&mut self, address: u32, length: usize, value: u8) -> Result<(), SPI::Error> {
        Self::check_range(address, length);
        let block = [value; PAGE_SIZE as usize];
        let mut address = address;
        let mut remaining = length;
        while remaining > 0 {
            let n = self.chunk_len(address, remaining).min(block.len());
            write_raw(&mut self.spi, address, &block[..n])?;
            address += n as u32;
            remaining -= n;
        }
        Ok(())
    }

    /// Array size in bytes (131072).
    pub fn size(&self) -> u32 {
        SIZE
    }

    /// Select the operating mode (WRMR) and verify it with RDMR.
    ///
    /// # Arguments
    /// * `mode` — [`SRAM23X1024_MODE_BYTE`], [`SRAM23X1024_MODE_PAGE`] or [`SRAM23X1024_MODE_SEQUENTIAL`]; any other value panics.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), SPI::Error> {
        assert!(
            mode == SRAM23X1024_MODE_BYTE || mode == SRAM23X1024_MODE_PAGE || mode == SRAM23X1024_MODE_SEQUENTIAL,
            "mode must be MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL"
        );
        write_mode(&mut self.spi, mode)?;
        let readback = read_mode(&mut self.spi)?;
        assert_eq!(readback, mode, "23X1024 MODE register read-back mismatch");
        self.mode = mode;
        Ok(())
    }

    /// Read the operating mode (RDMR bits 7:6) as one of the `SRAM23X1024_MODE_*` constants (0xC0 if reserved).
    pub fn get_mode(&mut self) -> Result<u8, SPI::Error> {
        Ok(read_mode(&mut self.spi)? & MODE_MASK)
    }

    /// Send RSTIO (0xFF) to return the chip to SPI mode after a stray EDIO/EQIO.
    ///
    /// Resynchronises the cached mode with the chip (re-reads RDMR).
    pub fn reset_io(&mut self) -> Result<(), SPI::Error> {
        self.spi.transaction(&mut [Operation::Write(&[CMD_RSTIO])])?;
        self.mode = self.get_mode()?;
        Ok(())
    }

    /// Maximum data bytes per bus transaction (1–256, default 256).
    pub fn max_chunk(&self) -> usize {
        self.max_chunk
    }

    /// Set the maximum data bytes per bus transaction; panics unless 1–256.
    pub fn set_max_chunk(&mut self, n: usize) {
        assert!((1..=MAX_CHUNK_LIMIT).contains(&n), "max_chunk must be 1-256");
        self.max_chunk = n;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    fn wmr(mode: u8) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(vec![0x01, mode]),
            SpiTransaction::transaction_end(),
        ]
    }

    fn rdmr(mode: u8) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(vec![0x05]),
            SpiTransaction::read_vec(vec![mode]),
            SpiTransaction::transaction_end(),
        ]
    }

    fn wr(addr: u32, data: &[u8]) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(vec![0x02, (addr >> 16) as u8, (addr >> 8) as u8, addr as u8]),
            SpiTransaction::write_vec(data.to_vec()),
            SpiTransaction::transaction_end(),
        ]
    }

    fn rd(addr: u32, data: &[u8]) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(vec![0x03, (addr >> 16) as u8, (addr >> 8) as u8, addr as u8]),
            SpiTransaction::read_vec(data.to_vec()),
            SpiTransaction::transaction_end(),
        ]
    }

    fn init_seq() -> Vec<SpiTransaction<u8>> {
        let mut t = wmr(0x40);
        t.extend(rdmr(0x40));
        t
    }

    #[test]
    fn init_writes_sequential_and_verifies() {
        let mut spi = SpiMock::new(&init_seq());
        let chip = Sram23x1024Minimal::new(spi.clone()).unwrap();
        drop(chip);
        spi.done();
    }

    #[test]
    #[should_panic(expected = "MODE register")]
    fn init_panics_on_mode_mismatch() {
        let mut t = wmr(0x40);
        t.extend(rdmr(0x00));
        let spi = SpiMock::new(&t);
        let _ = Sram23x1024Minimal::new(spi);
    }

    #[test]
    fn byte_write_and_read_frames() {
        let mut t = init_seq();
        t.extend(wr(0x1ABCD, &[0x5A]));
        t.extend(rd(0x1ABCD, &[0x5A]));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Minimal::new(spi.clone()).unwrap();
        chip.write_byte(0x1ABCD, 0x5A).unwrap();
        assert_eq!(chip.read_byte(0x1ABCD).unwrap(), 0x5A);
        spi.done();
    }

    #[test]
    #[should_panic(expected = "address out of range")]
    fn read_byte_out_of_range_panics() {
        let spi = SpiMock::new(&init_seq());
        let mut chip = Sram23x1024Minimal::new(spi).unwrap();
        let _ = chip.read_byte(0x20000);
    }

    #[test]
    fn full_block_write_is_chunked_at_256() {
        let data: Vec<u8> = (0..300u32).map(|i| i as u8).collect();
        let mut t = init_seq();
        t.extend(wr(0x100, &data[..256]));
        t.extend(wr(0x200, &data[256..]));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        chip.write(0x100, &data).unwrap();
        spi.done();
    }

    #[test]
    fn full_block_read_is_chunked_with_custom_max_chunk() {
        let mut t = init_seq();
        t.extend(rd(0x00, &[1, 2, 3, 4]));
        t.extend(rd(0x04, &[5, 6, 7, 8]));
        t.extend(rd(0x08, &[9, 10]));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        chip.set_max_chunk(4);
        assert_eq!(chip.max_chunk(), 4);
        let mut buf = [0u8; 10];
        chip.read(0, &mut buf).unwrap();
        assert_eq!(buf, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
        spi.done();
    }

    #[test]
    fn page_mode_splits_at_page_boundaries() {
        let data: Vec<u8> = (0..64u32).map(|i| 0xC0 + i as u8).collect();
        let mut t = init_seq();
        t.extend(wmr(0x80));
        t.extend(rdmr(0x80));
        t.extend(wr(0x4010, &data[..16]));
        t.extend(wr(0x4020, &data[16..48]));
        t.extend(wr(0x4040, &data[48..]));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        chip.set_mode(SRAM23X1024_MODE_PAGE).unwrap();
        chip.write(0x4010, &data).unwrap();
        spi.done();
    }

    #[test]
    fn byte_mode_sends_one_byte_per_frame() {
        let mut t = init_seq();
        t.extend(wmr(0x00));
        t.extend(rdmr(0x00));
        for i in 0..3u32 {
            t.extend(wr(0x500 + i, &[i as u8 + 1]));
        }
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        chip.set_mode(SRAM23X1024_MODE_BYTE).unwrap();
        chip.write(0x500, &[1, 2, 3]).unwrap();
        spi.done();
    }

    #[test]
    fn fill_writes_value_in_32_byte_blocks() {
        let mut t = init_seq();
        t.extend(wr(0x2000, &[0xA5; 32]));
        t.extend(wr(0x2020, &[0xA5; 32]));
        t.extend(wr(0x2040, &[0xA5; 4]));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        chip.fill(0x2000, 68, 0xA5).unwrap();
        spi.done();
    }

    #[test]
    #[should_panic(expected = "range leaves")]
    fn range_past_end_panics() {
        let spi = SpiMock::new(&init_seq());
        let mut chip = Sram23x1024Full::new(spi).unwrap();
        let _ = chip.write(0x1FFFF, &[1, 2]);
    }

    #[test]
    #[should_panic(expected = "mode must be")]
    fn set_mode_rejects_reserved() {
        let spi = SpiMock::new(&init_seq());
        let mut chip = Sram23x1024Full::new(spi).unwrap();
        let _ = chip.set_mode(0xC0);
    }

    #[test]
    fn get_mode_masks_reserved_bits() {
        let mut t = init_seq();
        t.extend(rdmr(0x4F));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        assert_eq!(chip.get_mode().unwrap(), SRAM23X1024_MODE_SEQUENTIAL);
        spi.done();
    }

    #[test]
    fn reset_io_sends_ff_and_resyncs_mode() {
        let mut t = init_seq();
        t.extend(vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(vec![0xFF]),
            SpiTransaction::transaction_end(),
        ]);
        t.extend(rdmr(0x40));
        let mut spi = SpiMock::new(&t);
        let mut chip = Sram23x1024Full::new(spi.clone()).unwrap();
        chip.reset_io().unwrap();
        spi.done();
    }
}
