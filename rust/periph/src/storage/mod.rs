//! Common block-device contract for storage chips (SD, SD NAND, SPI NOR, SPI NAND).
//!
//! See `specs/feature_block_device.md`. Only the Full chip drivers implement
//! [`BlockDevice`]. Units are bytes and block indices; a block is the
//! read/program unit exposed to a filesystem. Periph ships no filesystem; the
//! trait is the seam where `embedded-sdmmc`, FatFs or LittleFS adapters attach.

#[cfg(feature = "std")]
pub mod ram;
#[cfg(feature = "std")]
pub use ram::RamBlockDevice;

/// Error of every [`BlockDevice`] operation; `E` is the transport error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockError<E> {
    /// Device absent or initialisation failed.
    NotReady,
    /// Block index/count beyond `block_count()`, or unaligned `erase()`.
    OutOfRange,
    /// Program to an un-erased block while auto-erase is off.
    NotErased,
    /// Card WP / status-register block protection.
    WriteProtected,
    /// Target erase unit is marked bad (NAND).
    BadBlock,
    /// Uncorrectable ECC error on read (NAND).
    Ecc,
    /// SD command/data CRC mismatch.
    Crc,
    /// Busy never cleared.
    Timeout,
    /// Transport failure.
    Io(E),
}

/// Geometry and capabilities of a [`BlockDevice`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BlockInfo {
    /// Read/program unit in bytes.
    pub block_size: u32,
    /// Number of addressable blocks.
    pub block_count: u32,
    /// Erase unit in bytes (a multiple of `block_size`).
    pub erase_size: u32,
    /// True if a block must be erased before it is programmed.
    pub needs_erase: bool,
    /// Write protected.
    pub read_only: bool,
    /// Device may contain bad erase units.
    pub bad_blocks: bool,
    /// Short device name (e.g. SD product name), NUL padded.
    pub name: [u8; 16],
}

/// Contract implemented by the Full driver of every storage chip.
pub trait BlockDevice {
    /// Transport error carried by [`BlockError::Io`].
    type Error;

    /// Logical block size in bytes (read/program unit).
    fn block_size(&self) -> u32;
    /// Number of addressable logical blocks.
    fn block_count(&self) -> u32;
    /// Erase unit in bytes; equals `block_size()` when `needs_erase()` is false.
    fn erase_size(&self) -> u32;
    /// True if blocks must be erased before they are programmed.
    fn needs_erase(&self) -> bool;

    /// Read `buf.len() / block_size()` consecutive blocks starting at `block`.
    fn read(&mut self, block: u32, buf: &mut [u8]) -> Result<(), BlockError<Self::Error>>;
    /// Program `data.len() / block_size()` consecutive blocks starting at `block`.
    fn write(&mut self, block: u32, data: &[u8]) -> Result<(), BlockError<Self::Error>>;
    /// Erase the erase units covering `[block, block + count)`. No-op on SD.
    fn erase(&mut self, block: u32, count: u32) -> Result<(), BlockError<Self::Error>>;
    /// Block until all pending program/erase work has finished.
    fn sync(&mut self) -> Result<(), BlockError<Self::Error>>;
    /// Geometry and capabilities.
    fn info(&self) -> BlockInfo;

    /// Optional (`info().bad_blocks`): whether erase unit `unit` is bad.
    fn is_bad(&mut self, _unit: u32) -> bool {
        false
    }
    /// Optional: persist a bad-block mark for erase unit `unit`.
    fn mark_bad(&mut self, _unit: u32) -> Result<(), BlockError<Self::Error>> {
        Ok(())
    }
}
