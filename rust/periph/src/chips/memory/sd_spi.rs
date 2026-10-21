//! SD / SDHC / SDXC / microSD card in SPI mode (`specs/memory/sd_spi.md`).
//!
//! [`SdSpiMinimal`] runs the SPI-mode init sequence in `new()` and offers
//! single 512-byte block reads and writes. [`SdSpiFull`] adds multi-block
//! transfers, erase, CID/CSD/OCR access and CRC checking, and implements the
//! common [`BlockDevice`] contract (`specs/feature_block_device.md`).
//!
//! The driver is generic over [`SpiExtensions`] (`idle_clocks`, `set_speed`,
//! `select`/`deselect`, `read` with MOSI high) rather than `SpiDevice`, because
//! SD init clocks with CS high and a command, its response polling and the data
//! block form one CS-low transaction. Build one with [`SpiManualCs`] from an
//! `embedded-hal` `SpiBus` and a GPIO chip select. Cards are 3.3 V only.
//!
//! ## Default configuration
//!
//! - Init at 400 kHz, then the speed from [`SdSpiOptions::speed_hz`] (12 MHz).
//! - [`SdSpiMinimal`]: CRC16 off. [`SdSpiFull`]: CRC16 on (`CMD59`).
//! - Timeouts use `SdSpiOptions::now_ms` when given, else bounded poll counts.
//!
//! [`SpiManualCs`]: crate::connection::spi::SpiManualCs

use crate::connection::spi::SpiExtensions;
use crate::storage::{BlockDevice, BlockError, BlockInfo};

const INIT_TIMEOUT_MS: u32 = 1000;
const TOKEN_TIMEOUT_MS: u32 = 200;
const BUSY_TIMEOUT_MS: u32 = 500;
const ERASE_TIMEOUT_MS: u32 = 30_000;

/// Block size of an SD card in SPI mode.
pub const SD_BLOCK_SIZE: usize = 512;

/// Card generation detected during init.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SdCardType {
    /// No card initialised.
    None,
    /// SD version 1 (byte addressed).
    SdV1,
    /// SD version 2, standard capacity (byte addressed).
    SdV2,
    /// SDHC / SDXC (block addressed).
    Sdhc,
}

/// Driver options.
#[derive(Clone, Copy)]
pub struct SdSpiOptions {
    /// Data-phase clock after init (init always runs at 400 kHz).
    pub speed_hz: u32,
    /// Millisecond clock for timeouts; `None` uses bounded poll counts.
    pub now_ms: Option<fn() -> u32>,
    /// Enable `CMD59` and send/verify CRC16 on data blocks.
    pub crc: bool,
}

impl Default for SdSpiOptions {
    fn default() -> Self {
        Self { speed_hz: 12_000_000, now_ms: None, crc: false }
    }
}

type Res<T, S> = Result<T, BlockError<<S as SpiExtensions>::Error>>;

const CRC16_T: [u16; 16] = [
    0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
    0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
];

/// CRC7 (polynomial x^7 + x^3 + 1) of `data`, not shifted.
pub fn crc7(data: &[u8]) -> u8 {
    let mut crc: u8 = 0;
    for &byte in data {
        let mut b = byte;
        for _ in 0..8 {
            crc <<= 1;
            if (b ^ crc) & 0x80 != 0 {
                crc ^= 0x09;
            }
            b <<= 1;
        }
    }
    crc & 0x7F
}

/// CRC16-CCITT (polynomial 0x1021, initial value 0) of `data`.
pub fn crc16(data: &[u8]) -> u16 {
    let mut crc: u16 = 0;
    for &b in data {
        crc ^= (b as u16) << 8;
        crc = (crc << 4) ^ CRC16_T[(crc >> 12) as usize];
        crc = (crc << 4) ^ CRC16_T[(crc >> 12) as usize];
    }
    crc
}

struct Deadline {
    start: u32,
    ms: u32,
    polls: u32,
}

/// SD card in SPI mode — minimal driver (single-block read/write).
pub struct SdSpiMinimal<S: SpiExtensions> {
    spi: S,
    opt: SdSpiOptions,
    card: SdCardType,
    block_addressing: bool,
    crc_on: bool,
    block_count: u32,
    csd: [u8; 16],
}

impl<S: SpiExtensions> SdSpiMinimal<S> {
    /// Create the driver and run the SPI-mode init sequence.
    ///
    /// # Errors
    /// [`BlockError::NotReady`] if no (supported) card answers, [`BlockError::Timeout`]
    /// if the card never leaves idle, [`BlockError::Io`] on a bus error.
    pub fn new(spi: S, opt: SdSpiOptions) -> Res<Self, S> {
        let mut s = Self {
            spi,
            opt,
            card: SdCardType::None,
            block_addressing: false,
            crc_on: false,
            block_count: 0,
            csd: [0; 16],
        };
        s.begin()?;
        Ok(s)
    }

    /// Release the SPI object.
    pub fn release(self) -> S {
        self.spi
    }

    // ---- bus primitives ----------------------------------------------------

    fn io<T>(r: Result<T, S::Error>) -> Res<T, S> {
        r.map_err(BlockError::Io)
    }

    fn deadline(&self, ms: u32) -> Deadline {
        Deadline { start: self.opt.now_ms.map_or(0, |f| f()), ms, polls: 0 }
    }

    fn expired(&self, d: &mut Deadline) -> bool {
        match self.opt.now_ms {
            Some(f) => f().wrapping_sub(d.start) >= d.ms,
            None => {
                d.polls += 1;
                d.polls > d.ms.saturating_mul(8)
            }
        }
    }

    fn rx(&mut self) -> Res<u8, S> {
        let mut b = [0xFFu8];
        Self::io(self.spi.read(&mut b))?;
        Ok(b[0])
    }

    fn wait_ready(&mut self, ms: u32) -> Res<(), S> {
        let mut d = self.deadline(ms);
        loop {
            if self.rx()? == 0xFF {
                return Ok(());
            }
            if self.expired(&mut d) {
                return Err(BlockError::Timeout);
            }
        }
    }

    fn release_card(&mut self) -> Res<(), S> {
        Self::io(self.spi.deselect())?;
        Self::io(self.spi.idle_clocks(8)) // let the card free MISO
    }

    /// Send a command with CS already selected; returns R1 (0xFF if no response).
    fn command(&mut self, cmd: u8, arg: u32) -> Res<u8, S> {
        if cmd != 12 && cmd != 0 {
            self.wait_ready(BUSY_TIMEOUT_MS)?;
        }
        let mut frame = [0u8; 6];
        frame[0] = 0x40 | cmd;
        frame[1..5].copy_from_slice(&arg.to_be_bytes());
        frame[5] = (crc7(&frame[..5]) << 1) | 1;
        Self::io(self.spi.write(&frame))?;
        if cmd == 12 {
            self.rx()?; // discard the stuff byte that follows CMD12
        }
        for _ in 0..10 {
            let r = self.rx()?; // Ncr <= 8 bytes
            if r & 0x80 == 0 {
                return Ok(r);
            }
        }
        Ok(0xFF)
    }

    fn app_command(&mut self, cmd: u8, arg: u32) -> Res<u8, S> {
        let r = self.command(55, 0)?;
        if r > 1 {
            return Ok(r);
        }
        self.command(cmd, arg)
    }

    fn read_data(&mut self, buf: &mut [u8]) -> Res<(), S> {
        let mut d = self.deadline(TOKEN_TIMEOUT_MS);
        let token = loop {
            let t = self.rx()?;
            if t != 0xFF {
                break t;
            }
            if self.expired(&mut d) {
                return Err(BlockError::Timeout);
            }
        };
        if token != 0xFE {
            return Err(BlockError::NotReady);
        }
        Self::io(self.spi.read(buf))?;
        let mut crc = [0u8; 2];
        Self::io(self.spi.read(&mut crc))?;
        if self.crc_on && u16::from_be_bytes(crc) != crc16(buf) {
            return Err(BlockError::Crc);
        }
        Ok(())
    }

    fn write_data(&mut self, token: u8, data: &[u8]) -> Res<(), S> {
        self.wait_ready(BUSY_TIMEOUT_MS)?;
        Self::io(self.spi.write(&[token]))?;
        if token == 0xFD {
            return Ok(()); // stop-tran token has no payload
        }
        Self::io(self.spi.write(data))?;
        let crc = if self.crc_on { crc16(data).to_be_bytes() } else { [0xFF, 0xFF] };
        Self::io(self.spi.write(&crc))?;
        match self.rx()? & 0x1F {
            0x05 => Ok(()),
            0x0B => Err(BlockError::Crc),
            _ => Err(BlockError::NotReady),
        }
    }

    fn read_register(&mut self, cmd: u8, out: &mut [u8; 16]) -> Res<(), S> {
        Self::io(self.spi.select())?;
        let r = match self.command(cmd, 0) {
            Ok(0) => self.read_data(out),
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.release_card();
        r.and(rel)
    }

    fn address(&self, block: u32) -> u32 {
        if self.block_addressing { block } else { block * SD_BLOCK_SIZE as u32 }
    }

    // ---- init --------------------------------------------------------------

    /// Run the SPI-mode init sequence (also usable to re-init a swapped card).
    pub fn begin(&mut self) -> Res<(), S> {
        self.card = SdCardType::None;
        self.block_count = 0;
        self.crc_on = false;
        self.spi.set_speed(400_000);
        Self::io(self.spi.deselect())?;
        Self::io(self.spi.idle_clocks(80))?;

        // CMD0 with CS low enters SPI mode; a fresh card needs a few tries.
        let mut idle = false;
        for _ in 0..10 {
            Self::io(self.spi.select())?;
            let r = self.command(0, 0);
            let rel = self.release_card();
            idle = r? == 0x01;
            rel?;
            if idle {
                break;
            }
        }
        if !idle {
            return Err(BlockError::NotReady);
        }

        Self::io(self.spi.select())?;
        let r = self.init_after_idle();
        let rel = self.release_card();
        r?;
        rel?;

        self.spi.set_speed(self.opt.speed_hz);
        let mut csd = [0u8; 16];
        self.read_register(9, &mut csd)?;
        self.csd = csd;
        self.block_count = csd_blocks(&csd);
        Ok(())
    }

    fn init_after_idle(&mut self) -> Res<(), S> {
        let mut v2 = false;
        let r = self.command(8, 0x1AA)?;
        if r == 0x01 {
            let mut r7 = [0u8; 4];
            Self::io(self.spi.read(&mut r7))?;
            if r7[2] & 0x0F != 0x01 || r7[3] != 0xAA {
                return Err(BlockError::NotReady);
            }
            v2 = true;
        } else if r & 0x04 == 0 {
            return Err(BlockError::NotReady); // anything but "illegal command" (v1 card)
        }

        let mut d = self.deadline(INIT_TIMEOUT_MS);
        loop {
            let r = self.app_command(41, if v2 { 0x4000_0000 } else { 0 })?;
            if r == 0x00 {
                break;
            }
            if self.expired(&mut d) {
                return Err(BlockError::Timeout);
            }
        }

        self.card = if v2 { SdCardType::SdV2 } else { SdCardType::SdV1 };
        self.block_addressing = false;
        if v2 && self.command(58, 0)? == 0x00 {
            let mut ocr = [0u8; 4];
            Self::io(self.spi.read(&mut ocr))?;
            if ocr[0] & 0x40 != 0 {
                self.block_addressing = true;
                self.card = SdCardType::Sdhc;
            }
        }
        if !self.block_addressing {
            self.command(16, SD_BLOCK_SIZE as u32)?;
        }
        if self.opt.crc && self.command(59, 1)? == 0x00 {
            self.crc_on = true;
        }
        Ok(())
    }

    // ---- minimal API ---------------------------------------------------------

    /// Capacity in 512-byte blocks (from the CSD).
    pub fn block_count(&self) -> u32 {
        self.block_count
    }

    /// Alias of [`block_count`](Self::block_count) (FatFs terminology).
    pub fn sector_count(&self) -> u32 {
        self.block_count
    }

    /// Card generation.
    pub fn card_type(&self) -> SdCardType {
        self.card
    }

    /// Read one 512-byte block (`CMD17`).
    pub fn read_block(&mut self, block: u32, buf: &mut [u8; SD_BLOCK_SIZE]) -> Res<(), S> {
        if block >= self.block_count {
            return Err(BlockError::OutOfRange);
        }
        Self::io(self.spi.select())?;
        let addr = self.address(block);
        let r = match self.command(17, addr) {
            Ok(0) => self.read_data(buf),
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.release_card();
        r.and(rel)
    }

    /// Write one 512-byte block (`CMD24`).
    pub fn write_block(&mut self, block: u32, data: &[u8; SD_BLOCK_SIZE]) -> Res<(), S> {
        if block >= self.block_count {
            return Err(BlockError::OutOfRange);
        }
        Self::io(self.spi.select())?;
        let addr = self.address(block);
        let r = match self.command(24, addr) {
            Ok(0) => match self.write_data(0xFE, data) {
                Ok(()) => self.wait_ready(BUSY_TIMEOUT_MS),
                Err(e) => Err(e),
            },
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.release_card();
        r.and(rel)
    }
}

/// Capacity in 512-byte blocks from a 16-byte CSD register.
pub fn csd_blocks(csd: &[u8; 16]) -> u32 {
    if csd[0] >> 6 == 1 {
        // CSD v2: C_SIZE = CSD[69:48]
        let csize = (((csd[7] & 0x3F) as u32) << 16) | ((csd[8] as u32) << 8) | csd[9] as u32;
        (csize + 1) * 1024
    } else {
        let read_bl_len = (csd[5] & 0x0F) as u32;
        let csize = (((csd[6] & 0x03) as u32) << 10) | ((csd[7] as u32) << 2) | (csd[8] >> 6) as u32;
        let mult = (((csd[9] & 0x03) as u32) << 1) | (csd[10] >> 7) as u32;
        let bytes = ((csize as u64) + 1) << (mult + 2) << read_bl_len;
        (bytes / SD_BLOCK_SIZE as u64) as u32
    }
}

/// SD card in SPI mode — full driver, implements [`BlockDevice`].
///
/// Adds multi-block transfers (`CMD18`/`CMD25`), erase, CID/CSD/OCR access and
/// CRC checking (on by default).
pub struct SdSpiFull<S: SpiExtensions> {
    inner: SdSpiMinimal<S>,
    name: [u8; 16],
}

impl<S: SpiExtensions> SdSpiFull<S> {
    /// Options with CRC16 checking enabled (the Full default).
    pub fn defaults() -> SdSpiOptions {
        SdSpiOptions { crc: true, ..SdSpiOptions::default() }
    }

    /// Create the driver and run the SPI-mode init sequence.
    pub fn new(spi: S, opt: SdSpiOptions) -> Res<Self, S> {
        let mut inner = SdSpiMinimal::new(spi, opt)?;
        let mut name = [0u8; 16];
        let mut cid = [0u8; 16];
        if inner.read_register(10, &mut cid).is_ok() {
            name[..5].copy_from_slice(&cid[3..8]); // PNM = CID[103:64]
        }
        Ok(Self { inner, name })
    }

    /// Release the SPI object.
    pub fn release(self) -> S {
        self.inner.release()
    }

    /// Capacity in 512-byte blocks.
    pub fn sector_count(&self) -> u32 {
        self.inner.block_count
    }

    /// Card generation.
    pub fn card_type(&self) -> SdCardType {
        self.inner.card
    }

    /// Read one 512-byte block.
    pub fn read_block(&mut self, block: u32, buf: &mut [u8; SD_BLOCK_SIZE]) -> Res<(), S> {
        self.inner.read_block(block, buf)
    }

    /// Write one 512-byte block.
    pub fn write_block(&mut self, block: u32, data: &[u8; SD_BLOCK_SIZE]) -> Res<(), S> {
        self.inner.write_block(block, data)
    }

    /// The 16-byte CID register (manufacturer, name, revision, serial, date).
    pub fn cid(&mut self) -> Res<[u8; 16], S> {
        let mut out = [0u8; 16];
        self.inner.read_register(10, &mut out)?;
        Ok(out)
    }

    /// The 16-byte CSD register as read during init.
    pub fn csd(&self) -> [u8; 16] {
        self.inner.csd
    }

    /// The OCR word (`CMD58`).
    pub fn ocr(&mut self) -> Res<u32, S> {
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let r = match self.inner.command(58, 0) {
            Ok(0) => {
                let mut o = [0u8; 4];
                SdSpiMinimal::<S>::io(self.inner.spi.read(&mut o)).map(|_| u32::from_be_bytes(o))
            }
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.inner.release_card();
        let v = r?;
        rel?;
        Ok(v)
    }

    /// Enable/disable `CMD59` and CRC16 checking.
    pub fn set_crc(&mut self, enabled: bool) -> Res<(), S> {
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let r = match self.inner.command(59, enabled as u32) {
            Ok(0) => Ok(()),
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.inner.release_card();
        r?;
        rel?;
        self.inner.crc_on = enabled;
        Ok(())
    }

    /// Change the SCK frequency after init; false if the platform cannot.
    pub fn set_speed(&mut self, hz: u32) -> bool {
        self.inner.spi.set_speed(hz)
    }

    /// True if a card responds to `CMD13`.
    pub fn is_present(&mut self) -> Res<bool, S> {
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let r = self.inner.command(13, 0);
        let mut ok = false;
        let r = r.and_then(|v| {
            ok = v != 0xFF;
            if ok { self.inner.rx().map(|_| ()) } else { Ok(()) }
        });
        let rel = self.inner.release_card();
        r?;
        rel?;
        Ok(ok)
    }

    fn check_range(&self, block: u32, count: u32) -> Res<(), S> {
        if (block as u64) + (count as u64) > self.inner.block_count as u64 {
            Err(BlockError::OutOfRange)
        } else {
            Ok(())
        }
    }
}

impl<S: SpiExtensions> BlockDevice for SdSpiFull<S> {
    type Error = S::Error;

    fn block_size(&self) -> u32 { SD_BLOCK_SIZE as u32 }
    fn block_count(&self) -> u32 { self.inner.block_count }
    fn erase_size(&self) -> u32 { SD_BLOCK_SIZE as u32 }
    fn needs_erase(&self) -> bool { false }

    fn read(&mut self, block: u32, buf: &mut [u8]) -> Res<(), S> {
        let count = (buf.len() / SD_BLOCK_SIZE) as u32;
        if count == 0 {
            return Ok(());
        }
        self.check_range(block, count)?;
        if count == 1 {
            let mut one = [0u8; SD_BLOCK_SIZE];
            self.inner.read_block(block, &mut one)?;
            buf[..SD_BLOCK_SIZE].copy_from_slice(&one);
            return Ok(());
        }
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let addr = self.inner.address(block);
        let r = match self.inner.command(18, addr) {
            Ok(0) => {
                let mut res = Ok(());
                for chunk in buf.chunks_exact_mut(SD_BLOCK_SIZE) {
                    res = self.inner.read_data(chunk);
                    if res.is_err() {
                        break;
                    }
                }
                let _ = self.inner.command(12, 0); // stop transmission, also after an error
                let _ = self.inner.wait_ready(BUSY_TIMEOUT_MS);
                res
            }
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.inner.release_card();
        r?;
        rel
    }

    fn write(&mut self, block: u32, data: &[u8]) -> Res<(), S> {
        let count = (data.len() / SD_BLOCK_SIZE) as u32;
        if count == 0 {
            return Ok(());
        }
        self.check_range(block, count)?;
        if count == 1 {
            let mut one = [0u8; SD_BLOCK_SIZE];
            one.copy_from_slice(&data[..SD_BLOCK_SIZE]);
            return self.inner.write_block(block, &one);
        }
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let _ = self.inner.app_command(23, count); // pre-erase hint; cards may ignore it
        let addr = self.inner.address(block);
        let r = match self.inner.command(25, addr) {
            Ok(0) => {
                let mut res = Ok(());
                for chunk in data.chunks_exact(SD_BLOCK_SIZE) {
                    res = self.inner.write_data(0xFC, chunk);
                    if res.is_err() {
                        break;
                    }
                }
                let _ = self.inner.wait_ready(BUSY_TIMEOUT_MS);
                let _ = self.inner.write_data(0xFD, &[]);
                let _ = self.inner.rx(); // stuff byte after the stop token
                let busy = self.inner.wait_ready(BUSY_TIMEOUT_MS);
                res.and(busy)
            }
            Ok(_) => Err(BlockError::NotReady),
            Err(e) => Err(e),
        };
        let rel = self.inner.release_card();
        r?;
        rel
    }

    fn erase(&mut self, block: u32, count: u32) -> Res<(), S> {
        if count == 0 {
            return Ok(());
        }
        self.check_range(block, count)?;
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let a = self.inner.address(block);
        let b = self.inner.address(block + count - 1);
        let ok = |r: u8| r == 0x00;
        let r = (|| -> Res<(), S> {
            if ok(self.inner.command(32, a)?) && ok(self.inner.command(33, b)?) && ok(self.inner.command(38, 0)?) {
                self.inner.wait_ready(ERASE_TIMEOUT_MS)
            } else {
                Err(BlockError::NotReady)
            }
        })();
        let rel = self.inner.release_card();
        r?;
        rel
    }

    fn sync(&mut self) -> Res<(), S> {
        SdSpiMinimal::<S>::io(self.inner.spi.select())?;
        let r = self.inner.wait_ready(BUSY_TIMEOUT_MS);
        let rel = self.inner.release_card();
        r?;
        rel
    }

    fn info(&self) -> BlockInfo {
        BlockInfo {
            block_size: SD_BLOCK_SIZE as u32,
            block_count: self.inner.block_count,
            erase_size: SD_BLOCK_SIZE as u32,
            needs_erase: false,
            read_only: false,
            bad_blocks: false,
            name: self.name,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::RamBlockDevice;
    use core::convert::Infallible;
    use std::collections::VecDeque;

    /// Behavioural model of an SD card in SPI mode behind `SpiExtensions`.
    /// Needs 80 idle clocks before CMD0, checks command CRC7, models R1/R3/R7,
    /// CSD/CID, single/multi-block transfers with tokens, busy and CRC16.
    struct SdModel {
        v2: bool,
        sdhc: bool,
        csize_v2: u32,
        acmd41_calls: u32,
        acmd41_needed: u32,
        got_idle_clocks: bool,
        selected: bool,
        in_idle: bool,
        crc_on: bool,
        corrupt_read_crc: bool,
        speed: u32,
        mem: Vec<u8>,
        cmd_log: Vec<u8>,
        cs_toggles_inside_command: u32,
        state: u8, // 0 CMD, 1 WTOKEN, 2 WDATA
        cmd_buf: Vec<u8>,
        out: VecDeque<u8>,
        app_next: bool,
        streaming: bool,
        stream_block: u32,
        w_block: u32,
        multi_write: bool,
        w_data: Vec<u8>,
    }

    impl SdModel {
        fn new(v2: bool, sdhc: bool) -> Self {
            Self {
                v2, sdhc, csize_v2: 7, acmd41_calls: 0, acmd41_needed: 3, got_idle_clocks: false,
                selected: false, in_idle: false, crc_on: false, corrupt_read_crc: false, speed: 0,
                mem: vec![0; 8192 * 512], cmd_log: vec![], cs_toggles_inside_command: 0, state: 0,
                cmd_buf: vec![], out: VecDeque::new(), app_next: false, streaming: false,
                stream_block: 0, w_block: 0, multi_write: false, w_data: vec![],
            }
        }

        fn push_block(&mut self, data: &[u8]) {
            self.out.push_back(0xFE);
            self.out.extend(data.iter().copied());
            let mut c = crc16(data);
            if self.corrupt_read_crc {
                c ^= 1;
            }
            self.out.push_back((c >> 8) as u8);
            self.out.push_back(c as u8);
        }

        fn block_of(&self, arg: u32) -> u32 {
            if self.sdhc { arg } else { arg / 512 }
        }

        fn do_command(&mut self) {
            let was_app = self.app_next;
            self.app_next = false;
            let cb = self.cmd_buf.clone();
            let cmd = cb[0] & 0x3F;
            let arg = u32::from_be_bytes([cb[1], cb[2], cb[3], cb[4]]);
            self.cmd_log.push(if was_app { 0x80 | cmd } else { cmd });
            if (cb[5] >> 1) != crc7(&cb[..5]) {
                self.out.extend([0xFF, 0x09]);
                return;
            }
            self.out.push_back(0xFF); // Ncr gap
            let idle = if self.in_idle { 0x01 } else { 0x00 };
            if was_app {
                if cmd == 41 {
                    self.acmd41_calls += 1;
                    if self.acmd41_calls >= self.acmd41_needed {
                        self.in_idle = false;
                        self.out.push_back(0x00);
                    } else {
                        self.out.push_back(0x01);
                    }
                } else {
                    self.out.push_back(idle);
                }
                return;
            }
            match cmd {
                0 => {
                    if !self.got_idle_clocks {
                        self.out.clear();
                        return;
                    }
                    self.in_idle = true;
                    self.out.push_back(0x01);
                }
                8 => {
                    if !self.v2 {
                        self.out.push_back(0x05);
                    } else {
                        self.out.extend([0x01, 0, 0, 0x01, 0xAA]);
                    }
                }
                9 => {
                    let mut csd = [0u8; 16];
                    if self.sdhc {
                        csd[0] = 0x40;
                        csd[7] = ((self.csize_v2 >> 16) & 0x3F) as u8;
                        csd[8] = (self.csize_v2 >> 8) as u8;
                        csd[9] = self.csize_v2 as u8;
                    } else {
                        csd[5] = 9; csd[6] = 0x03; csd[7] = 0xFF; csd[8] = 0xC0; csd[9] = 0x03; csd[10] = 0x80;
                    }
                    self.out.extend([0x00, 0xFF]);
                    self.push_block(&csd);
                }
                10 => {
                    let cid = [0x03, b'S', b'D', b'T', b'E', b'S', b'T', b'1', 0x10, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x23, 0x01];
                    self.out.extend([0x00, 0xFF]);
                    self.push_block(&cid);
                }
                12 => {
                    self.streaming = false;
                    self.out.clear();
                    self.out.extend([0xFF, 0x00, 0x00, 0x00, 0xFF]);
                }
                13 => self.out.extend([0x00, 0x00]),
                16 => self.out.push_back(idle),
                17 => {
                    self.out.extend([0x00, 0xFF]);
                    let b = self.block_of(arg) as usize;
                    let data = self.mem[b * 512..(b + 1) * 512].to_vec();
                    self.push_block(&data);
                }
                18 => {
                    self.out.push_back(0x00);
                    self.streaming = true;
                    self.stream_block = self.block_of(arg);
                }
                24 | 25 => {
                    self.out.push_back(0x00);
                    self.state = 1;
                    self.w_block = self.block_of(arg);
                    self.multi_write = cmd == 25;
                }
                32 | 33 => self.out.push_back(0x00),
                38 => self.out.extend([0x00, 0x00, 0x00]),
                55 => {
                    self.app_next = true;
                    self.out.push_back(idle);
                }
                58 => self.out.extend([idle, if self.sdhc { 0xC0 } else { 0x80 }, 0xFF, 0x80, 0x00]),
                59 => {
                    self.crc_on = arg & 1 != 0;
                    self.out.push_back(idle);
                }
                _ => self.out.push_back(0x04), // illegal command
            }
        }

        fn feed(&mut self, b: u8) -> u8 {
            match self.state {
                1 => {
                    if b == 0xFE || b == 0xFC {
                        self.state = 2;
                        self.w_data.clear();
                    } else if b == 0xFD {
                        self.state = 0;
                        self.out.extend([0xFF, 0x00, 0x00, 0xFF]);
                        return 0xFF;
                    }
                }
                2 => {
                    self.w_data.push(b);
                    if self.w_data.len() == 514 {
                        let got = ((self.w_data[512] as u16) << 8) | self.w_data[513] as u16;
                        let ok = !self.crc_on || got == crc16(&self.w_data[..512]);
                        if ok {
                            let s = self.w_block as usize * 512;
                            let d = self.w_data[..512].to_vec();
                            self.mem[s..s + 512].copy_from_slice(&d);
                            self.w_block += 1;
                        }
                        self.out.extend([if ok { 0xE5 } else { 0xEB }, 0x00, 0x00, 0xFF]);
                        self.state = if self.multi_write { 1 } else { 0 };
                        return 0xFF; // the data response follows on the NEXT byte clock
                    }
                }
                _ => {
                    if !self.cmd_buf.is_empty() || (b & 0xC0) == 0x40 {
                        self.cmd_buf.push(b);
                        if self.cmd_buf.len() == 6 {
                            self.do_command();
                            self.cmd_buf.clear();
                        }
                        return 0xFF;
                    }
                }
            }
            if let Some(v) = self.out.pop_front() {
                return v;
            }
            if self.streaming && self.state == 0 {
                let s = self.stream_block as usize * 512;
                let data = self.mem[s..s + 512].to_vec();
                self.push_block(&data);
                self.stream_block += 1;
                return self.out.pop_front().unwrap();
            }
            0xFF
        }
    }

    impl SpiExtensions for SdModel {
        type Error = Infallible;

        fn idle_clocks(&mut self, n: u32) -> Result<(), Infallible> {
            if !self.selected && n >= 80 {
                self.got_idle_clocks = true;
            }
            Ok(())
        }
        fn set_speed(&mut self, hz: u32) -> bool {
            self.speed = hz;
            true
        }
        fn select(&mut self) -> Result<(), Infallible> {
            self.selected = true;
            Ok(())
        }
        fn deselect(&mut self) -> Result<(), Infallible> {
            self.selected = false;
            if self.state == 0 && !self.cmd_buf.is_empty() {
                self.cs_toggles_inside_command += 1;
            }
            Ok(())
        }
        fn write(&mut self, data: &[u8]) -> Result<(), Infallible> {
            for &b in data {
                self.feed(b);
            }
            Ok(())
        }
        fn read(&mut self, buf: &mut [u8]) -> Result<(), Infallible> {
            for b in buf.iter_mut() {
                *b = self.feed(0xFF);
            }
            Ok(())
        }
    }

    #[test]
    fn crc_helpers() {
        assert_eq!(crc16(b"123456789"), 0x31C3);
        assert_eq!((crc7(&[0x40, 0, 0, 0, 0]) << 1) | 1, 0x95);
    }

    #[test]
    fn init_v2_sdhc() {
        let sd = SdSpiMinimal::new(SdModel::new(true, true), SdSpiOptions::default()).unwrap();
        assert_eq!(sd.card_type(), SdCardType::Sdhc);
        assert_eq!(sd.block_count(), 8192);
        let m = sd.release();
        assert!(m.got_idle_clocks, "80 idle clocks before CMD0");
        assert_eq!(m.speed, 12_000_000);
        assert_eq!(m.cs_toggles_inside_command, 0);
    }

    #[test]
    fn init_v1_byte_addressed() {
        let sd = SdSpiMinimal::new(SdModel::new(false, false), SdSpiOptions::default()).unwrap();
        assert_eq!(sd.card_type(), SdCardType::SdV1);
        assert_eq!(sd.block_count(), 2_097_152);
        assert!(sd.release().cmd_log.contains(&16), "CMD16 for byte-addressed card");
    }

    #[test]
    fn no_card_is_not_ready() {
        struct Dead;
        impl SpiExtensions for Dead {
            type Error = Infallible;
            fn idle_clocks(&mut self, _: u32) -> Result<(), Infallible> { Ok(()) }
            fn set_speed(&mut self, _: u32) -> bool { true }
            fn select(&mut self) -> Result<(), Infallible> { Ok(()) }
            fn deselect(&mut self) -> Result<(), Infallible> { Ok(()) }
            fn write(&mut self, _: &[u8]) -> Result<(), Infallible> { Ok(()) }
            fn read(&mut self, b: &mut [u8]) -> Result<(), Infallible> { b.fill(0xFF); Ok(()) }
        }
        assert!(matches!(SdSpiMinimal::new(Dead, SdSpiOptions::default()), Err(BlockError::NotReady)));
    }

    #[test]
    fn minimal_block_roundtrip() {
        let mut sd = SdSpiMinimal::new(SdModel::new(true, true), SdSpiOptions::default()).unwrap();
        let mut w = [0u8; 512];
        for (i, b) in w.iter_mut().enumerate() { *b = (i * 7 + 3) as u8; }
        sd.write_block(5, &w).unwrap();
        let mut r = [0u8; 512];
        sd.read_block(5, &mut r).unwrap();
        assert_eq!(w, r);
        assert_eq!(sd.read_block(8192, &mut r), Err(BlockError::OutOfRange));
    }

    #[test]
    fn full_multi_block_and_info() {
        let mut sd = SdSpiFull::new(SdModel::new(true, true), SdSpiFull::<SdModel>::defaults()).unwrap();
        assert_eq!(sd.block_size(), 512);
        assert!(!sd.needs_erase());
        assert_eq!(&sd.info().name[..5], b"TEST1");
        let w: Vec<u8> = (0..4 * 512usize).map(|k| (k ^ (k >> 8)) as u8).collect();
        sd.write(100, &w).unwrap();
        sd.sync().unwrap();
        let mut r = vec![0u8; 4 * 512];
        sd.read(100, &mut r).unwrap();
        assert_eq!(w, r);
        sd.erase(100, 4).unwrap();
        assert_eq!(sd.write(8190, &w), Err(BlockError::OutOfRange));
        assert!(sd.ocr().unwrap() & 0x4000_0000 != 0);
        assert!(sd.is_present().unwrap());
    }

    #[test]
    fn crc_error_is_reported() {
        let mut sd = SdSpiFull::new(SdModel::new(true, true), SdSpiFull::<SdModel>::defaults()).unwrap();
        let mut r = [0u8; 512];
        sd.inner.spi.corrupt_read_crc = true;
        assert_eq!(sd.read_block(1, &mut r), Err(BlockError::Crc));
        sd.set_crc(false).unwrap();
        sd.read_block(1, &mut r).unwrap();
    }

    #[test]
    fn blockdevice_contract_shared_with_ram() {
        fn exercise<D: BlockDevice>(d: &mut D) where D::Error: core::fmt::Debug {
            let w = [0xA5u8; 512];
            d.write(3, &w).unwrap();
            let mut r = [0u8; 512];
            d.read(3, &mut r).unwrap();
            assert_eq!(w, r);
            let n = d.block_count();
            assert!(matches!(d.read(n, &mut r), Err(BlockError::OutOfRange)));
        }
        exercise(&mut SdSpiFull::new(SdModel::new(true, true), SdSpiFull::<SdModel>::defaults()).unwrap());
        exercise(&mut RamBlockDevice::new(512, 64, 1, false));
    }
}
