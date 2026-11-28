//! ST25DV04K / ST25DV16K / ST25DV64K — dynamic NFC/RFID tag (STMicroelectronics).
//!
//! The chip answers on two I²C addresses, both with 16-bit register addresses:
//! `0x53` for the user memory, the dynamic registers and the fast-transfer-mode
//! mailbox, and `0x57` for the system configuration area (static registers,
//! UID, `IC_REF`, `MEM_SIZE`, the I²C password). Both live on the same bus, so
//! the driver owns one bus and takes both addresses.
//!
//! EEPROM writes (user memory and system area) are self-timed (5 ms per 4-byte
//! internal page); the chip does not acknowledge its address during the cycle,
//! so every write is completed by ACK polling. While an RF reader accesses the
//! EEPROM the chip NACKs I²C, so reads are retried up to 3 times (5 ms apart).
//! The driver owns a [`DelayNs`] provider for both.
//!
//! Reads and writes of user memory are split into transfers of at most 16 bytes
//! aligned to 16-byte boundaries, which never cross a 256-byte page or an area
//! border and fit the 32-byte buffer of [`crate::connection::register`].
//!
//! Interrupts: the Full driver exposes [`St25dvFull::poll_interrupt`] only; the
//! caller wires it into a GPO edge handler (falling edge for -IE, rising for
//! -JF variants) or a polling loop.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

const REG_BYTES: u8 = 2;
const CHUNK: usize = 16;
const WRITE_TIMEOUT_MS: u32 = 50;
const READ_RETRIES: u32 = 3;

const SYS_GPO: u16 = 0x0000;
const SYS_IT_TIME: u16 = 0x0001;
const SYS_EH_MODE: u16 = 0x0002;
const SYS_RFA1SS: u16 = 0x0004;
const SYS_ENDA1: u16 = 0x0005;
const SYS_I2CSS: u16 = 0x000B;
const SYS_LOCK_CCFILE: u16 = 0x000C;
const SYS_MB_MODE: u16 = 0x000D;
const SYS_MB_WDG: u16 = 0x000E;
const SYS_MEM_SIZE: u16 = 0x0014;
const SYS_UID: u16 = 0x0018;
const SYS_IC_REV: u16 = 0x0020;
const SYS_I2C_PWD: u16 = 0x0900;

const DYN_GPO_CTRL: u16 = 0x2000;
const DYN_EH_CTRL: u16 = 0x2002;
const DYN_RF_MNGT: u16 = 0x2003;
const DYN_I2C_SSO: u16 = 0x2004;
const DYN_IT_STS: u16 = 0x2005;
const DYN_MB_CTRL: u16 = 0x2006;
const DYN_MB_LEN: u16 = 0x2007;
const MAILBOX: u16 = 0x2008;
const MAILBOX_SIZE: usize = 256;

/// Interrupt source (`IT_STS_Dyn` bit 0): RF "Manage GPO" set/reset.
pub const SOURCE_RF_USER: u8 = 0x01;
/// Interrupt source (bit 1): RF access.
pub const SOURCE_RF_ACTIVITY: u8 = 0x02;
/// Interrupt source (bit 2): RF "Manage GPO" interrupt request.
pub const SOURCE_RF_INTERRUPT: u8 = 0x04;
/// Interrupt source (bit 3): RF field disappeared.
pub const SOURCE_FIELD_FALLING: u8 = 0x08;
/// Interrupt source (bit 4): RF field appeared.
pub const SOURCE_FIELD_RISING: u8 = 0x10;
/// Interrupt source (bit 5): RF reader wrote a mailbox message.
pub const SOURCE_RF_PUT_MSG: u8 = 0x20;
/// Interrupt source (bit 6): RF reader read the mailbox message.
pub const SOURCE_RF_GET_MSG: u8 = 0x40;
/// Interrupt source (bit 7): RF reader wrote to EEPROM.
pub const SOURCE_RF_WRITE: u8 = 0x80;

/// `GPO` bit 0: GPO level by RF "Manage GPO" set/reset.
pub const GPO_RF_USER: u8 = 0x01;
/// `GPO` bit 1: GPO follows RF activity.
pub const GPO_RF_ACTIVITY: u8 = 0x02;
/// `GPO` bit 2: GPO pulse by RF "Manage GPO".
pub const GPO_RF_INTERRUPT: u8 = 0x04;
/// `GPO` bit 3: pulse on RF field change.
pub const GPO_FIELD_CHANGE: u8 = 0x08;
/// `GPO` bit 4: pulse when the reader put a mailbox message.
pub const GPO_RF_PUT_MSG: u8 = 0x10;
/// `GPO` bit 5: pulse when the reader got the mailbox message.
pub const GPO_RF_GET_MSG: u8 = 0x20;
/// `GPO` bit 6: pulse after an RF EEPROM write.
pub const GPO_RF_WRITE: u8 = 0x40;
/// `GPO` bit 7: GPO output enable.
pub const GPO_EN: u8 = 0x80;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error (also a NACK on a protected or locked write).
    Bus(E),
    /// Address range outside the user memory.
    OutOfRange,
    /// Argument outside its documented range.
    InvalidArgument,
    /// The chip did not answer again within the write-cycle timeout.
    Timeout,
    /// The I²C security session is closed (needed to change the password).
    SessionClosed,
    /// The mailbox (fast transfer mode) is disabled.
    MailboxDisabled,
    /// The mailbox still holds a message.
    MailboxBusy,
    /// The destination buffer is too small for the message.
    BufferTooSmall,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// Decoded `MB_CTRL_Dyn` (fast-transfer-mode mailbox control and status).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct MailboxStatus {
    /// `MB_EN`: fast transfer mode active.
    pub enabled: bool,
    /// `HOST_PUT_MSG`: the host wrote the current message.
    pub host_put: bool,
    /// `RF_PUT_MSG`: the RF reader wrote the current message.
    pub rf_put: bool,
    /// `HOST_MISS_MSG`: host message not read before the watchdog.
    pub host_miss: bool,
    /// `RF_MISS_MSG`: RF message not read before the watchdog.
    pub rf_miss: bool,
    /// `HOST_CURRENT_MSG`: the host is reading the message.
    pub host_current: bool,
    /// `RF_CURRENT_MSG`: the RF reader is reading the message.
    pub rf_current: bool,
}

/// ST25DV minimal driver — user memory as a byte store.
///
/// [`St25dvMinimal::init`] reads `MEM_SIZE`, `BLK_SIZE` and `IC_REF`; no
/// configuration is written (delivery state: area 1 = the whole memory, no
/// protection, `GPO_EN = 1`, field-change interrupt only).
pub struct St25dvMinimal<I2C, D> {
    i2c: I2C,
    delay: D,
    user_addr: u8,
    system_addr: u8,
    size: u32,
    ic_ref: u8,
}

impl<I2C: I2c, D: DelayNs> St25dvMinimal<I2C, D> {
    /// Create the driver; no bus traffic (call [`Self::init`]).
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `delay` — Delay provider for ACK polling and read retries.
    /// * `user_addr` — User memory / dynamic register address (`0x53`).
    /// * `system_addr` — System area address (`0x57`).
    pub fn new(i2c: I2C, delay: D, user_addr: u8, system_addr: u8) -> Self {
        Self { i2c, delay, user_addr, system_addr, size: 0, ic_ref: 0 }
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        (self.i2c, self.delay)
    }

    /// Read `MEM_SIZE`, `BLK_SIZE` and `IC_REF` and derive the memory size.
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        let mut id = [0u8; 4];
        let sys = self.system_addr;
        self.read_reg(sys, SYS_MEM_SIZE, &mut id)?;
        let mem = id[0] as u32 | ((id[1] as u32) << 8);
        self.size = (mem + 1) * (id[2] as u32 + 1);
        self.ic_ref = id[3];
        Ok(())
    }

    /// Read user memory into `buf` starting at `address`.
    ///
    /// Returns [`Error::OutOfRange`] if the range lies outside the memory.
    pub fn read(&mut self, address: u16, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        self.check_range(address, buf.len())?;
        let user = self.user_addr;
        let mut a = address as usize;
        let mut done = 0;
        while done < buf.len() {
            let n = core::cmp::min(CHUNK - a % CHUNK, buf.len() - done);
            self.read_reg(user, a as u16, &mut buf[done..done + n])?;
            a += n;
            done += n;
        }
        Ok(())
    }

    /// Write `data` to user memory in 16-byte-aligned pieces, ACK-polling after each.
    ///
    /// Returns [`Error::OutOfRange`] outside the memory, [`Error::Bus`] on a NACK
    /// (protected area, mailbox active) and [`Error::Timeout`] if a write cycle
    /// does not complete.
    pub fn write(&mut self, address: u16, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        self.check_range(address, data.len())?;
        let user = self.user_addr;
        let mut a = address as usize;
        let mut done = 0;
        while done < data.len() {
            let n = core::cmp::min(CHUNK - a % CHUNK, data.len() - done);
            write_register(&mut self.i2c, user, a as u32, REG_BYTES, &data[done..done + n])?;
            self.commit(user)?;
            a += n;
            done += n;
        }
        Ok(())
    }

    fn check_range(&self, address: u16, length: usize) -> Result<(), Error<I2C::Error>> {
        if address as u32 + length as u32 > self.size {
            Err(Error::OutOfRange)
        } else {
            Ok(())
        }
    }

    fn read_reg(&mut self, addr: u8, reg: u16, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        let mut attempt = 1;
        loop {
            match read_register(&mut self.i2c, addr, reg as u32, REG_BYTES, buf) {
                Ok(()) => return Ok(()),
                Err(e) if attempt >= READ_RETRIES => return Err(Error::Bus(e)),
                Err(_) => {
                    attempt += 1;
                    self.delay.delay_ms(5);
                }
            }
        }
    }

    fn read_byte(&mut self, addr: u8, reg: u16) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.read_reg(addr, reg, &mut b)?;
        Ok(b[0])
    }

    fn write_reg(&mut self, addr: u8, reg: u16, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        write_register(&mut self.i2c, addr, reg as u32, REG_BYTES, data)?;
        Ok(())
    }

    fn ack_poll(&mut self, addr: u8, timeout_ms: u32) -> bool {
        let mut t = 0;
        loop {
            if write_register(&mut self.i2c, addr, 0, REG_BYTES, &[]).is_ok() {
                return true;
            }
            if t >= timeout_ms {
                return false;
            }
            self.delay.delay_ms(1);
            t += 1;
        }
    }

    fn commit(&mut self, addr: u8) -> Result<(), Error<I2C::Error>> {
        if self.ack_poll(addr, WRITE_TIMEOUT_MS) {
            Ok(())
        } else {
            Err(Error::Timeout)
        }
    }
}

/// ST25DV full driver — extends [`St25dvMinimal`] with identification, system
/// area, password session, memory areas, GPO interrupts, energy harvesting, RF
/// state, mailbox and a minimal NDEF writer.
///
/// System-area writes need an open I²C security session
/// ([`St25dvFull::present_password`]). Bits 0-6 of `GPO_CTRL_Dyn` are read-only
/// over I²C, so interrupt sources are configured in the static `GPO` register
/// (session required); only `GPO_EN` switches at runtime without a session.
pub struct St25dvFull<I2C, D> {
    inner: St25dvMinimal<I2C, D>,
}

impl<I2C: I2c, D: DelayNs> St25dvFull<I2C, D> {
    /// Create the driver; same arguments as [`St25dvMinimal::new`].
    pub fn new(i2c: I2C, delay: D, user_addr: u8, system_addr: u8) -> Self {
        Self { inner: St25dvMinimal::new(i2c, delay, user_addr, system_addr) }
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        self.inner.release()
    }

    /// Read `MEM_SIZE`, `BLK_SIZE` and `IC_REF`. See [`St25dvMinimal::init`].
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.init()
    }

    /// Read user memory. See [`St25dvMinimal::read`].
    pub fn read(&mut self, address: u16, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        self.inner.read(address, buf)
    }

    /// Write user memory. See [`St25dvMinimal::write`].
    pub fn write(&mut self, address: u16, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        self.inner.write(address, data)
    }

    /// User memory size in bytes (`(MEM_SIZE + 1) × (BLK_SIZE + 1)`), valid after `init`.
    pub fn memory_size(&self) -> u32 {
        self.inner.size
    }

    /// `IC_REF`: `0x24` (ST25DV04K) or `0x26` (ST25DV16K/64K), valid after `init`.
    pub fn ic_reference(&self) -> u8 {
        self.inner.ic_ref
    }

    /// Read the 64-bit UID, most significant byte first (`E0 02 …`).
    pub fn uid(&mut self) -> Result<[u8; 8], Error<I2C::Error>> {
        let mut raw = [0u8; 8];
        let sys = self.inner.system_addr;
        self.inner.read_reg(sys, SYS_UID, &mut raw)?;
        raw.reverse();
        Ok(raw)
    }

    /// Read `IC_REV`.
    pub fn ic_revision(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.sys_byte(SYS_IC_REV)
    }

    /// Raw read of the system configuration area (device `0x57`).
    pub fn read_system(&mut self, address: u16, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        let sys = self.inner.system_addr;
        self.inner.read_reg(sys, address, buf)
    }

    /// Raw write (≤ 32 bytes) to the system area, then ACK poll (session required).
    pub fn write_system(&mut self, address: u16, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        if data.len() > 32 {
            return Err(Error::InvalidArgument);
        }
        let sys = self.inner.system_addr;
        self.inner.write_reg(sys, address, data)?;
        self.inner.commit(sys)
    }

    fn sys_byte(&mut self, reg: u16) -> Result<u8, Error<I2C::Error>> {
        let sys = self.inner.system_addr;
        self.inner.read_byte(sys, reg)
    }

    fn dyn_byte(&mut self, reg: u16) -> Result<u8, Error<I2C::Error>> {
        let user = self.inner.user_addr;
        self.inner.read_byte(user, reg)
    }

    fn write_dyn(&mut self, reg: u16, value: u8) -> Result<(), Error<I2C::Error>> {
        let user = self.inner.user_addr;
        self.inner.write_reg(user, reg, &[value])
    }

    fn send_password(&mut self, password: &[u8; 8], code: u8) -> Result<(), Error<I2C::Error>> {
        let mut frame = [0u8; 17];
        frame[..8].copy_from_slice(password);
        frame[8] = code;
        frame[9..].copy_from_slice(password);
        let sys = self.inner.system_addr;
        self.inner.write_reg(sys, SYS_I2C_PWD, &frame)
    }

    /// Present the I²C password (opens the security session).
    ///
    /// `password` is most significant byte first (factory `00 × 8`). Returns
    /// whether the session is open afterwards.
    pub fn present_password(&mut self, password: &[u8; 8]) -> Result<bool, Error<I2C::Error>> {
        self.send_password(password, 0x09)?;
        let sys = self.inner.system_addr;
        self.inner.ack_poll(sys, WRITE_TIMEOUT_MS);
        self.session_open()
    }

    /// Change the I²C password; returns [`Error::SessionClosed`] without an open session.
    pub fn write_password(&mut self, password: &[u8; 8]) -> Result<(), Error<I2C::Error>> {
        if !self.session_open()? {
            return Err(Error::SessionClosed);
        }
        self.send_password(password, 0x07)?;
        let sys = self.inner.system_addr;
        self.inner.commit(sys)
    }

    /// `I2C_SSO_Dyn` bit 0: the I²C security session is open.
    pub fn session_open(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.dyn_byte(DYN_I2C_SSO)? & 0x01 != 0)
    }

    /// Set `ENDA1..3` (session required); area `n` ends at byte `end_block × 32 + 31`.
    ///
    /// # Arguments
    /// * `area` — 1-3.
    /// * `end_block` — `ENDAn` value 0-255.
    pub fn set_area_end(&mut self, area: u8, end_block: u8) -> Result<(), Error<I2C::Error>> {
        if !(1..=3).contains(&area) {
            return Err(Error::InvalidArgument);
        }
        self.write_system(SYS_ENDA1 + 2 * (area as u16 - 1), &[end_block])
    }

    /// Set `RFAnSS` and the area's `I2CSS` bits (session required).
    ///
    /// # Arguments
    /// * `area` — 1-4.
    /// * `rf` — `RFAnSS` 0-15 (bits 3:2 RW protection, bits 1:0 RF password).
    /// * `i2c` — `I2CSS` level 0-3 (bit 0 write, bit 1 read protection).
    pub fn set_area_protection(&mut self, area: u8, rf: u8, i2c: u8) -> Result<(), Error<I2C::Error>> {
        if !(1..=4).contains(&area) || rf > 0x0F || i2c > 3 {
            return Err(Error::InvalidArgument);
        }
        self.write_system(SYS_RFA1SS + 2 * (area as u16 - 1), &[rf])?;
        let shift = 2 * (area - 1);
        let i2css = self.sys_byte(SYS_I2CSS)?;
        let i2css = (i2css & !(0x03 << shift)) | (i2c << shift);
        self.write_system(SYS_I2CSS, &[i2css])
    }

    /// Read `(rf, i2c)` protection of `area` (1-4), as for [`Self::set_area_protection`].
    pub fn get_area_protection(&mut self, area: u8) -> Result<(u8, u8), Error<I2C::Error>> {
        if !(1..=4).contains(&area) {
            return Err(Error::InvalidArgument);
        }
        let rf = self.sys_byte(SYS_RFA1SS + 2 * (area as u16 - 1))? & 0x0F;
        let i2c = (self.sys_byte(SYS_I2CSS)? >> (2 * (area - 1))) & 0x03;
        Ok((rf, i2c))
    }

    /// Write the static `GPO` register (session required): `sources` (`GPO_*` bits 0-6)
    /// plus `GPO_EN` when `enable`. The chip copies it into `GPO_CTRL_Dyn`.
    pub fn set_gpo(&mut self, sources: u8, enable: bool) -> Result<(), Error<I2C::Error>> {
        let v = (sources & 0x7F) | if enable { GPO_EN } else { 0 };
        self.write_system(SYS_GPO, &[v])
    }

    /// Read the static `GPO` register (`GPO_*` mask).
    pub fn get_gpo(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.sys_byte(SYS_GPO)
    }

    /// Switch the GPO output at runtime (`GPO_CTRL_Dyn.GPO_EN`, no session; bits 0-6 are read-only).
    pub fn set_gpo_runtime(&mut self, enable: bool) -> Result<(), Error<I2C::Error>> {
        self.write_dyn(DYN_GPO_CTRL, if enable { GPO_EN } else { 0 })
    }

    /// Set `IT_TIME` 0-7 (session required); pulse = 301 µs − value × 37.65 µs.
    pub fn set_interrupt_pulse(&mut self, value: u8) -> Result<(), Error<I2C::Error>> {
        if value > 7 {
            return Err(Error::InvalidArgument);
        }
        self.write_system(SYS_IT_TIME, &[value])
    }

    /// `EH_CTRL_Dyn.FIELD_ON`: an RF field is present.
    pub fn field_present(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.dyn_byte(DYN_EH_CTRL)? & 0x04 != 0)
    }

    /// Set `EH_CTRL_Dyn.EH_EN`; write `EH_MODE` (session required) only when `forced` changes it.
    ///
    /// # Arguments
    /// * `enabled` — Energy harvesting on now.
    /// * `forced` — Forced after power-up (`EH_MODE = 0`) instead of on demand.
    pub fn set_energy_harvesting(&mut self, enabled: bool, forced: bool) -> Result<(), Error<I2C::Error>> {
        let mode = if forced { 0 } else { 1 };
        if self.sys_byte(SYS_EH_MODE)? & 0x01 != mode {
            self.write_system(SYS_EH_MODE, &[mode])?;
        }
        self.write_dyn(DYN_EH_CTRL, if enabled { 0x01 } else { 0x00 })
    }

    /// Set the runtime RF state `RF_MNGT_Dyn` (the static `RF_MNGT` is unchanged).
    pub fn set_rf_state(&mut self, disable: bool, sleep: bool) -> Result<(), Error<I2C::Error>> {
        let v = (if disable { 0x01 } else { 0 }) | (if sleep { 0x02 } else { 0 });
        self.write_dyn(DYN_RF_MNGT, v)
    }

    /// Set `MB_MODE` if needed (session required), then `MB_CTRL_Dyn.MB_EN`.
    pub fn enable_mailbox(&mut self) -> Result<(), Error<I2C::Error>> {
        if self.sys_byte(SYS_MB_MODE)? & 0x01 == 0 {
            self.write_system(SYS_MB_MODE, &[0x01])?;
        }
        self.write_dyn(DYN_MB_CTRL, 0x01)
    }

    /// Clear `MB_CTRL_Dyn.MB_EN` (`MB_MODE` stays).
    pub fn disable_mailbox(&mut self) -> Result<(), Error<I2C::Error>> {
        self.write_dyn(DYN_MB_CTRL, 0x00)
    }

    /// Decode `MB_CTRL_Dyn`.
    pub fn mailbox_status(&mut self) -> Result<MailboxStatus, Error<I2C::Error>> {
        let v = self.dyn_byte(DYN_MB_CTRL)?;
        Ok(MailboxStatus {
            enabled: v & 0x01 != 0,
            host_put: v & 0x02 != 0,
            rf_put: v & 0x04 != 0,
            host_miss: v & 0x10 != 0,
            rf_miss: v & 0x20 != 0,
            host_current: v & 0x40 != 0,
            rf_current: v & 0x80 != 0,
        })
    }

    /// Put a 1-256 byte message into the mailbox (one write starting at `0x2008`).
    ///
    /// Returns [`Error::MailboxDisabled`] or [`Error::MailboxBusy`] when the
    /// mailbox cannot take a message.
    pub fn mailbox_write(&mut self, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        if data.is_empty() || data.len() > MAILBOX_SIZE {
            return Err(Error::InvalidArgument);
        }
        let ctrl = self.dyn_byte(DYN_MB_CTRL)?;
        if ctrl & 0x01 == 0 {
            return Err(Error::MailboxDisabled);
        }
        if ctrl & 0x06 != 0 {
            return Err(Error::MailboxBusy);
        }
        let mut frame = [0u8; 2 + MAILBOX_SIZE];
        frame[0] = (MAILBOX >> 8) as u8;
        frame[1] = MAILBOX as u8;
        frame[2..2 + data.len()].copy_from_slice(data);
        let user = self.inner.user_addr;
        self.inner.i2c.write(user, &frame[..2 + data.len()])?;
        Ok(())
    }

    /// Read the mailbox message (`MB_LEN_Dyn + 1` bytes) into `buf`.
    ///
    /// Returns the message length, 0 when no message is present, and
    /// [`Error::BufferTooSmall`] if it does not fit.
    pub fn mailbox_read(&mut self, buf: &mut [u8]) -> Result<usize, Error<I2C::Error>> {
        let ctrl = self.dyn_byte(DYN_MB_CTRL)?;
        if ctrl & 0x06 == 0 {
            return Ok(0);
        }
        let length = self.dyn_byte(DYN_MB_LEN)? as usize + 1;
        if length > buf.len() {
            return Err(Error::BufferTooSmall);
        }
        let user = self.inner.user_addr;
        let mut done = 0;
        while done < length {
            let n = core::cmp::min(CHUNK, length - done);
            self.inner.read_reg(user, MAILBOX + done as u16, &mut buf[done..done + n])?;
            done += n;
        }
        Ok(length)
    }

    /// Set `MB_WDG` 0-7 (session required); watchdog = 2^(value − 1) × 30 ms, 0 disables it.
    pub fn set_mailbox_watchdog(&mut self, value: u8) -> Result<(), Error<I2C::Error>> {
        if value > 7 {
            return Err(Error::InvalidArgument);
        }
        self.write_system(SYS_MB_WDG, &[value])
    }

    /// Write `LOCK_CCFILE` (session required): RF write protection of blocks 0 and 1.
    pub fn lock_ccfile(&mut self, lock: bool) -> Result<(), Error<I2C::Error>> {
        self.write_system(SYS_LOCK_CCFILE, &[if lock { 0x03 } else { 0x00 }])
    }

    /// Write a Type 5 capability container and one NDEF TLV (`03 len … FE`,
    /// `03 FF hi lo …` for 255 bytes and more).
    ///
    /// Returns [`Error::OutOfRange`] if the message does not fit.
    pub fn write_ndef(&mut self, message: &[u8]) -> Result<(), Error<I2C::Error>> {
        let size = self.inner.size;
        let mut head = [0u8; 12];
        let mut h = if size <= 2048 {
            let mlen = core::cmp::min(size / 8, 0xFF) as u8;
            head[..4].copy_from_slice(&[0xE1, 0x40, mlen, 0x01]);
            4
        } else {
            let mlen = size / 8 - 1;
            head[..8].copy_from_slice(&[0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, (mlen >> 8) as u8, mlen as u8]);
            8
        };
        head[h] = 0x03;
        h += 1;
        if message.len() < 0xFF {
            head[h] = message.len() as u8;
            h += 1;
        } else {
            head[h] = 0xFF;
            head[h + 1] = (message.len() >> 8) as u8;
            head[h + 2] = message.len() as u8;
            h += 3;
        }
        if (h + message.len() + 1) as u32 > size {
            return Err(Error::OutOfRange);
        }
        self.inner.write(0, &head[..h])?;
        if !message.is_empty() {
            self.inner.write(h as u16, message)?;
        }
        self.inner.write((h + message.len()) as u16, &[0xFE])
    }

    /// Read the message of the first NDEF TLV into `buf`.
    ///
    /// Returns the message length, 0 when no capability container or NDEF TLV
    /// is found, and [`Error::BufferTooSmall`] if it does not fit.
    pub fn read_ndef(&mut self, buf: &mut [u8]) -> Result<usize, Error<I2C::Error>> {
        let size = self.inner.size as usize;
        let mut cc = [0u8; 4];
        self.inner.read(0, &mut cc)?;
        let mut pos = match cc[0] {
            0xE1 => 4,
            0xE2 => 8,
            _ => return Ok(0),
        };
        while pos < size {
            let mut t = [0u8; 1];
            self.inner.read(pos as u16, &mut t)?;
            if t[0] == 0x00 {
                pos += 1;
                continue;
            }
            if t[0] == 0xFE || pos + 2 > size {
                return Ok(0);
            }
            let mut l = [0u8; 1];
            self.inner.read((pos + 1) as u16, &mut l)?;
            pos += 2;
            let mut length = l[0] as usize;
            if l[0] == 0xFF {
                if pos + 2 > size {
                    return Ok(0);
                }
                let mut lb = [0u8; 2];
                self.inner.read(pos as u16, &mut lb)?;
                length = ((lb[0] as usize) << 8) | lb[1] as usize;
                pos += 2;
            }
            if t[0] == 0x03 {
                if pos + length > size {
                    return Ok(0);
                }
                if length > buf.len() {
                    return Err(Error::BufferTooSmall);
                }
                self.inner.read(pos as u16, &mut buf[..length])?;
                return Ok(length);
            }
            pos += length;
        }
        Ok(0)
    }

    /// ACK poll on the user address until the write cycle has finished ([`Error::Timeout`] otherwise).
    pub fn wait_write_complete(&mut self) -> Result<(), Error<I2C::Error>> {
        let user = self.inner.user_addr;
        self.inner.commit(user)
    }

    /// Enable `SOURCE_*` sources in the static `GPO` register plus `GPO_EN` (session required).
    /// `SOURCE_FIELD_RISING` and `SOURCE_FIELD_FALLING` share `FIELD_CHANGE_EN`.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        let gpo = self.get_gpo()? | source_to_gpo(source) | GPO_EN;
        self.write_system(SYS_GPO, &[gpo])?;
        self.set_gpo_runtime(true)
    }

    /// Clear `SOURCE_*` sources in the static `GPO` register (session required).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        let gpo = self.get_gpo()? & !source_to_gpo(source);
        self.write_system(SYS_GPO, &[gpo])
    }

    /// Read `IT_STS_Dyn` (cleared on read): the `SOURCE_*` mask, 0 = none.
    ///
    /// The caller wires this into a GPO edge handler or a polling loop.
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.dyn_byte(DYN_IT_STS)
    }
}

/// Map `SOURCE_*` bits (IT_STS_Dyn layout) to `GPO_*` enable bits.
fn source_to_gpo(source: u8) -> u8 {
    let mut gpo = source & 0x07;
    if source & (SOURCE_FIELD_RISING | SOURCE_FIELD_FALLING) != 0 {
        gpo |= GPO_FIELD_CHANGE;
    }
    gpo | ((source >> 1) & 0x70)
}

#[cfg(test)]
mod tests {
    extern crate std;
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};
    use std::vec;
    use std::vec::Vec;

    const U: u8 = 0x53;
    const S: u8 = 0x57;

    fn ident(mem_lo: u8, mem_hi: u8, ic_ref: u8) -> T {
        T::write_read(S, vec![0x00, 0x14], vec![mem_lo, mem_hi, 0x03, ic_ref])
    }

    fn poll(addr: u8) -> T {
        T::write(addr, vec![0x00, 0x00])
    }

    fn full(t: &[T]) -> St25dvFull<I2cMock, NoopDelay> {
        St25dvFull::new(I2cMock::new(t), NoopDelay, U, S)
    }

    #[test]
    fn init_and_sizes() {
        for (lo, hi, r, size) in [(0x7F, 0x00, 0x24, 512u32), (0xFF, 0x01, 0x26, 2048), (0xFF, 0x07, 0x26, 8192)] {
            let t = [ident(lo, hi, r)];
            let mut chip = full(&t);
            chip.init().unwrap();
            assert_eq!(chip.memory_size(), size);
            assert_eq!(chip.ic_reference(), r);
            chip.release().0.done();
        }
    }

    #[test]
    fn write_splits_at_16_byte_boundaries() {
        let data: Vec<u8> = (0..40).collect();
        let mut f0 = vec![0x00, 0x08];
        f0.extend_from_slice(&data[..8]);
        let mut f1 = vec![0x00, 0x10];
        f1.extend_from_slice(&data[8..24]);
        let mut f2 = vec![0x00, 0x20];
        f2.extend_from_slice(&data[24..]);
        let t = [ident(0xFF, 0x01, 0x26), T::write(U, f0), poll(U), T::write(U, f1), poll(U), T::write(U, f2), poll(U)];
        let mut chip = St25dvMinimal::new(I2cMock::new(&t), NoopDelay, U, S);
        chip.init().unwrap();
        chip.write(0x0008, &data).unwrap();
        assert_eq!(chip.write(2040, &[0u8; 9]), Err(Error::OutOfRange));
        chip.release().0.done();
    }

    #[test]
    fn read_retries_then_ack_poll_timeout() {
        let mut t = vec![
            ident(0xFF, 0x01, 0x26),
            T::write_read(U, vec![0x00, 0x40], vec![0]).with_error(ErrorKind::Other),
            T::write_read(U, vec![0x00, 0x40], vec![0x77]),
            T::write(U, vec![0x00, 0x00, 0x01]),
        ];
        for _ in 0..=WRITE_TIMEOUT_MS {
            t.push(poll(U).with_error(ErrorKind::Other));
        }
        let mut chip = full(&t);
        chip.init().unwrap();
        let mut b = [0u8; 1];
        chip.read(0x0040, &mut b).unwrap();
        assert_eq!(b[0], 0x77);
        assert_eq!(chip.write(0, &[1]), Err(Error::Timeout));
        chip.release().0.done();
    }

    #[test]
    fn identification_and_password() {
        let pw = [1u8, 2, 3, 4, 5, 6, 7, 8];
        let mut present = vec![0x09, 0x00];
        present.extend_from_slice(&pw);
        present.push(0x09);
        present.extend_from_slice(&pw);
        let mut change = present.clone();
        change[10] = 0x07;
        let t = [
            ident(0xFF, 0x01, 0x26),
            T::write_read(S, vec![0x00, 0x18], vec![0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0]),
            T::write_read(S, vec![0x00, 0x20], vec![0x12]),
            T::write(S, present),
            poll(S),
            T::write_read(U, vec![0x20, 0x04], vec![0x01]),
            T::write_read(U, vec![0x20, 0x04], vec![0x01]),
            T::write(S, change),
            poll(S),
        ];
        let mut chip = full(&t);
        chip.init().unwrap();
        assert_eq!(chip.uid().unwrap(), [0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11]);
        assert_eq!(chip.ic_revision().unwrap(), 0x12);
        assert!(chip.present_password(&pw).unwrap());
        chip.write_password(&pw).unwrap();
        chip.release().0.done();
    }

    #[test]
    fn areas_and_interrupt_mapping() {
        let t = [
            ident(0xFF, 0x01, 0x26),
            T::write(S, vec![0x00, 0x07, 0x1F]),
            poll(S),
            T::write(S, vec![0x00, 0x08, 0x05]),
            poll(S),
            T::write_read(S, vec![0x00, 0x0B], vec![0x04]),
            T::write(S, vec![0x00, 0x0B, 0x34]),
            poll(S),
            T::write_read(S, vec![0x00, 0x00], vec![0x80]),
            T::write(S, vec![0x00, 0x00, 0xD9]),
            poll(S),
            T::write(U, vec![0x20, 0x00, 0x80]),
            T::write_read(U, vec![0x20, 0x05], vec![0x90]),
        ];
        let mut chip = full(&t);
        chip.init().unwrap();
        chip.set_area_end(2, 0x1F).unwrap();
        assert_eq!(chip.set_area_end(4, 0), Err(Error::InvalidArgument));
        chip.set_area_protection(3, 0x05, 0x03).unwrap();
        assert_eq!(chip.set_area_protection(1, 0, 4), Err(Error::InvalidArgument));
        chip.enable_interrupt(SOURCE_FIELD_RISING | SOURCE_RF_WRITE | SOURCE_RF_PUT_MSG | SOURCE_RF_USER).unwrap();
        assert_eq!(chip.poll_interrupt().unwrap(), 0x90);
        chip.release().0.done();
    }

    #[test]
    fn mailbox_and_ndef() {
        let msg: Vec<u8> = (0..100).collect();
        let mut mb = vec![0x20, 0x08];
        mb.extend_from_slice(&msg);
        let ndef = [0xD1u8, 0x01, 0x04, 0x54];
        let t = [
            ident(0xFF, 0x01, 0x26),
            T::write_read(U, vec![0x20, 0x06], vec![0x05]),
            T::write_read(U, vec![0x20, 0x06], vec![0x01]),
            T::write(U, mb),
            T::write_read(U, vec![0x20, 0x06], vec![0x05]),
            T::write_read(U, vec![0x20, 0x07], vec![2]),
            T::write_read(U, vec![0x20, 0x08], vec![0xA0, 0xA1, 0xA2]),
            T::write(U, vec![0x00, 0x00, 0xE1, 0x40, 0xFF, 0x01, 0x03, 0x04]),
            poll(U),
            T::write(U, vec![0x00, 0x06, 0xD1, 0x01, 0x04, 0x54]),
            poll(U),
            T::write(U, vec![0x00, 0x0A, 0xFE]),
            poll(U),
        ];
        let mut chip = full(&t);
        chip.init().unwrap();
        assert_eq!(chip.mailbox_write(&[1]), Err(Error::MailboxBusy));
        chip.mailbox_write(&msg).unwrap();
        let mut buf = [0u8; 256];
        assert_eq!(chip.mailbox_read(&mut buf).unwrap(), 3);
        assert_eq!(&buf[..3], &[0xA0, 0xA1, 0xA2]);
        chip.write_ndef(&ndef).unwrap();
        assert_eq!(chip.write_ndef(&[0u8; 2048]), Err(Error::OutOfRange));
        chip.release().0.done();
    }

    #[test]
    fn source_mapping() {
        assert_eq!(source_to_gpo(SOURCE_FIELD_FALLING), GPO_FIELD_CHANGE);
        assert_eq!(source_to_gpo(SOURCE_RF_GET_MSG), GPO_RF_GET_MSG);
        assert_eq!(source_to_gpo(0xFF), 0x7F);
    }
}
