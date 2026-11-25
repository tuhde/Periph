//! MX25L12845E — Macronix 128-Mbit (16 MiB) serial NOR flash (`specs/memory/mx25l12845e.md`).
//!
//! [`Mx25l12845eMinimal`] is a byte-addressable store with transparent erase:
//! [`read_bytes`](Mx25l12845eMinimal::read_bytes), an erase-aware
//! [`write_bytes`](Mx25l12845eMinimal::write_bytes) (read-modify-erase-write per 4 KiB
//! sector, programming only what changed) and
//! [`erase_sector`](Mx25l12845eMinimal::erase_sector). [`Mx25l12845eFull`] adds the raw
//! program / erase commands, status and block protection, deep power-down, SFDP and the
//! secured OTP, and implements [`BlockDevice`] (512-byte blocks, 4 KiB erase units,
//! `needs_erase() == true`).
//!
//! Plain single-I/O SPI Mode 0 over a bare [`SpiDevice`]: every command is one
//! `transaction` (CS held low for the whole frame). `write_bytes` needs a 4 KiB sector
//! buffer on the stack, so call it from a task with enough stack on small MCUs.
//!
//! ## Timeouts
//!
//! `no_std` has no clock: [`Mx25l12845eOptions::now_ms`] supplies one, otherwise the busy
//! polling uses bounded poll counts (about 8 polls per millisecond of the datasheet maximum).
//! [`Mx25l12845eOptions::delay_ms`] is slept between polls of long operations.

use crate::connection::spi::{Operation, SpiDevice};
use crate::storage::{BlockDevice, BlockError, BlockInfo};

const CMD_WREN: u8 = 0x06;
const CMD_RDID: u8 = 0x9F;
const CMD_RES: u8 = 0xAB;
const CMD_REMS: u8 = 0x90;
const CMD_RDSR: u8 = 0x05;
const CMD_WRSR: u8 = 0x01;
const CMD_READ: u8 = 0x03;
const CMD_FAST_READ: u8 = 0x0B;
const CMD_PP: u8 = 0x02;
const CMD_SE: u8 = 0x20;
const CMD_BE32K: u8 = 0x52;
const CMD_BE64K: u8 = 0xD8;
const CMD_CE: u8 = 0xC7;
const CMD_DP: u8 = 0xB9;
const CMD_RDP: u8 = 0xAB;
const CMD_RDSFDP: u8 = 0x5A;
const CMD_ENSO: u8 = 0xB1;
const CMD_EXSO: u8 = 0xC1;
const CMD_RDSCUR: u8 = 0x2B;
const CMD_WRSCUR: u8 = 0x2F;
const CMD_CLSR: u8 = 0x30;

const SR_WIP: u8 = 0x01;
const SR_WEL: u8 = 0x02;
const SR_BP_MASK: u8 = 0x3C;
const SR_BP_SHIFT: u8 = 2;
const SCUR_P_FAIL: u8 = 0x20;
const SCUR_E_FAIL: u8 = 0x40;

const T_WRSR_MS: u32 = 150;
const T_PP_MS: u32 = 10;
const T_SE_MS: u32 = 400;
const T_BE_MS: u32 = 3000;
const T_CE_MS: u32 = 250_000;
const READ_CHUNK: usize = 128;

/// Capacity in bytes (16 MiB).
pub const MX25L12845E_SIZE: u32 = 16 * 1024 * 1024;
/// Page program granularity.
pub const MX25L12845E_PAGE_SIZE: usize = 256;
/// Smallest erase unit.
pub const MX25L12845E_SECTOR_SIZE: usize = 4096;

type Res<T, SPI> = Result<T, BlockError<<SPI as embedded_hal::spi::ErrorType>::Error>>;

/// Driver options.
#[derive(Clone, Copy)]
pub struct Mx25l12845eOptions {
    /// Millisecond clock for timeouts; `None` uses bounded poll counts.
    pub now_ms: Option<fn() -> u32>,
    /// Sleep used between polls of long operations and after `RDP`; `None` busy-polls.
    pub delay_ms: Option<fn(u32)>,
    /// [`BlockDevice::write`] erases sectors itself when a 0→1 transition is needed.
    pub auto_erase: bool,
}

impl Default for Mx25l12845eOptions {
    fn default() -> Self {
        Self { now_ms: None, delay_ms: None, auto_erase: false }
    }
}

fn addr_frame(cmd: u8, address: u32) -> [u8; 4] {
    [cmd, (address >> 16) as u8, (address >> 8) as u8, address as u8]
}

/// MX25L12845E minimal driver — byte-addressed read, erase-aware write.
pub struct Mx25l12845eMinimal<SPI: SpiDevice> {
    spi: SPI,
    opt: Mx25l12845eOptions,
}

impl<SPI: SpiDevice> Mx25l12845eMinimal<SPI> {
    /// Run the init sequence: `RDP`, `RDID` check (`C2 20 18`), wait until not busy.
    ///
    /// # Errors
    /// [`BlockError::NotReady`] if the JEDEC ID is wrong, [`BlockError::Timeout`] if the chip
    /// stays busy, [`BlockError::Io`] on a bus error.
    pub fn new(spi: SPI, opt: Mx25l12845eOptions) -> Res<Self, SPI> {
        let mut s = Self { spi, opt };
        s.command(CMD_RDP)?; // leave deep power-down if a previous session left it there
        s.sleep(1); // tRES2 = 100 us
        if s.jedec_id()? != [0xC2, 0x20, 0x18] {
            return Err(BlockError::NotReady);
        }
        s.wait_ready(T_CE_MS)?;
        Ok(s)
    }

    /// Release the SPI device.
    pub fn release(self) -> SPI {
        self.spi
    }

    // ---- bus primitives -----------------------------------------------------

    fn io<T>(r: Result<T, SPI::Error>) -> Res<T, SPI> {
        r.map_err(BlockError::Io)
    }

    fn sleep(&mut self, ms: u32) {
        if let Some(f) = self.opt.delay_ms {
            f(ms);
        }
    }

    fn command(&mut self, cmd: u8) -> Res<(), SPI> {
        Self::io(self.spi.transaction(&mut [Operation::Write(&[cmd])]))
    }

    fn read_status_raw(&mut self) -> Res<u8, SPI> {
        let mut sr = [0u8];
        Self::io(self.spi.transaction(&mut [Operation::Write(&[CMD_RDSR]), Operation::Read(&mut sr)]))?;
        Ok(sr[0])
    }

    fn wren(&mut self) -> Res<(), SPI> {
        self.command(CMD_WREN)?;
        if self.read_status_raw()? & SR_WEL == 0 {
            return Err(BlockError::Timeout); // WEL must be set or the next command is ignored
        }
        Ok(())
    }

    fn wait_ready(&mut self, timeout_ms: u32) -> Res<(), SPI> {
        let start = self.opt.now_ms.map_or(0, |f| f());
        let mut polls: u32 = 0;
        loop {
            if self.read_status_raw()? & SR_WIP == 0 {
                return Ok(());
            }
            match self.opt.now_ms {
                Some(f) => {
                    if f().wrapping_sub(start) >= timeout_ms {
                        return Err(BlockError::Timeout);
                    }
                    if timeout_ms > 20 {
                        self.sleep(1); // long operations: do not hammer the bus
                    }
                }
                None => {
                    polls += 1;
                    if polls > timeout_ms.saturating_mul(8) {
                        return Err(BlockError::Timeout);
                    }
                }
            }
        }
    }

    fn check_fail(&mut self) -> Res<(), SPI> {
        let mut scur = [0u8];
        Self::io(self.spi.transaction(&mut [Operation::Write(&[CMD_RDSCUR]), Operation::Read(&mut scur)]))?;
        if scur[0] & (SCUR_P_FAIL | SCUR_E_FAIL) == 0 {
            return Ok(());
        }
        self.command(CMD_CLSR)?;
        let sr = self.read_status_raw()?;
        Err(if sr & SR_BP_MASK != 0 { BlockError::WriteProtected } else { BlockError::Timeout })
    }

    fn erase_cmd(&mut self, cmd: u8, address: u32, timeout_ms: u32) -> Res<(), SPI> {
        self.wren()?;
        Self::io(self.spi.transaction(&mut [Operation::Write(&addr_frame(cmd, address))]))?;
        self.wait_ready(timeout_ms)?;
        self.check_fail()
    }

    fn program(&mut self, address: u32, data: &[u8]) -> Res<(), SPI> {
        self.wren()?;
        Self::io(self.spi.transaction(&mut [
            Operation::Write(&addr_frame(CMD_PP, address)),
            Operation::Write(data),
        ]))?;
        self.wait_ready(T_PP_MS)?;
        self.check_fail()
    }

    fn read_raw(&mut self, cmd: u8, address: u32, dummy: bool, buf: &mut [u8]) -> Res<(), SPI> {
        let frame = addr_frame(cmd, address);
        let dummy_byte = [0u8];
        if dummy {
            Self::io(self.spi.transaction(&mut [
                Operation::Write(&frame),
                Operation::Write(&dummy_byte),
                Operation::Read(buf),
            ]))
        } else {
            Self::io(self.spi.transaction(&mut [Operation::Write(&frame), Operation::Read(buf)]))
        }
    }

    fn do_read(&mut self, mut address: u32, buf: &mut [u8]) -> Res<(), SPI> {
        for chunk in buf.chunks_mut(READ_CHUNK) {
            self.read_raw(CMD_READ, address, false, chunk)?;
            address += chunk.len() as u32;
        }
        Ok(())
    }

    // ---- minimal API ----------------------------------------------------------

    /// The three `RDID` bytes (manufacturer, memory type, density).
    pub fn jedec_id(&mut self) -> Res<[u8; 3], SPI> {
        let mut id = [0u8; 3];
        Self::io(self.spi.transaction(&mut [Operation::Write(&[CMD_RDID]), Operation::Read(&mut id)]))?;
        Ok(id)
    }

    /// Capacity in bytes (16 777 216).
    pub fn size(&self) -> u32 {
        MX25L12845E_SIZE
    }

    /// Read `buf.len()` bytes starting at `address` (`READ`, 03h), any length and alignment.
    pub fn read_bytes(&mut self, address: u32, buf: &mut [u8]) -> Res<(), SPI> {
        if address as u64 + buf.len() as u64 > MX25L12845E_SIZE as u64 {
            return Err(BlockError::OutOfRange);
        }
        self.do_read(address, buf)
    }

    /// Erase the 4 KiB sector containing `address` (`SE`, 20h).
    pub fn erase_sector(&mut self, address: u32) -> Res<(), SPI> {
        if address >= MX25L12845E_SIZE {
            return Err(BlockError::OutOfRange);
        }
        self.erase_cmd(CMD_SE, address, T_SE_MS)
    }

    /// Erase-aware write; blocks until the chip is ready.
    ///
    /// Per 4 KiB sector: if only 1→0 transitions are needed just the changed pages are
    /// programmed, otherwise the sector is erased and rewritten with the merged contents.
    /// Returns [`BlockError::WriteProtected`] if the chip reports a failure while block
    /// protect bits are set.
    pub fn write_bytes(&mut self, mut address: u32, mut data: &[u8]) -> Res<(), SPI> {
        if address as u64 + data.len() as u64 > MX25L12845E_SIZE as u64 {
            return Err(BlockError::OutOfRange);
        }
        const SECTOR: u32 = MX25L12845E_SECTOR_SIZE as u32;
        const PAGE: u32 = MX25L12845E_PAGE_SIZE as u32;
        while !data.is_empty() {
            let base = address & !(SECTOR - 1);
            let off = (address - base) as usize;
            let n = (MX25L12845E_SECTOR_SIZE - off).min(data.len());

            let mut sector = [0u8; MX25L12845E_SECTOR_SIZE];
            self.do_read(base, &mut sector)?;
            let needs_erase = (0..n).any(|i| sector[off + i] & data[i] != data[i]); // would need a 0 -> 1 transition

            if !needs_erase {
                let mut pos = 0usize;
                while pos < n {
                    let a = address + pos as u32;
                    let chunk = ((PAGE - (a & (PAGE - 1))) as usize).min(n - pos);
                    if sector[off + pos..off + pos + chunk] != data[pos..pos + chunk] {
                        self.program(a, &data[pos..pos + chunk])?;
                    }
                    pos += chunk;
                }
            } else {
                sector[off..off + n].copy_from_slice(&data[..n]);
                self.erase_cmd(CMD_SE, base, T_SE_MS)?;
                for page in (0..MX25L12845E_SECTOR_SIZE).step_by(MX25L12845E_PAGE_SIZE) {
                    let block = &sector[page..page + MX25L12845E_PAGE_SIZE];
                    if block.iter().any(|&b| b != 0xFF) {
                        // blank pages need no program
                        self.program(base + page as u32, block)?;
                    }
                }
            }
            address += n as u32;
            data = &data[n..];
        }
        Ok(())
    }
}

/// MX25L12845E full driver: raw commands, protection, power, OTP, SFDP; implements [`BlockDevice`].
pub struct Mx25l12845eFull<SPI: SpiDevice> {
    inner: Mx25l12845eMinimal<SPI>,
    bp_cache: u8,
}

impl<SPI: SpiDevice> Mx25l12845eFull<SPI> {
    /// Run the init sequence (see [`Mx25l12845eMinimal::new`]).
    pub fn new(spi: SPI, opt: Mx25l12845eOptions) -> Res<Self, SPI> {
        Ok(Self { inner: Mx25l12845eMinimal::new(spi, opt)?, bp_cache: 0 })
    }

    /// Release the SPI device.
    pub fn release(self) -> SPI {
        self.inner.release()
    }

    // ---- minimal API, delegated ---------------------------------------------------

    /// The three `RDID` bytes.
    pub fn jedec_id(&mut self) -> Res<[u8; 3], SPI> { self.inner.jedec_id() }
    /// Capacity in bytes.
    pub fn size(&self) -> u32 { MX25L12845E_SIZE }
    /// Byte-addressed read (`READ`, 03h).
    pub fn read_bytes(&mut self, address: u32, buf: &mut [u8]) -> Res<(), SPI> { self.inner.read_bytes(address, buf) }
    /// Erase-aware byte-addressed write.
    pub fn write_bytes(&mut self, address: u32, data: &[u8]) -> Res<(), SPI> { self.inner.write_bytes(address, data) }
    /// Erase the 4 KiB sector containing `address`.
    pub fn erase_sector(&mut self, address: u32) -> Res<(), SPI> { self.inner.erase_sector(address) }

    // ---- raw operations -----------------------------------------------------------

    /// `FAST_READ` (0Bh).
    pub fn fast_read(&mut self, address: u32, buf: &mut [u8]) -> Res<(), SPI> {
        if address as u64 + buf.len() as u64 > MX25L12845E_SIZE as u64 {
            return Err(BlockError::OutOfRange);
        }
        let mut a = address;
        for chunk in buf.chunks_mut(READ_CHUNK) {
            self.inner.read_raw(CMD_FAST_READ, a, true, chunk)?;
            a += chunk.len() as u32;
        }
        Ok(())
    }

    /// One `PP` of 1..=256 bytes that must not cross a 256-byte page boundary; the bytes must be erased.
    pub fn page_program(&mut self, address: u32, data: &[u8]) -> Res<(), SPI> {
        let n = data.len();
        if n == 0 || n > MX25L12845E_PAGE_SIZE || address as u64 + n as u64 > MX25L12845E_SIZE as u64 {
            return Err(BlockError::OutOfRange);
        }
        if (address as usize & (MX25L12845E_PAGE_SIZE - 1)) + n > MX25L12845E_PAGE_SIZE {
            return Err(BlockError::OutOfRange); // would wrap inside the page
        }
        self.inner.program(address, data)
    }

    /// Erase the 32 KiB block containing `address` (`BE32K`, 52h).
    pub fn erase_block_32k(&mut self, address: u32) -> Res<(), SPI> {
        if address >= MX25L12845E_SIZE {
            return Err(BlockError::OutOfRange);
        }
        self.inner.erase_cmd(CMD_BE32K, address, T_BE_MS)
    }

    /// Erase the 64 KiB block containing `address` (`BE`, D8h).
    pub fn erase_block_64k(&mut self, address: u32) -> Res<(), SPI> {
        if address >= MX25L12845E_SIZE {
            return Err(BlockError::OutOfRange);
        }
        self.inner.erase_cmd(CMD_BE64K, address, T_BE_MS)
    }

    /// Erase the whole chip (`CE`, C7h): 80 s typ, 200 s max; needs BP3:0 == 0.
    pub fn chip_erase(&mut self) -> Res<(), SPI> {
        self.inner.wren()?;
        self.inner.command(CMD_CE)?;
        self.inner.wait_ready(T_CE_MS)?;
        self.inner.check_fail()
    }

    // ---- status / protection -------------------------------------------------------

    /// The status register.
    pub fn read_status(&mut self) -> Res<u8, SPI> { self.inner.read_status_raw() }

    /// `WREN` + `WRSR`; waits for `tW`.
    pub fn write_status(&mut self, value: u8) -> Res<(), SPI> {
        self.inner.wren()?;
        Mx25l12845eMinimal::<SPI>::io(self.inner.spi.transaction(&mut [Operation::Write(&[CMD_WRSR, value])]))?;
        self.inner.wait_ready(T_WRSR_MS)
    }

    /// BP3:0 (0..=15).
    pub fn protection(&mut self) -> Res<u8, SPI> {
        let bp = (self.inner.read_status_raw()? & SR_BP_MASK) >> SR_BP_SHIFT;
        self.bp_cache = bp;
        Ok(bp)
    }

    /// Set BP3:0 (non-volatile); QE and SRWD are preserved.
    pub fn set_protection(&mut self, bp: u8) -> Res<(), SPI> {
        if bp > 15 {
            return Err(BlockError::OutOfRange);
        }
        let sr = self.inner.read_status_raw()?;
        self.write_status((sr & !SR_BP_MASK) | (bp << SR_BP_SHIFT))?;
        self.bp_cache = bp;
        Ok(())
    }

    /// True while a program, erase or `WRSR` is running (WIP).
    pub fn is_busy(&mut self) -> Res<bool, SPI> { Ok(self.inner.read_status_raw()? & SR_WIP != 0) }

    /// Wait until WIP = 0.
    pub fn wait_ready(&mut self, timeout_ms: u32) -> Res<(), SPI> { self.inner.wait_ready(timeout_ms) }

    /// The security register (`RDSCUR`).
    pub fn read_security(&mut self) -> Res<u8, SPI> {
        let mut scur = [0u8];
        Mx25l12845eMinimal::<SPI>::io(self.inner.spi.transaction(&mut [Operation::Write(&[CMD_RDSCUR]), Operation::Read(&mut scur)]))?;
        Ok(scur[0])
    }

    /// `CLSR`: clear P_FAIL / E_FAIL.
    pub fn clear_fail_flags(&mut self) -> Res<(), SPI> { self.inner.command(CMD_CLSR) }

    // ---- power / identification ----------------------------------------------------

    /// `DP`: deep power-down (only `RDP` / `RES` are accepted afterwards).
    pub fn deep_power_down(&mut self) -> Res<(), SPI> { self.inner.command(CMD_DP) }

    /// `RDP`: leave deep power-down and wait `tRES2`.
    pub fn release_deep_power_down(&mut self) -> Res<(), SPI> {
        self.inner.command(CMD_RDP)?;
        self.inner.sleep(1);
        Ok(())
    }

    /// `RES` electronic ID (17h).
    pub fn electronic_id(&mut self) -> Res<u8, SPI> {
        let mut id = [0u8];
        Mx25l12845eMinimal::<SPI>::io(self.inner.spi.transaction(&mut [Operation::Write(&[CMD_RES, 0, 0, 0]), Operation::Read(&mut id)]))?;
        Ok(id[0])
    }

    /// `REMS`: `(manufacturer, device)` = `(0xC2, 0x17)`.
    pub fn device_ids(&mut self) -> Res<(u8, u8), SPI> {
        let mut ids = [0u8; 2];
        Mx25l12845eMinimal::<SPI>::io(self.inner.spi.transaction(&mut [Operation::Write(&[CMD_REMS, 0, 0, 0]), Operation::Read(&mut ids)]))?;
        Ok((ids[0], ids[1]))
    }

    /// `RDSFDP` (5Ah): read the SFDP table from `address`.
    pub fn read_sfdp(&mut self, address: u32, buf: &mut [u8]) -> Res<(), SPI> {
        let mut a = address;
        for chunk in buf.chunks_mut(READ_CHUNK) {
            self.inner.read_raw(CMD_RDSFDP, a, true, chunk)?;
            a += chunk.len() as u32;
        }
        Ok(())
    }

    // ---- secured OTP (4 Kbit) -------------------------------------------------------

    /// Read OTP bytes at `offset` (0..512).
    pub fn read_otp(&mut self, offset: u32, buf: &mut [u8]) -> Res<(), SPI> {
        if offset as u64 + buf.len() as u64 > 512 {
            return Err(BlockError::OutOfRange);
        }
        self.inner.command(CMD_ENSO)?;
        let r = self.inner.do_read(offset, buf);
        let x = self.inner.command(CMD_EXSO);
        r.and(x)
    }

    /// Program OTP bytes at `offset` (16..512 only: 0..16 is the ESN area); one-time programmable.
    pub fn program_otp(&mut self, mut offset: u32, data: &[u8]) -> Res<(), SPI> {
        if offset < 16 || data.is_empty() || offset as u64 + data.len() as u64 > 512 {
            return Err(BlockError::OutOfRange);
        }
        self.inner.command(CMD_ENSO)?;
        let mut r = Ok(());
        let mut rest = data;
        while !rest.is_empty() && r.is_ok() {
            let n = (MX25L12845E_PAGE_SIZE - (offset as usize & (MX25L12845E_PAGE_SIZE - 1))).min(rest.len());
            r = self.inner.program(offset, &rest[..n]);
            offset += n as u32;
            rest = &rest[n..];
        }
        let x = self.inner.command(CMD_EXSO);
        r.and(x)
    }

    /// Lock the customer OTP area for good (`WRSCUR`, sets LDSO) — **irreversible**.
    pub fn lock_otp(&mut self) -> Res<(), SPI> {
        self.inner.wren()?;
        self.inner.command(CMD_WRSCUR)?;
        self.inner.wait_ready(T_WRSR_MS)
    }

    /// The 16 bytes of OTP `000h`–`00Fh` (the factory ESN when `read_security() & 1`; blank `FF` otherwise).
    pub fn serial_number(&mut self) -> Res<[u8; 16], SPI> {
        let mut out = [0u8; 16];
        self.read_otp(0, &mut out)?;
        Ok(out)
    }
}

impl<SPI: SpiDevice> BlockDevice for Mx25l12845eFull<SPI> {
    type Error = SPI::Error;

    fn block_size(&self) -> u32 { 512 }
    fn block_count(&self) -> u32 { MX25L12845E_SIZE / 512 }
    fn erase_size(&self) -> u32 { MX25L12845E_SECTOR_SIZE as u32 }
    fn needs_erase(&self) -> bool { true }

    fn read(&mut self, block: u32, buf: &mut [u8]) -> Res<(), SPI> {
        let count = (buf.len() / 512) as u32;
        if block as u64 + count as u64 > self.block_count() as u64 {
            return Err(BlockError::OutOfRange);
        }
        self.inner.do_read(block * 512, buf)
    }

    fn write(&mut self, block: u32, data: &[u8]) -> Res<(), SPI> {
        let count = (data.len() / 512) as u32;
        if block as u64 + count as u64 > self.block_count() as u64 {
            return Err(BlockError::OutOfRange);
        }
        let address = block * 512;
        if self.inner.opt.auto_erase {
            return self.inner.write_bytes(address, data);
        }
        // No auto-erase: program page by page, but only where no 0 -> 1 transition is needed.
        let mut a = address;
        let mut rest = data;
        while !rest.is_empty() {
            let n = (MX25L12845E_PAGE_SIZE - (a as usize & (MX25L12845E_PAGE_SIZE - 1))).min(rest.len());
            let mut old = [0u8; MX25L12845E_PAGE_SIZE];
            self.inner.do_read(a, &mut old[..n])?;
            if (0..n).any(|i| old[i] & rest[i] != rest[i]) {
                return Err(BlockError::NotErased);
            }
            if old[..n] != rest[..n] {
                self.inner.program(a, &rest[..n])?;
            }
            a += n as u32;
            rest = &rest[n..];
        }
        Ok(())
    }

    fn erase(&mut self, block: u32, count: u32) -> Res<(), SPI> {
        if block % 8 != 0 || count % 8 != 0 || block as u64 + count as u64 > self.block_count() as u64 {
            return Err(BlockError::OutOfRange);
        }
        let mut address = block * 512;
        let mut left = count * 512;
        while left > 0 {
            if address & 0xFFFF == 0 && left >= 65536 {
                self.erase_block_64k(address)?;
                address += 65536;
                left -= 65536;
            } else if address & 0x7FFF == 0 && left >= 32768 {
                self.erase_block_32k(address)?;
                address += 32768;
                left -= 32768;
            } else {
                self.erase_sector(address)?;
                address += MX25L12845E_SECTOR_SIZE as u32;
                left -= MX25L12845E_SECTOR_SIZE as u32;
            }
        }
        Ok(())
    }

    fn sync(&mut self) -> Res<(), SPI> {
        self.inner.wait_ready(T_CE_MS)
    }

    fn info(&self) -> BlockInfo {
        let mut name = [0u8; 16];
        name[..11].copy_from_slice(b"MX25L12845E");
        BlockInfo {
            block_size: 512,
            block_count: MX25L12845E_SIZE / 512,
            erase_size: MX25L12845E_SECTOR_SIZE as u32,
            needs_erase: true,
            read_only: self.bp_cache >= 8,
            bad_blocks: false,
            name,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::RamBlockDevice;
    use core::convert::Infallible;
    use embedded_hal::spi::ErrorType;

    /// Behavioural MX25L12845E behind `SpiDevice`: 16 MiB array programmable 1→0 only, page wrap
    /// on PP, 4/32/64 KiB and chip erase, WEL/WIP handling (WIP reads high for a few RDSR polls
    /// after every program/erase), BP block protect with P_FAIL/E_FAIL, secured OTP, deep
    /// power-down, RES/REMS/RDID/SFDP. Every command opcode is recorded.
    struct Model {
        mem: Vec<u8>,
        otp: [u8; 512],
        in_otp: bool,
        wel: bool,
        dp: bool,
        otp_locked: bool,
        sr: u8,
        scur: u8,
        id: [u8; 3],
        busy_polls: u32,
        busy_after_op: u32,
        stuck_busy: bool,
        cmds: Vec<u8>,
        pp: u32,
        se: u32,
        be32: u32,
        be64: u32,
        ce: u32,
    }

    impl Model {
        fn new() -> Self {
            let mut otp = [0xFFu8; 512];
            for i in 0..16 {
                otp[i] = 0xA0 + i as u8;
            }
            Self {
                mem: vec![0xFF; 16 * 1024 * 1024], otp, in_otp: false, wel: false, dp: false, otp_locked: false,
                sr: 0, scur: 0x01, id: [0xC2, 0x20, 0x18], busy_polls: 0, busy_after_op: 2, stuck_busy: false,
                cmds: vec![], pp: 0, se: 0, be32: 0, be64: 0, ce: 0,
            }
        }

        fn protected_from(&self) -> u32 {
            let bp = (self.sr >> 2) & 0xF;
            if bp == 0 { 16 * 1024 * 1024 } else if bp >= 8 { 0 } else { 16 * 1024 * 1024 - (65536u32 << bp) }
        }

        fn is_protected(&self, a: u32, n: u32) -> bool {
            a + n > self.protected_from()
        }

        fn start_busy(&mut self) {
            self.busy_polls = self.busy_after_op;
            self.wel = false;
        }

        fn frame(&mut self, d: &[u8], out: &mut [u8]) {
            let op = d[0];
            self.cmds.push(op);
            if self.dp && op != 0xAB {
                return;
            }
            let busy = self.stuck_busy || self.busy_polls > 0;
            if busy && !matches!(op, 0x05 | 0x2B | 0x9F | 0x30) {
                return;
            }
            let a = if d.len() >= 4 { ((d[1] as u32) << 16) | ((d[2] as u32) << 8) | d[3] as u32 } else { 0 };
            match op {
                0x06 => self.wel = true,
                0x04 => self.wel = false,
                0x9F => { for i in 0..out.len().min(3) { out[i] = self.id[i]; } }
                0x05 => {
                    out[0] = (self.sr & 0xFC) | if self.wel { 2 } else { 0 } | if busy { 1 } else { 0 };
                    if self.busy_polls > 0 { self.busy_polls -= 1; }
                }
                0x01 => { if self.wel && d.len() >= 2 { self.sr = d[1] & 0xFC; self.start_busy(); } }
                0x03 | 0x0B | 0x5A => {
                    for i in 0..out.len() {
                        out[i] = if op == 0x5A { (a as usize + i) as u8 }
                            else if self.in_otp { self.otp[(a as usize + i) & 0x1FF] }
                            else { self.mem[(a as usize + i) & 0xFF_FFFF] };
                    }
                }
                0x02 => {
                    if self.wel {
                        let data = &d[4..];
                        self.pp += 1;
                        if self.in_otp {
                            if self.otp_locked { self.scur |= 0x20; }
                            else { for (i, b) in data.iter().enumerate() { self.otp[(a as usize + i) & 0x1FF] &= b; } }
                        } else if self.is_protected(a & !255, 256) {
                            self.scur |= 0x20;
                        } else {
                            let page = (a & !255) as usize;
                            for (i, b) in data.iter().enumerate() { self.mem[page + ((a as usize + i) & 255)] &= b; }
                        }
                        self.start_busy();
                    }
                }
                0x20 | 0x52 | 0xD8 => {
                    if self.wel {
                        let size = match op { 0x20 => 4096u32, 0x52 => 32768, _ => 65536 };
                        let base = a & !(size - 1);
                        match op { 0x20 => self.se += 1, 0x52 => self.be32 += 1, _ => self.be64 += 1 }
                        if self.is_protected(base, size) { self.scur |= 0x40; }
                        else { for b in &mut self.mem[base as usize..(base + size) as usize] { *b = 0xFF; } }
                        self.start_busy();
                    }
                }
                0xC7 | 0x60 => {
                    if self.wel {
                        self.ce += 1;
                        if (self.sr >> 2) & 0xF != 0 { self.scur |= 0x40; }
                        else { for b in self.mem.iter_mut() { *b = 0xFF; } }
                        self.start_busy();
                    }
                }
                0xB9 => self.dp = true,
                0xAB => { self.dp = false; if !out.is_empty() { out[0] = 0x17; } }
                0x90 => { if out.len() >= 2 { out[0] = 0xC2; out[1] = 0x17; } }
                0xB1 => self.in_otp = true,
                0xC1 => self.in_otp = false,
                0x2B => out[0] = self.scur,
                0x2F => { if self.wel { self.scur |= 0x02; self.otp_locked = true; self.start_busy(); } }
                0x30 => self.scur &= !0x60,
                _ => {}
            }
        }
    }

    impl ErrorType for Model {
        type Error = Infallible;
    }

    impl SpiDevice for Model {
        fn transaction(&mut self, operations: &mut [Operation<'_, u8>]) -> Result<(), Infallible> {
            let mut cmd: Vec<u8> = vec![];
            let mut read_done = false;
            for op in operations.iter_mut() {
                match op {
                    Operation::Write(w) => cmd.extend_from_slice(w),
                    Operation::Read(r) => {
                        let mut out = vec![0u8; r.len()];
                        self.frame(&cmd, &mut out);
                        r.copy_from_slice(&out);
                        read_done = true;
                    }
                    _ => {}
                }
            }
            if !read_done {
                self.frame(&cmd, &mut []);
            }
            Ok(())
        }
    }

    fn opt() -> Mx25l12845eOptions { Mx25l12845eOptions::default() }

    #[test]
    fn init_checks_the_id_and_releases_deep_power_down() {
        let mut m = Model::new();
        m.dp = true;
        let f = Mx25l12845eMinimal::new(m, opt()).unwrap();
        let m = f.release();
        assert!(!m.dp, "init releases deep power-down (RDP)");

        let mut wrong = Model::new();
        wrong.id = [0xC2, 0x20, 0x17];
        assert!(matches!(Mx25l12845eMinimal::new(wrong, opt()), Err(BlockError::NotReady)));

        let mut stuck = Model::new();
        stuck.stuck_busy = true;
        assert!(matches!(Mx25l12845eMinimal::new(stuck, opt()), Err(BlockError::Timeout)));
    }

    #[test]
    fn minimal_write_is_erase_aware() {
        let mut f = Mx25l12845eMinimal::new(Model::new(), opt()).unwrap();
        let pattern: Vec<u8> = (0..600usize).map(|i| (i * 7 + 3) as u8).collect();
        f.write_bytes(0x1F0, &pattern).unwrap();
        let mut back = vec![0u8; 600];
        f.read_bytes(0x1F0, &mut back).unwrap();
        assert_eq!(back, pattern, "write across a page boundary into blank flash");

        let m = f.release();
        assert_eq!(m.se, 0, "blank flash needs no erase");
        assert!(m.mem[0x1EF] == 0xFF && m.mem[0x1F0 + 600] == 0xFF, "neighbouring bytes untouched");
        let pp = m.pp;

        let mut f = Mx25l12845eMinimal::new(m, opt()).unwrap();
        f.write_bytes(0x1F0, &pattern).unwrap();
        let m = f.release();
        assert!(m.pp == pp && m.se == 0, "identical rewrite programs and erases nothing");

        let mut f = Mx25l12845eMinimal::new(m, opt()).unwrap();
        let more: Vec<u8> = pattern.iter().map(|b| b & 0xF0).collect();
        f.write_bytes(0x1F0, &more).unwrap();
        f.read_bytes(0x1F0, &mut back).unwrap();
        assert_eq!(back, more, "1->0 only rewrite");
        f.write_bytes(0x1F0, &[0xFF; 10]).unwrap();
        let mut ten = [0u8; 10];
        f.read_bytes(0x1F0, &mut ten).unwrap();
        assert_eq!(ten, [0xFF; 10], "0->1 erased the sector");
        let mut tail = [0u8; 20];
        f.read_bytes(0x200, &mut tail).unwrap();
        assert_eq!(&tail[..], &more[16..36], "rest of the sector survived the erase");
        assert_eq!(f.release().se, 1);
    }

    #[test]
    fn minimal_multi_sector_and_range() {
        let mut f = Mx25l12845eMinimal::new(Model::new(), opt()).unwrap();
        let big: Vec<u8> = (0..9000usize).map(|i| (i ^ (i >> 8)) as u8).collect();
        f.write_bytes(0x2FF0, &big).unwrap();
        let mut back = vec![0u8; 9000];
        f.read_bytes(0x2FF0, &mut back).unwrap();
        assert_eq!(back, big);
        assert_eq!(f.write_bytes(MX25L12845E_SIZE - 2, &big[..5]), Err(BlockError::OutOfRange));
        f.erase_sector(0x2000).unwrap();
        assert_eq!(f.release().mem[0x2000], 0xFF);
    }

    #[test]
    fn protection_reports_write_protected() {
        let mut f = Mx25l12845eFull::new(Model::new(), opt()).unwrap();
        assert_eq!(f.protection().unwrap(), 0);
        f.set_protection(8).unwrap();
        assert_eq!(f.protection().unwrap(), 8);
        assert_eq!(f.page_program(0x100, &[0x00]), Err(BlockError::WriteProtected));
        assert_eq!(f.read_security().unwrap() & 0x60, 0, "fail flags cleared by the driver");
        assert_eq!(f.erase_sector(0), Err(BlockError::WriteProtected));
        assert!(f.info().read_only);
        assert_eq!(f.set_protection(16), Err(BlockError::OutOfRange));
        f.set_protection(0).unwrap();
        f.page_program(0x100, &[0x00]).unwrap();
        assert_eq!(f.release().mem[0x100], 0x00);
    }

    #[test]
    fn raw_operations() {
        let mut f = Mx25l12845eFull::new(Model::new(), opt()).unwrap();
        let data: Vec<u8> = (0..300usize).map(|i| i as u8).collect();
        f.page_program(0x1000, &data[..256]).unwrap();
        assert_eq!(f.page_program(0x10C0, &data[..100]), Err(BlockError::OutOfRange), "page crossing");
        assert_eq!(f.page_program(0, &data[..257]), Err(BlockError::OutOfRange), "> 256");
        let (mut a, mut b) = ([0u8; 256], [0u8; 256]);
        f.read_bytes(0x1000, &mut a).unwrap();
        f.fast_read(0x1000, &mut b).unwrap();
        assert!(a[..] == data[..256] && b == a, "READ and FAST_READ agree");
        f.erase_block_32k(0x8000).unwrap();
        f.erase_block_64k(0x10000).unwrap();
        f.chip_erase().unwrap();
        assert_eq!(f.electronic_id().unwrap(), 0x17);
        assert_eq!(f.device_ids().unwrap(), (0xC2, 0x17));
        f.deep_power_down().unwrap();
        f.release_deep_power_down().unwrap();
        let mut sfdp = [0u8; 8];
        f.read_sfdp(0, &mut sfdp).unwrap();
        assert_eq!(sfdp[1], 1);
        assert!(!f.is_busy().unwrap());
        f.write_status(0x04).unwrap();
        f.write_status(0).unwrap();
        let m = f.release();
        assert!(m.be32 == 1 && m.be64 == 1 && m.ce == 1 && !m.dp && m.mem[0x1000] == 0xFF);
    }

    #[test]
    fn secured_otp() {
        let mut f = Mx25l12845eFull::new(Model::new(), opt()).unwrap();
        let esn = f.serial_number().unwrap();
        assert!(esn[0] == 0xA0 && esn[15] == 0xAF);
        f.program_otp(16, &[1, 2, 3, 4]).unwrap();
        let mut back = [0u8; 4];
        f.read_otp(16, &mut back).unwrap();
        assert_eq!(back, [1, 2, 3, 4]);
        assert_eq!(f.program_otp(8, &[1]), Err(BlockError::OutOfRange), "ESN area refused");
        assert_eq!(f.program_otp(510, &[1, 2, 3, 4]), Err(BlockError::OutOfRange));
        f.lock_otp().unwrap();
        let m = f.release();
        assert!(m.otp_locked && !m.in_otp);
    }

    #[test]
    fn block_device() {
        let mut f = Mx25l12845eFull::new(Model::new(), opt()).unwrap();
        assert!(f.block_size() == 512 && f.block_count() == 32768 && f.erase_size() == 4096 && f.needs_erase());
        let w: Vec<u8> = (0..1024usize).map(|k| (k * 5 + 1) as u8).collect();
        f.write(8, &w).unwrap();
        let mut r = vec![0u8; 1024];
        f.read(8, &mut r).unwrap();
        assert_eq!(r, w);
        f.write(8, &w).unwrap();
        assert_eq!(f.write(8, &[0xFF; 512]), Err(BlockError::NotErased));
        f.erase(8, 8).unwrap();
        assert_eq!(f.erase(9, 8), Err(BlockError::OutOfRange));
        assert_eq!(f.erase(32768 - 8, 16), Err(BlockError::OutOfRange));
        assert_eq!(f.read(32768, &mut r[..512]), Err(BlockError::OutOfRange));
        f.sync().unwrap();
        let m = f.release();
        assert_eq!(m.se, 1, "only the explicit erase touched the array");
        assert_eq!(m.mem[8 * 512], 0xFF);

        let mut f = Mx25l12845eFull::new(m, opt()).unwrap();
        let (be64, be32, se) = (f.inner.spi.be64, f.inner.spi.be32, f.inner.spi.se);
        f.erase(0, 128 + 64 + 8).unwrap();
        assert!(f.inner.spi.be64 - be64 == 1 && f.inner.spi.be32 - be32 == 1 && f.inner.spi.se - se == 1, "64K, then 32K, then 4K");

        let mut g = Mx25l12845eFull::new(Model::new(), Mx25l12845eOptions { auto_erase: true, ..opt() }).unwrap();
        g.write(0, &w).unwrap();
        g.write(0, &[0xFF; 512]).unwrap();
        let mut one = [0u8; 512];
        g.read(0, &mut one).unwrap();
        assert!(one == [0xFF; 512] && g.inner.spi.se == 1, "auto_erase handles 0->1");
        g.read(1, &mut one).unwrap();
        assert_eq!(&one[..], &w[512..], "neighbouring block kept");
    }

    #[test]
    fn contract_shared_with_ram() {
        fn exercise<D: BlockDevice>(d: &mut D) where D::Error: core::fmt::Debug {
            d.erase(0, 8).unwrap();
            let w = [0x5Au8; 512];
            d.write(0, &w).unwrap();
            let mut r = [0u8; 512];
            d.read(0, &mut r).unwrap();
            assert_eq!(w, r);
            let n = d.block_count();
            assert!(matches!(d.read(n, &mut r), Err(BlockError::OutOfRange)));
        }
        exercise(&mut Mx25l12845eFull::new(Model::new(), opt()).unwrap());
        exercise(&mut RamBlockDevice::new(512, 64, 8, false));
    }

    #[test]
    fn wait_ready_times_out() {
        let mut f = Mx25l12845eFull::new(Model::new(), opt()).unwrap();
        f.inner.spi.stuck_busy = true;
        assert_eq!(f.wait_ready(5), Err(BlockError::Timeout));
        assert!(f.is_busy().unwrap());
    }
}
