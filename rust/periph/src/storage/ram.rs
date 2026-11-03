//! RAM-backed [`BlockDevice`] mock for tests (needs the `std` feature).

use super::{BlockDevice, BlockError, BlockInfo};
use core::convert::Infallible;

/// RAM-backed block device.
///
/// With `erase_blocks > 1` it models NOR-like semantics: erased blocks read
/// `0xFF` and programming an un-erased block while `auto_erase` is false
/// returns [`BlockError::NotErased`].
pub struct RamBlockDevice {
    bs: u32,
    bc: u32,
    eb: u32,
    auto: bool,
    mem: std::vec::Vec<u8>,
    erased: std::vec::Vec<bool>,
}

impl RamBlockDevice {
    /// Create a device of `block_count` blocks of `block_size` bytes.
    pub fn new(block_size: u32, block_count: u32, erase_blocks: u32, auto_erase: bool) -> Self {
        let eb = erase_blocks.max(1);
        let fill = if eb > 1 { 0xFF } else { 0x00 };
        Self {
            bs: block_size,
            bc: block_count,
            eb,
            auto: auto_erase,
            mem: std::vec![fill; (block_size * block_count) as usize],
            erased: std::vec![true; block_count as usize],
        }
    }
}

impl BlockDevice for RamBlockDevice {
    type Error = Infallible;

    fn block_size(&self) -> u32 { self.bs }
    fn block_count(&self) -> u32 { self.bc }
    fn erase_size(&self) -> u32 { self.bs * self.eb }
    fn needs_erase(&self) -> bool { self.eb > 1 }

    fn read(&mut self, block: u32, buf: &mut [u8]) -> Result<(), BlockError<Infallible>> {
        let count = buf.len() as u32 / self.bs;
        if block + count > self.bc {
            return Err(BlockError::OutOfRange);
        }
        let start = (block * self.bs) as usize;
        buf.copy_from_slice(&self.mem[start..start + buf.len()]);
        Ok(())
    }

    fn write(&mut self, block: u32, data: &[u8]) -> Result<(), BlockError<Infallible>> {
        let count = data.len() as u32 / self.bs;
        if block + count > self.bc {
            return Err(BlockError::OutOfRange);
        }
        if self.needs_erase() {
            for i in 0..count {
                let b = block + i;
                if !self.erased[b as usize] {
                    if !self.auto {
                        return Err(BlockError::NotErased);
                    }
                    self.erase(b - b % self.eb, self.eb)?;
                }
            }
        }
        let start = (block * self.bs) as usize;
        self.mem[start..start + data.len()].copy_from_slice(data);
        for i in 0..count {
            self.erased[(block + i) as usize] = false;
        }
        Ok(())
    }

    fn erase(&mut self, block: u32, count: u32) -> Result<(), BlockError<Infallible>> {
        if !self.needs_erase() {
            return Ok(());
        }
        if block % self.eb != 0 || count % self.eb != 0 || block + count > self.bc {
            return Err(BlockError::OutOfRange);
        }
        let start = (block * self.bs) as usize;
        let end = ((block + count) * self.bs) as usize;
        for b in &mut self.mem[start..end] {
            *b = 0xFF;
        }
        for i in 0..count {
            self.erased[(block + i) as usize] = true;
        }
        Ok(())
    }

    fn sync(&mut self) -> Result<(), BlockError<Infallible>> { Ok(()) }

    fn info(&self) -> BlockInfo {
        let mut name = [0u8; 16];
        name[..3].copy_from_slice(b"RAM");
        BlockInfo {
            block_size: self.bs,
            block_count: self.bc,
            erase_size: self.erase_size(),
            needs_erase: self.needs_erase(),
            read_only: false,
            bad_blocks: false,
            name,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_roundtrip_and_range() {
        let mut d = RamBlockDevice::new(512, 16, 1, false);
        let w = [0xA5u8; 512];
        d.write(3, &w).unwrap();
        let mut r = [0u8; 512];
        d.read(3, &mut r).unwrap();
        assert_eq!(w, r);
        assert_eq!(d.read(16, &mut r), Err(BlockError::OutOfRange));
    }

    #[test]
    fn nor_semantics() {
        let mut d = RamBlockDevice::new(512, 16, 8, false);
        let mut r = [0u8; 512];
        d.read(0, &mut r).unwrap();
        assert!(r.iter().all(|&b| b == 0xFF));
        d.write(0, &[0u8; 512]).unwrap();
        assert_eq!(d.write(0, &[1u8; 512]), Err(BlockError::NotErased));
        d.erase(0, 8).unwrap();
        d.write(0, &[2u8; 512]).unwrap();
        assert_eq!(d.erase(1, 8), Err(BlockError::OutOfRange));
        let mut a = RamBlockDevice::new(512, 16, 8, true);
        a.write(2, &[1u8; 512]).unwrap();
        a.write(2, &[2u8; 512]).unwrap();
        a.read(2, &mut r).unwrap();
        assert!(r.iter().all(|&b| b == 2));
    }
}
