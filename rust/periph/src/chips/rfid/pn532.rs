//! PN532 — NFC/RFID controller (NXP), I²C frame protocol.
//!
//! The host does not access registers directly; it exchanges frames with the PN532 firmware:
//! a command frame `00 00 FF LEN LCS D4 code data DCS 00`, the chip's ACK frame
//! `00 00 FF 00 FF 00` and a response frame (`TFI 0xD5`, `code + 1`). On I²C (fixed address
//! [`ADDRESS`] `0x24`) every read starts with a status byte (bit 0 = frame ready); a 1-byte read
//! does not consume the frame. The driver polls that byte, reads the 6-byte response header,
//! sends a NACK frame so the chip resends the response, then reads exactly `1 + LEN + 7` bytes.
//!
//! Construction does no bus access. `init()` checks `GetFirmwareVersion` (`IC = 0x32`), sets
//! `SAMConfiguration` normal mode with the IRQ pin enabled and `MxRtyPassiveActivation = 2`
//! (so a read without a card returns). Each write is retried up to 10 times (2 ms apart) to
//! wake the chip; the ACK is awaited 15 ms and the frame resent up to 3 times. Response data is
//! limited to [`MAX_DATA`] bytes.
//!
//! The Rust driver exposes [`Pn532Full::poll_interrupt`] (no callback subscription): the caller
//! wires the `IRQ` pin into an ISR or polling loop, then reads the frame with
//! [`Pn532Full::read_response`].

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;
use heapless::Vec;

/// Fixed 7-bit I²C address.
pub const ADDRESS: u8 = 0x24;
/// Maximum command / response data bytes handled by the driver.
pub const MAX_DATA: usize = 64;

/// `InListPassiveTarget` baud rate: 106 kbps ISO/IEC 14443 A.
pub const BAUD_106_A: u8 = 0x00;
/// `InListPassiveTarget` baud rate: 212 kbps FeliCa.
pub const BAUD_212_F: u8 = 0x01;
/// `InListPassiveTarget` baud rate: 424 kbps FeliCa.
pub const BAUD_424_F: u8 = 0x02;
/// `InListPassiveTarget` baud rate: 106 kbps ISO/IEC 14443 B.
pub const BAUD_106_B: u8 = 0x03;

const ACK: [u8; 6] = [0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00];
const NACK: [u8; 6] = [0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00];
const TFI_HOST: u8 = 0xD4;
const TFI_CHIP: u8 = 0xD5;
const TFI_ERROR: u8 = 0x7F;

const CMD_DIAGNOSE: u8 = 0x00;
const CMD_GET_FIRMWARE_VERSION: u8 = 0x02;
const CMD_GET_GENERAL_STATUS: u8 = 0x04;
const CMD_READ_REGISTER: u8 = 0x06;
const CMD_WRITE_REGISTER: u8 = 0x08;
const CMD_SAM_CONFIGURATION: u8 = 0x14;
const CMD_POWER_DOWN: u8 = 0x16;
const CMD_RF_CONFIGURATION: u8 = 0x32;
const CMD_IN_DATA_EXCHANGE: u8 = 0x40;
const CMD_IN_COMMUNICATE_THRU: u8 = 0x42;
const CMD_IN_DESELECT: u8 = 0x44;
const CMD_IN_LIST_PASSIVE_TARGET: u8 = 0x4A;
const CMD_IN_RELEASE: u8 = 0x52;
const CMD_IN_SELECT: u8 = 0x54;

const IC: u8 = 0x32;
const WRITE_RETRIES: u32 = 10;
const ACK_TIMEOUT_MS: u32 = 15;
const ACK_ATTEMPTS: u32 = 3;
const PATTERN: [u8; 8] = *b"PERIPH01";

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// `GetFirmwareVersion` returned an IC other than `0x32`.
    InvalidChip(u8),
    /// No ACK frame after 3 attempts.
    NoAck,
    /// No response within the timeout (the command was aborted with an ACK frame).
    Timeout,
    /// Bad preamble, checksum, TFI, response code or length.
    Frame,
    /// The chip answered an application-level error frame.
    ErrorFrame,
    /// Non-zero `Status & 0x3F` (PN532 error code, e.g. `0x14` MIFARE authentication error).
    Status(u8),
    /// An argument is outside the supported range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// Response data (after the response code).
pub type Data = Vec<u8, MAX_DATA>;

/// `GetFirmwareVersion` result.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct FirmwareVersion {
    /// IC code, `0x32` for the PN532.
    pub ic: u8,
    /// Firmware version.
    pub version: u8,
    /// Firmware revision.
    pub revision: u8,
    /// Raw support byte.
    pub support: u8,
    /// Support bit 0: ISO/IEC 14443 A.
    pub iso14443a: bool,
    /// Support bit 1: ISO/IEC 14443 B.
    pub iso14443b: bool,
    /// Support bit 2: ISO/IEC 18092.
    pub iso18092: bool,
}

/// Decoded `GetGeneralStatus` (per-target entries are skipped).
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct GeneralStatus {
    /// Last error code.
    pub error: u8,
    /// External RF field present.
    pub field: bool,
    /// Number of active targets.
    pub targets: u8,
    /// SAM status byte.
    pub sam_status: u8,
}

/// A target found by `InListPassiveTarget`.
#[derive(Debug, PartialEq, Eq, Clone)]
pub struct Target {
    /// Logical target number.
    pub tg: u8,
    /// SENS_RES (ATQA), first byte high; 0 for FeliCa / type B.
    pub sens_res: u16,
    /// SEL_RES (SAK); 0 for FeliCa / type B.
    pub sel_res: u8,
    /// NFCID1 (type A), NFCID2 (FeliCa) or PUPI (type B).
    pub uid: Vec<u8, 10>,
    /// ATS (type A), PAD + system code (FeliCa) or ATTRIB_RES (type B); truncated to 32 bytes.
    pub ats: Vec<u8, 32>,
}

fn copy<const N: usize>(src: &[u8]) -> Vec<u8, N> {
    let mut v = Vec::new();
    for &b in src.iter().take(N) {
        let _ = v.push(b);
    }
    v
}

/// PN532 minimal driver — detect one ISO/IEC 14443 A card and return its UID.
pub struct Pn532Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Pn532Minimal<I2C> {
    /// Create a new `Pn532Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address ([`ADDRESS`], `0x24`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    /// Release the underlying I²C bus.
    pub fn release_bus(self) -> I2C {
        self.i2c
    }

    /// Wake the chip, check the firmware and configure SAM and retries.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let d = self.command(delay, CMD_GET_FIRMWARE_VERSION, &[], 1000)?;
        if d.len() < 4 || d[0] != IC {
            return Err(Error::InvalidChip(d.first().copied().unwrap_or(0)));
        }
        self.command(delay, CMD_SAM_CONFIGURATION, &[0x01, 0x14, 0x01], 1000)?;
        self.command(delay, CMD_RF_CONFIGURATION, &[0x05, 0xFF, 0x01, 0x02], 1000)?;
        Ok(())
    }

    /// Detect one ISO/IEC 14443 A card and return its UID (4, 7 or 10 bytes); the target is
    /// released. `None` when no card answered or no response arrived within `timeout_ms`
    /// (the command is then aborted).
    pub fn read_uid<D: DelayNs>(
        &mut self,
        delay: &mut D,
        timeout_ms: u32,
    ) -> Result<Option<Vec<u8, 10>>, Error<I2C::Error>> {
        let d = match self.list(delay, 1, BAUD_106_A, &[], timeout_ms)? {
            Some(d) => d,
            None => return Ok(None),
        };
        if d.len() < 6 || d[0] == 0 {
            return Ok(None);
        }
        let n = d[5] as usize;
        if n > 10 || 6 + n > d.len() {
            return Err(Error::Frame);
        }
        let uid = copy(&d[6..6 + n]);
        self.command(delay, CMD_IN_RELEASE, &[d[1]], 1000)?;
        Ok(Some(uid))
    }

    fn list<D: DelayNs>(
        &mut self,
        delay: &mut D,
        max_targets: u8,
        baud: u8,
        initiator_data: &[u8],
        timeout_ms: u32,
    ) -> Result<Option<Data>, Error<I2C::Error>> {
        if initiator_data.len() > MAX_DATA - 2 {
            return Err(Error::InvalidArgument);
        }
        let mut p: Vec<u8, MAX_DATA> = Vec::new();
        let _ = p.push(max_targets);
        let _ = p.push(baud);
        let _ = p.extend_from_slice(initiator_data);
        self.start(delay, CMD_IN_LIST_PASSIVE_TARGET, &p)?;
        match self.response(delay, CMD_IN_LIST_PASSIVE_TARGET, timeout_ms) {
            Ok(d) => Ok(Some(d)),
            Err(Error::Timeout) => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn write<D: DelayNs>(&mut self, delay: &mut D, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        let mut attempt = 0;
        loop {
            match self.i2c.write(self.addr, data) {
                Ok(()) => return Ok(()),
                Err(e) => {
                    attempt += 1;
                    if attempt >= WRITE_RETRIES {
                        return Err(Error::Bus(e));
                    }
                    delay.delay_ms(2);
                }
            }
        }
    }

    fn ready(&mut self) -> Result<bool, Error<I2C::Error>> {
        let mut s = [0u8; 1];
        self.i2c.read(self.addr, &mut s)?;
        Ok(s[0] & 0x01 != 0)
    }

    fn wait<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<bool, Error<I2C::Error>> {
        for _ in 0..=timeout_ms {
            if self.ready()? {
                return Ok(true);
            }
            delay.delay_ms(1);
        }
        Ok(false)
    }

    fn start<D: DelayNs>(&mut self, delay: &mut D, command: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        if data.len() > MAX_DATA {
            return Err(Error::InvalidArgument);
        }
        let mut frame = [0u8; MAX_DATA + 9];
        let len = data.len();
        let length = (len + 2) as u8;
        frame[..3].copy_from_slice(&[0x00, 0x00, 0xFF]);
        frame[3] = length;
        frame[4] = length.wrapping_neg();
        frame[5] = TFI_HOST;
        frame[6] = command;
        let mut sum = TFI_HOST.wrapping_add(command);
        for (i, &b) in data.iter().enumerate() {
            frame[7 + i] = b;
            sum = sum.wrapping_add(b);
        }
        frame[7 + len] = sum.wrapping_neg();
        frame[8 + len] = 0x00;
        for _ in 0..ACK_ATTEMPTS {
            self.write(delay, &frame[..len + 9])?;
            if self.wait(delay, ACK_TIMEOUT_MS)? {
                let mut ack = [0u8; 7];
                self.i2c.read(self.addr, &mut ack)?;
                if ack[1..7] == ACK {
                    return Ok(());
                }
            }
        }
        Err(Error::NoAck)
    }

    fn response<D: DelayNs>(&mut self, delay: &mut D, command: u8, timeout_ms: u32) -> Result<Data, Error<I2C::Error>> {
        if !self.wait(delay, timeout_ms)? {
            self.write(delay, &ACK)?; // abort the running command
            return Err(Error::Timeout);
        }
        let mut header = [0u8; 6];
        self.i2c.read(self.addr, &mut header)?;
        if header[1..4] != [0x00, 0x00, 0xFF] {
            return Err(Error::Frame);
        }
        let length = header[4] as usize;
        if (header[4].wrapping_add(header[5])) != 0 || length > MAX_DATA + 2 {
            return Err(Error::Frame);
        }
        self.write(delay, &NACK)?; // have the chip resend the whole frame
        if !self.wait(delay, timeout_ms)? {
            return Err(Error::Timeout);
        }
        let mut buf = [0u8; MAX_DATA + 10];
        let total = 1 + length + 7;
        self.i2c.read(self.addr, &mut buf[..total])?;
        let body = &buf[6..6 + length];
        if length == 1 && body[0] == TFI_ERROR {
            return Err(Error::ErrorFrame);
        }
        if length < 2 || body[0] != TFI_CHIP || body[1] != command.wrapping_add(1) {
            return Err(Error::Frame);
        }
        let sum = body.iter().fold(buf[6 + length], |a, &b| a.wrapping_add(b));
        if sum != 0 {
            return Err(Error::Frame);
        }
        Ok(copy(&body[2..]))
    }

    fn command<D: DelayNs>(
        &mut self,
        delay: &mut D,
        command: u8,
        data: &[u8],
        timeout_ms: u32,
    ) -> Result<Data, Error<I2C::Error>> {
        self.start(delay, command, data)?;
        self.response(delay, command, timeout_ms)
    }
}

/// PN532 full driver — target listing at every baud rate, data exchange, MIFARE Classic and
/// NTAG helpers, RF and retry configuration, register access, power-down, the self test, raw
/// commands and IRQ polling.
pub struct Pn532Full<I2C> {
    inner: Pn532Minimal<I2C>,
}

impl<I2C: I2c> Pn532Full<I2C> {
    /// Create a new `Pn532Full`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address ([`ADDRESS`], `0x24`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Pn532Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release_bus(self) -> I2C {
        self.inner.release_bus()
    }

    /// Wake the chip, check the firmware and configure SAM and retries (see [`Pn532Minimal::init`]).
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Detect one type A card and return its UID (see [`Pn532Minimal::read_uid`]).
    pub fn read_uid<D: DelayNs>(
        &mut self,
        delay: &mut D,
        timeout_ms: u32,
    ) -> Result<Option<Vec<u8, 10>>, Error<I2C::Error>> {
        self.inner.read_uid(delay, timeout_ms)
    }

    /// `GetFirmwareVersion`.
    pub fn firmware_version<D: DelayNs>(&mut self, delay: &mut D) -> Result<FirmwareVersion, Error<I2C::Error>> {
        let d = self.inner.command(delay, CMD_GET_FIRMWARE_VERSION, &[], 1000)?;
        if d.len() < 4 {
            return Err(Error::Frame);
        }
        Ok(FirmwareVersion {
            ic: d[0],
            version: d[1],
            revision: d[2],
            support: d[3],
            iso14443a: d[3] & 0x01 != 0,
            iso14443b: d[3] & 0x02 != 0,
            iso18092: d[3] & 0x04 != 0,
        })
    }

    /// `GetGeneralStatus`.
    pub fn general_status<D: DelayNs>(&mut self, delay: &mut D) -> Result<GeneralStatus, Error<I2C::Error>> {
        let d = self.inner.command(delay, CMD_GET_GENERAL_STATUS, &[], 1000)?;
        if d.len() < 3 {
            return Err(Error::Frame);
        }
        let sam = 3 + 4 * d[2] as usize;
        Ok(GeneralStatus {
            error: d[0],
            field: d[1] != 0,
            targets: d[2],
            sam_status: d.get(sam).copied().unwrap_or(0),
        })
    }

    /// `InListPassiveTarget`; an empty list when no target answered (or on a timeout, which
    /// aborts the command).
    ///
    /// # Arguments
    /// * `baud` — [`BAUD_106_A`], [`BAUD_212_F`], [`BAUD_424_F`] or [`BAUD_106_B`].
    /// * `max_targets` — 1 or 2.
    /// * `initiator_data` — optional InitiatorData (UID, FeliCa polling payload, AFI).
    /// * `timeout_ms` — response timeout.
    pub fn list_passive_targets<D: DelayNs>(
        &mut self,
        delay: &mut D,
        baud: u8,
        max_targets: u8,
        initiator_data: &[u8],
        timeout_ms: u32,
    ) -> Result<Vec<Target, 2>, Error<I2C::Error>> {
        if !(1..=2).contains(&max_targets) || baud > 4 {
            return Err(Error::InvalidArgument);
        }
        let mut targets: Vec<Target, 2> = Vec::new();
        let d = match self.inner.list(delay, max_targets, baud, initiator_data, timeout_ms)? {
            Some(d) => d,
            None => return Ok(targets),
        };
        if d.is_empty() {
            return Ok(targets);
        }
        let n = d.len();
        let mut i = 1usize;
        for _ in 0..d[0].min(max_targets) {
            if i >= n {
                break;
            }
            let tg = d[i];
            let t = if baud == BAUD_106_A {
                if i + 4 >= n {
                    return Err(Error::Frame);
                }
                let sens_res = ((d[i + 1] as u16) << 8) | d[i + 2] as u16;
                let sel_res = d[i + 3];
                let len = d[i + 4] as usize;
                if i + 5 + len > n {
                    return Err(Error::Frame);
                }
                let uid = copy(&d[i + 5..i + 5 + len]);
                i += 5 + len;
                let mut ats = Vec::new();
                if sel_res & 0x20 != 0 && i < n {
                    let tl = d[i] as usize;
                    ats = copy(&d[i..(i + tl).min(n)]);
                    i += tl;
                }
                Target { tg, sens_res, sel_res, uid, ats }
            } else if baud == BAUD_212_F || baud == BAUD_424_F {
                if i + 1 >= n {
                    return Err(Error::Frame);
                }
                let len = d[i + 1] as usize;
                if len < 10 || i + 1 + len > n {
                    return Err(Error::Frame);
                }
                let t = Target {
                    tg,
                    sens_res: 0,
                    sel_res: 0,
                    uid: copy(&d[i + 3..i + 11]),
                    ats: copy(&d[i + 11..i + 1 + len]),
                };
                i += 1 + len;
                t
            } else {
                if i + 13 >= n {
                    return Err(Error::Frame);
                }
                let len = d[i + 13] as usize;
                if i + 14 + len > n {
                    return Err(Error::Frame);
                }
                let t = Target {
                    tg,
                    sens_res: 0,
                    sel_res: 0,
                    uid: copy(&d[i + 2..i + 6]),
                    ats: copy(&d[i + 14..i + 14 + len]),
                };
                i += 14 + len;
                t
            };
            let _ = targets.push(t);
        }
        Ok(targets)
    }

    fn status_command<D: DelayNs>(&mut self, delay: &mut D, command: u8, data: &[u8]) -> Result<Data, Error<I2C::Error>> {
        let d = self.inner.command(delay, command, data, 1000)?;
        if d.is_empty() {
            return Err(Error::Frame);
        }
        let code = d[0] & 0x3F;
        if code != 0 {
            return Err(Error::Status(code));
        }
        Ok(copy(&d[1..]))
    }

    /// `InDataExchange` with an activated target; [`Error::Status`] on a non-zero status.
    pub fn data_exchange<D: DelayNs>(&mut self, delay: &mut D, target: u8, data: &[u8]) -> Result<Data, Error<I2C::Error>> {
        if data.len() > MAX_DATA - 1 {
            return Err(Error::InvalidArgument);
        }
        let mut p: Vec<u8, MAX_DATA> = Vec::new();
        let _ = p.push(target);
        let _ = p.extend_from_slice(data);
        self.status_command(delay, CMD_IN_DATA_EXCHANGE, &p)
    }

    /// `InCommunicateThru`: raw RF exchange with the current target.
    pub fn communicate_thru<D: DelayNs>(&mut self, delay: &mut D, data: &[u8]) -> Result<Data, Error<I2C::Error>> {
        self.status_command(delay, CMD_IN_COMMUNICATE_THRU, data)
    }

    /// `InRelease`; `target` 0 releases all.
    pub fn release<D: DelayNs>(&mut self, delay: &mut D, target: u8) -> Result<(), Error<I2C::Error>> {
        self.status_command(delay, CMD_IN_RELEASE, &[target]).map(|_| ())
    }

    /// `InSelect` a listed target.
    pub fn select<D: DelayNs>(&mut self, delay: &mut D, target: u8) -> Result<(), Error<I2C::Error>> {
        self.status_command(delay, CMD_IN_SELECT, &[target]).map(|_| ())
    }

    /// `InDeselect` (keeps the target's information); `target` 0 = all.
    pub fn deselect<D: DelayNs>(&mut self, delay: &mut D, target: u8) -> Result<(), Error<I2C::Error>> {
        self.status_command(delay, CMD_IN_DESELECT, &[target]).map(|_| ())
    }

    /// Authenticate a MIFARE Classic sector (`0x60` key A / `0x61` key B); the last 4 bytes of
    /// `uid` are used.
    pub fn mifare_authenticate<D: DelayNs>(
        &mut self,
        delay: &mut D,
        target: u8,
        block: u8,
        key: &[u8; 6],
        uid: &[u8],
        key_b: bool,
    ) -> Result<(), Error<I2C::Error>> {
        if uid.len() < 4 {
            return Err(Error::InvalidArgument);
        }
        let mut p = [0u8; 12];
        p[0] = if key_b { 0x61 } else { 0x60 };
        p[1] = block;
        p[2..8].copy_from_slice(key);
        p[8..12].copy_from_slice(&uid[uid.len() - 4..]);
        self.data_exchange(delay, target, &p).map(|_| ())
    }

    /// Read a 16-byte MIFARE Classic block (`0x30`).
    pub fn mifare_read_block<D: DelayNs>(&mut self, delay: &mut D, target: u8, block: u8) -> Result<[u8; 16], Error<I2C::Error>> {
        let d = self.data_exchange(delay, target, &[0x30, block])?;
        if d.len() < 16 {
            return Err(Error::Frame);
        }
        let mut out = [0u8; 16];
        out.copy_from_slice(&d[..16]);
        Ok(out)
    }

    /// Write a 16-byte MIFARE Classic block (`0xA0`).
    pub fn mifare_write_block<D: DelayNs>(
        &mut self,
        delay: &mut D,
        target: u8,
        block: u8,
        data: &[u8; 16],
    ) -> Result<(), Error<I2C::Error>> {
        let mut p = [0u8; 18];
        p[0] = 0xA0;
        p[1] = block;
        p[2..].copy_from_slice(data);
        self.data_exchange(delay, target, &p).map(|_| ())
    }

    /// Read four NTAG / Ultralight pages starting at `page` (`0x30`), 16 bytes.
    pub fn ntag_read_page<D: DelayNs>(&mut self, delay: &mut D, target: u8, page: u8) -> Result<[u8; 16], Error<I2C::Error>> {
        self.mifare_read_block(delay, target, page)
    }

    /// Write one 4-byte NTAG / Ultralight page (`0xA2`).
    pub fn ntag_write_page<D: DelayNs>(
        &mut self,
        delay: &mut D,
        target: u8,
        page: u8,
        data: &[u8; 4],
    ) -> Result<(), Error<I2C::Error>> {
        let p = [0xA2, page, data[0], data[1], data[2], data[3]];
        self.data_exchange(delay, target, &p).map(|_| ())
    }

    /// `RFConfiguration` item `0x01`: RF field on/off and auto RF collision avoidance.
    pub fn set_rf_field<D: DelayNs>(&mut self, delay: &mut D, on: bool, auto_rfca: bool) -> Result<(), Error<I2C::Error>> {
        let v = (if on { 0x02 } else { 0x00 }) | (if auto_rfca { 0x01 } else { 0x00 });
        self.inner.command(delay, CMD_RF_CONFIGURATION, &[0x01, v], 1000).map(|_| ())
    }

    /// `RFConfiguration` item `0x05`: retry counts, `0xFF` = infinite (defaults 2, `0xFF`, 1).
    pub fn set_max_retries<D: DelayNs>(
        &mut self,
        delay: &mut D,
        passive_activation: u8,
        atr: u8,
        psl: u8,
    ) -> Result<(), Error<I2C::Error>> {
        self.inner
            .command(delay, CMD_RF_CONFIGURATION, &[0x05, atr, psl, passive_activation], 1000)
            .map(|_| ())
    }

    /// `ReadRegister` (16-bit address).
    pub fn read_register<D: DelayNs>(&mut self, delay: &mut D, address: u16) -> Result<u8, Error<I2C::Error>> {
        let d = self.inner.command(delay, CMD_READ_REGISTER, &[(address >> 8) as u8, address as u8], 1000)?;
        d.first().copied().ok_or(Error::Frame)
    }

    /// `WriteRegister` (16-bit address).
    pub fn write_register<D: DelayNs>(&mut self, delay: &mut D, address: u16, value: u8) -> Result<(), Error<I2C::Error>> {
        self.inner
            .command(delay, CMD_WRITE_REGISTER, &[(address >> 8) as u8, address as u8, value], 1000)
            .map(|_| ())
    }

    /// `PowerDown` with `WakeUpEnable` (`0x20` = I²C); the next transaction wakes the chip.
    pub fn power_down<D: DelayNs>(&mut self, delay: &mut D, wakeup_sources: u8) -> Result<(), Error<I2C::Error>> {
        self.status_command(delay, CMD_POWER_DOWN, &[wakeup_sources]).map(|_| ())
    }

    /// `Diagnose` self test: `0x00` communication line (echo of an 8-byte pattern), `0x01` ROM,
    /// `0x02` RAM, … Returns `true` when the test passed.
    pub fn self_test<D: DelayNs>(&mut self, delay: &mut D, test: u8) -> Result<bool, Error<I2C::Error>> {
        if test == 0x00 {
            let mut req = [0u8; 9];
            req[1..].copy_from_slice(&PATTERN);
            let d = self.inner.command(delay, CMD_DIAGNOSE, &req, 1000)?;
            return Ok(d[..] == req[..]);
        }
        let d = self.inner.command(delay, CMD_DIAGNOSE, &[test], 1000)?;
        Ok(d.first() == Some(&0x00))
    }

    /// Send a raw command and return its response data (after the response code).
    pub fn send_command<D: DelayNs>(
        &mut self,
        delay: &mut D,
        command: u8,
        data: &[u8],
        timeout_ms: u32,
    ) -> Result<Data, Error<I2C::Error>> {
        self.inner.command(delay, command, data, timeout_ms)
    }

    /// Write a command frame and read its ACK, without waiting for the response.
    pub fn start_command<D: DelayNs>(&mut self, delay: &mut D, command: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        self.inner.start(delay, command, data)
    }

    /// Read the response to a command started with [`Self::start_command`]; a timeout aborts it.
    pub fn read_response<D: DelayNs>(&mut self, delay: &mut D, command: u8, timeout_ms: u32) -> Result<Data, Error<I2C::Error>> {
        self.inner.response(delay, command, timeout_ms)
    }

    /// Poll the status byte every 1 ms until a frame is ready.
    pub fn wait_ready<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<bool, Error<I2C::Error>> {
        self.inner.wait(delay, timeout_ms)
    }

    /// `true` when the status byte reports a ready ACK or response frame.
    ///
    /// The caller wires `IRQ` (active low) into an ISR or polling loop and calls this.
    pub fn poll_interrupt(&mut self) -> Result<bool, Error<I2C::Error>> {
        self.inner.ready()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};
    use std::vec;
    use std::vec::Vec as StdVec;

    const A: u8 = ADDRESS;

    fn frame(tfi: u8, data: &[u8]) -> StdVec<u8> {
        let mut body = vec![tfi];
        body.extend_from_slice(data);
        let sum = body.iter().fold(0u8, |a, &b| a.wrapping_add(b));
        let mut f = vec![0x00, 0x00, 0xFF, body.len() as u8, (body.len() as u8).wrapping_neg()];
        f.extend_from_slice(&body);
        f.push(sum.wrapping_neg());
        f.push(0x00);
        f
    }

    fn status(f: &[u8]) -> StdVec<u8> {
        let mut v = vec![0x01];
        v.extend_from_slice(f);
        v
    }

    /// Transactions of one command: frame, ACK, header, NACK, full response.
    fn exchange(cmd: u8, params: &[u8], resp: &[u8]) -> StdVec<T> {
        let mut data = vec![cmd];
        data.extend_from_slice(params);
        let mut r = vec![cmd + 1];
        r.extend_from_slice(resp);
        let rf = frame(0xD5, &r);
        vec![
            T::write(A, frame(0xD4, &data)),
            T::read(A, vec![0x01]),
            T::read(A, status(&ACK)),
            T::read(A, vec![0x01]),
            T::read(A, status(&rf[..5])),
            T::write(A, NACK.to_vec()),
            T::read(A, vec![0x01]),
            T::read(A, status(&rf)),
        ]
    }

    fn init_seq() -> StdVec<T> {
        let mut t = exchange(0x02, &[], &[0x32, 0x01, 0x06, 0x07]);
        t.extend(exchange(0x14, &[0x01, 0x14, 0x01], &[]));
        t.extend(exchange(0x32, &[0x05, 0xFF, 0x01, 0x02], &[]));
        t
    }

    #[test]
    fn frame_matches_datasheet_vector() {
        assert_eq!(frame(0xD4, &[0x02]), vec![0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00]);
    }

    #[test]
    fn init_and_read_uid() {
        let uid = [0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66];
        let mut t = init_seq();
        let mut resp = vec![1, 1, 0x00, 0x44, 0x00, 7];
        resp.extend_from_slice(&uid);
        t.extend(exchange(0x4A, &[0x01, 0x00], &resp));
        t.extend(exchange(0x52, &[0x01], &[0x00]));
        t.extend(exchange(0x4A, &[0x01, 0x00], &[0x00]));
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Minimal::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        chip.init(&mut d).unwrap();
        assert_eq!(chip.read_uid(&mut d, 1000).unwrap().unwrap().as_slice(), &uid);
        assert_eq!(chip.read_uid(&mut d, 1000).unwrap(), None);
        i2c.done();
    }

    #[test]
    fn read_uid_timeout_aborts() {
        let mut t = vec![
            T::write(A, frame(0xD4, &[0x4A, 0x01, 0x00])),
            T::read(A, vec![0x01]),
            T::read(A, status(&ACK)),
        ];
        for _ in 0..3 {
            t.push(T::read(A, vec![0x00]));
        }
        t.push(T::write(A, ACK.to_vec()));
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Minimal::new(i2c.clone(), A);
        assert_eq!(chip.read_uid(&mut NoopDelay::new(), 2).unwrap(), None);
        i2c.done();
    }

    #[test]
    fn bad_ic_and_error_frame() {
        let t = exchange(0x02, &[], &[0x31, 0x01, 0x06, 0x07]);
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Minimal::new(i2c.clone(), A);
        assert_eq!(chip.init(&mut NoopDelay::new()), Err(Error::InvalidChip(0x31)));
        i2c.done();

        let ef = [0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00];
        let t = vec![
            T::write(A, frame(0xD4, &[0x02])),
            T::read(A, vec![0x01]),
            T::read(A, status(&ACK)),
            T::read(A, vec![0x01]),
            T::read(A, status(&ef[..5])),
            T::write(A, NACK.to_vec()),
            T::read(A, vec![0x01]),
            T::read(A, status(&ef)),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Minimal::new(i2c.clone(), A);
        assert_eq!(chip.init(&mut NoopDelay::new()), Err(Error::ErrorFrame));
        i2c.done();
    }

    #[test]
    fn full_info_and_list() {
        let mut t = exchange(0x02, &[], &[0x32, 0x01, 0x06, 0x07]);
        t.extend(exchange(0x04, &[], &[0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01]));
        t.extend(exchange(
            0x4A,
            &[0x02, 0x00],
            &[2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02, 2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9],
        ));
        t.extend(exchange(
            0x4A,
            &[0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00],
            &[1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC],
        ));
        t.extend(exchange(0x4A, &[0x01, 0x03, 0x00], &[1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00]));
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        let fw = chip.firmware_version(&mut d).unwrap();
        assert_eq!((fw.ic, fw.version, fw.revision, fw.support), (0x32, 1, 6, 7));
        assert!(fw.iso14443a && fw.iso14443b && fw.iso18092);
        let gs = chip.general_status(&mut d).unwrap();
        assert_eq!(gs, GeneralStatus { error: 0, field: true, targets: 1, sam_status: 1 });
        let t2 = chip.list_passive_targets(&mut d, BAUD_106_A, 2, &[], 1000).unwrap();
        assert_eq!(t2.len(), 2);
        assert_eq!((t2[0].tg, t2[0].sens_res, t2[0].sel_res), (1, 0x0004, 0x20));
        assert_eq!(t2[0].uid.as_slice(), &[1, 2, 3, 4]);
        assert_eq!(t2[0].ats.as_slice(), &[5, 0x75, 0x77, 0x81, 0x02]);
        assert_eq!((t2[1].tg, t2[1].sens_res, t2[1].uid.len(), t2[1].ats.len()), (2, 0x0044, 7, 0));
        let f = chip.list_passive_targets(&mut d, BAUD_212_F, 1, &[0x00, 0xFF, 0xFF, 0x01, 0x00], 1000).unwrap();
        assert_eq!(f[0].uid.as_slice(), &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(f[0].ats.as_slice(), &[0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC]);
        let b = chip.list_passive_targets(&mut d, BAUD_106_B, 1, &[0x00], 1000).unwrap();
        assert_eq!(b[0].uid.as_slice(), &[0xA1, 0xA2, 0xA3, 0xA4]);
        assert_eq!(b[0].ats.as_slice(), &[0x00]);
        i2c.done();
    }

    #[test]
    fn full_tag_operations() {
        let block: StdVec<u8> = (4..20).collect();
        let mut rd = vec![0x00];
        rd.extend_from_slice(&block);
        let mut t = exchange(0x40, &[0x01, 0x30, 0x04], &rd);
        t.extend(exchange(0x40, &[0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x44, 0x55, 0x66], &[0x00]));
        let mut wr = vec![0x01, 0xA0, 0x04];
        wr.extend_from_slice(&[0u8; 16]);
        t.extend(exchange(0x40, &wr, &[0x00]));
        t.extend(exchange(0x40, &[0x01, 0xA2, 0x05, 1, 2, 3, 4], &[0x00]));
        t.extend(exchange(0x40, &[0x01, 0x30, 0x00], &[0x14]));
        t.extend(exchange(0x42, &[1, 2, 3], &[0x00, 3, 2, 1]));
        t.extend(exchange(0x52, &[0x00], &[0x00]));
        t.extend(exchange(0x54, &[0x01], &[0x00]));
        t.extend(exchange(0x44, &[0x01], &[0x00]));
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        assert_eq!(chip.mifare_read_block(&mut d, 1, 4).unwrap().as_slice(), block.as_slice());
        chip.mifare_authenticate(&mut d, 1, 7, &[0xFF; 6], &[0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66], false).unwrap();
        chip.mifare_write_block(&mut d, 1, 4, &[0u8; 16]).unwrap();
        chip.ntag_write_page(&mut d, 1, 5, &[1, 2, 3, 4]).unwrap();
        assert_eq!(chip.data_exchange(&mut d, 1, &[0x30, 0x00]), Err(Error::Status(0x14)));
        assert_eq!(chip.communicate_thru(&mut d, &[1, 2, 3]).unwrap().as_slice(), &[3, 2, 1]);
        chip.release(&mut d, 0).unwrap();
        chip.select(&mut d, 1).unwrap();
        chip.deselect(&mut d, 1).unwrap();
        i2c.done();
    }

    #[test]
    fn full_config_and_raw() {
        let mut t = exchange(0x32, &[0x01, 0x03], &[]);
        t.extend(exchange(0x32, &[0x05, 0xFF, 0x01, 0xFF], &[]));
        t.extend(exchange(0x08, &[0x63, 0x02, 0x5A], &[]));
        t.extend(exchange(0x06, &[0x63, 0x02], &[0x5A]));
        let mut echo = vec![0x00];
        echo.extend_from_slice(b"PERIPH01");
        t.extend(exchange(0x00, &echo, &echo));
        t.extend(exchange(0x00, &[0x01], &[0x00]));
        t.extend(exchange(0x16, &[0x20], &[0x00]));
        t.extend(exchange(0x02, &[], &[0x32, 0x01, 0x06, 0x07]));
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        chip.set_rf_field(&mut d, true, true).unwrap();
        chip.set_max_retries(&mut d, 0xFF, 0xFF, 0x01).unwrap();
        chip.write_register(&mut d, 0x6302, 0x5A).unwrap();
        assert_eq!(chip.read_register(&mut d, 0x6302).unwrap(), 0x5A);
        assert!(chip.self_test(&mut d, 0x00).unwrap());
        assert!(chip.self_test(&mut d, 0x01).unwrap());
        chip.power_down(&mut d, 0x20).unwrap();
        assert_eq!(chip.send_command(&mut d, 0x02, &[], 1000).unwrap().as_slice(), &[0x32, 0x01, 0x06, 0x07]);
        i2c.done();
    }

    #[test]
    fn start_poll_read_response() {
        let all = exchange(0x02, &[], &[0x32, 0x01, 0x06, 0x07]);
        let mut t: StdVec<T> = all[..3].to_vec();
        t.push(T::read(A, vec![0x01])); // wait_ready
        t.push(T::read(A, vec![0x01])); // poll_interrupt
        t.extend_from_slice(&all[3..]);
        t.push(T::read(A, vec![0x00])); // poll_interrupt idle
        let mut i2c = I2cMock::new(&t);
        let mut chip = Pn532Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        chip.start_command(&mut d, 0x02, &[]).unwrap();
        assert!(chip.wait_ready(&mut d, 10).unwrap());
        assert!(chip.poll_interrupt().unwrap());
        assert_eq!(chip.read_response(&mut d, 0x02, 1000).unwrap().as_slice(), &[0x32, 0x01, 0x06, 0x07]);
        assert!(!chip.poll_interrupt().unwrap());
        i2c.done();
    }
}
