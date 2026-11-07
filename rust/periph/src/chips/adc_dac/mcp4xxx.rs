//! MCP4XXX — MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X I²C digital
//! potentiometers (Microchip).
//!
//! One driver for the whole sixteen-part family of 7-bit (129 steps) and 8-bit (257 steps)
//! digital potentiometers (X1) and rheostats (X2), one or two networks, volatile (453X / 455X /
//! 463X / 465X) or non-volatile (454X / 456X / 464X / 466X). A [`Part`] selects a row of the
//! part descriptor table ([`PartInfo`]): channel count, resolution, pot / rheostat pinout,
//! memory type and I²C address range. Everything else is one code path.
//!
//! The chip has no flat register map: a transaction starts with a command byte
//! `AD3 AD2 AD1 AD0 C1 C0 D9 D8` (memory address, operation, data bits 9:8) that plays the role
//! of the register address; a read returns two bytes (`0000 000D8`, `D7..D0`).
//!
//! The non-volatile operations of [`Mcp4xxxFull`] return [`Error::Unsupported`] on the volatile
//! parts. Operations that wait for an EEPROM write cycle take a [`DelayNs`] source.

use core::ops::{Deref, DerefMut};

use embedded_hal::delay::DelayNs;
use embedded_hal::digital::{ErrorType, OutputPin};
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// Driver error: an I²C bus error or an argument / part-capability violation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error<E> {
    /// The underlying I²C transaction failed.
    I2c(E),
    /// A channel, address or index is out of range for the selected part.
    InvalidArgument,
    /// The operation does not exist on the selected part (EEPROM on a volatile part, W–A on a
    /// rheostat, a high-voltage command without an `hvc` pin, `set_both` on a single network).
    Unsupported,
    /// The EEPROM write cycle did not finish within the timeout.
    Timeout,
    /// The `hvc` pin could not be driven.
    Pin,
}

/// The sixteen supported parts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Part {
    Mcp4531, Mcp4532, Mcp4551, Mcp4552, Mcp4631, Mcp4632, Mcp4651, Mcp4652,
    Mcp4541, Mcp4542, Mcp4561, Mcp4562, Mcp4641, Mcp4642, Mcp4661, Mcp4662,
}

/// Part descriptor: one row of the family table.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartInfo {
    /// Part name, e.g. `"MCP4532"`.
    pub name: &'static str,
    /// Number of resistor networks (1 or 2).
    pub channels: u8,
    /// Maximum wiper code: 128 (7-bit) or 256 (8-bit).
    pub max_code: u16,
    /// `true` for the X1 parts (terminal A on a pin).
    pub potentiometer: bool,
    /// `true` for the 454X / 456X / 464X / 466X parts (EEPROM).
    pub nonvolatile: bool,
    /// `true` for the MCP4641 / MCP4661 (hardware `WP` pin).
    pub wp_pin: bool,
    /// Lowest 7-bit I²C address (all address pins low).
    pub addr_min: u8,
    /// Highest 7-bit I²C address.
    pub addr_max: u8,
}

const fn info(name: &'static str, channels: u8, max_code: u16, potentiometer: bool, nonvolatile: bool, wp_pin: bool, addr_min: u8, addr_max: u8) -> PartInfo {
    PartInfo { name, channels, max_code, potentiometer, nonvolatile, wp_pin, addr_min, addr_max }
}

const TABLE: [PartInfo; 16] = [
    info("MCP4531", 1, 128, true, false, false, 0x2E, 0x2F),
    info("MCP4532", 1, 128, false, false, false, 0x58, 0x5B),
    info("MCP4551", 1, 256, true, false, false, 0x2E, 0x2F),
    info("MCP4552", 1, 256, false, false, false, 0x58, 0x5B),
    info("MCP4631", 2, 128, true, false, false, 0x28, 0x2F),
    info("MCP4632", 2, 128, false, false, false, 0x58, 0x5B),
    info("MCP4651", 2, 256, true, false, false, 0x28, 0x2F),
    info("MCP4652", 2, 256, false, false, false, 0x58, 0x5B),
    info("MCP4541", 1, 128, true, true, false, 0x2E, 0x2F),
    info("MCP4542", 1, 128, false, true, false, 0x58, 0x5B),
    info("MCP4561", 1, 256, true, true, false, 0x2E, 0x2F),
    info("MCP4562", 1, 256, false, true, false, 0x58, 0x5B),
    info("MCP4641", 2, 128, true, true, true, 0x28, 0x2F),
    info("MCP4642", 2, 128, false, true, false, 0x58, 0x5B),
    info("MCP4661", 2, 256, true, true, true, 0x28, 0x2F),
    info("MCP4662", 2, 256, false, true, false, 0x58, 0x5B),
];

impl Part {
    /// All sixteen parts in table order.
    pub const ALL: [Part; 16] = [
        Part::Mcp4531, Part::Mcp4532, Part::Mcp4551, Part::Mcp4552, Part::Mcp4631, Part::Mcp4632,
        Part::Mcp4651, Part::Mcp4652, Part::Mcp4541, Part::Mcp4542, Part::Mcp4561, Part::Mcp4562,
        Part::Mcp4641, Part::Mcp4642, Part::Mcp4661, Part::Mcp4662,
    ];

    /// The descriptor row of this part.
    pub fn info(self) -> PartInfo {
        TABLE[self as usize]
    }

    /// Look a part up by name, e.g. `"mcp4532"` (ASCII case-insensitive).
    pub fn from_name(name: &str) -> Option<Part> {
        Part::ALL.iter().copied().find(|p| p.info().name.eq_ignore_ascii_case(name))
    }

    /// Default 7-bit I²C address (all address pins low): `0x2E` (45X1), `0x58` (45X2 / 46X2) or `0x28` (46X1).
    pub fn default_address(self) -> u8 {
        self.info().addr_min
    }
}

/// Decoded `STATUS` register of the non-volatile parts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Status {
    /// An EEPROM write cycle is in progress (`EEWA`).
    pub eeprom_busy: bool,
    /// WiperLock state of network 0 and 1 (index 1 is `false` on single-network parts).
    pub wiper_lock: [bool; 2],
    /// EEPROM write protect (hardware `WP` pin OR the nonvolatile bit).
    pub write_protect: bool,
}

/// Placeholder `hvc` pin type for drivers built without a high-voltage control pin.
#[derive(Debug, Clone, Copy, Default)]
pub struct NoHvc;

impl ErrorType for NoHvc {
    type Error = core::convert::Infallible;
}

impl OutputPin for NoHvc {
    fn set_low(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
    fn set_high(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

const ADDR_WIPER0: u8 = 0x00;
const ADDR_NVWIPER0: u8 = 0x02;
const ADDR_TCON: u8 = 0x04;
const ADDR_STATUS: u8 = 0x05;
const ADDR_DATA0: u8 = 0x06;
const ADDR_WP: u8 = 0x0F;

const OP_WRITE: u8 = 0x00;
const OP_INC: u8 = 0x04;
const OP_DEC: u8 = 0x08;
const OP_READ: u8 = 0x0C;

const TCON_DEFAULT: u16 = 0x1FF;
const TCON_GCEN: u8 = 8;
/// Per-channel TCON bit positions `[HW, A, W, B]`.
const TCON_BITS: [[u8; 4]; 2] = [[3, 2, 1, 0], [7, 6, 5, 4]];

const STATUS_EEWA: u16 = 0x08;
const STATUS_WL: [u16; 2] = [0x02, 0x04];
const STATUS_WP: u16 = 0x01;

/// Longest run of increment / decrement commands sent in one continuous transaction.
const MAX_RUN: usize = 32;

/// Round half away from zero (`f32::round` is unavailable in `no_std`).
fn round_f32(x: f32) -> f32 {
    if x >= 0.0 { (x + 0.5) as i32 as f32 } else { (x - 0.5) as i32 as f32 }
}

/// MCP4XXX minimal driver — set and read back the volatile wiper or the W–B resistance.
///
/// Defaults: RAB = 10 kΩ, RW = 75 Ω; the wipers keep their power-up value until written.
/// Construction performs no I²C traffic ([`init`](Self::init) does the bus check).
pub struct Mcp4xxxMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    part: Part,
    desc: PartInfo,
    r_ab: f32,
    r_wiper: f32,
}

impl<I2C: I2c> Mcp4xxxMinimal<I2C> {
    /// Create a new `Mcp4xxxMinimal`; no I²C traffic.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit device address; panics if outside the part's range (see [`Part::info`]).
    /// * `part` — the family member.
    /// * `r_ab` — end-to-end resistance RAB in ohms (5000, 10000, 50000 or 100000).
    /// * `r_wiper` — wiper resistance RW in ohms (typically 75).
    pub fn new(i2c: I2C, addr: u8, part: Part, r_ab: f32, r_wiper: f32) -> Self {
        let desc = part.info();
        assert!(addr >= desc.addr_min && addr <= desc.addr_max, "MCP4XXX: address outside the part's range");
        Self { i2c, addr, part, desc, r_ab, r_wiper }
    }

    /// Bus check: reads the wiper register(s), `TCON` (and `STATUS` on non-volatile parts); no writes.
    pub fn init(&mut self) -> Result<(), Error<I2C::Error>> {
        for channel in 0..self.desc.channels {
            self.read_wiper(channel)?;
        }
        self.read9(ADDR_TCON)?;
        if self.desc.nonvolatile {
            self.read9(ADDR_STATUS)?;
        }
        Ok(())
    }

    /// The selected part.
    pub fn part(&self) -> Part {
        self.part
    }

    fn check_channel(&self, channel: u8) -> Result<(), Error<I2C::Error>> {
        if channel < self.desc.channels { Ok(()) } else { Err(Error::InvalidArgument) }
    }

    fn read9(&mut self, address: u8) -> Result<u16, Error<I2C::Error>> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, ((address << 4) | OP_READ) as u32, 1, &mut buf)
            .map_err(Error::I2c)?;
        Ok((((buf[0] & 0x01) as u16) << 8) | buf[1] as u16)
    }

    fn write9(&mut self, address: u8, value: u16) -> Result<(), Error<I2C::Error>> {
        let command = (address << 4) | OP_WRITE | ((value >> 8) & 0x01) as u8;
        register::write_register(&mut self.i2c, self.addr, command as u32, 1, &[value as u8]).map_err(Error::I2c)
    }

    fn read_wiper(&mut self, channel: u8) -> Result<u16, Error<I2C::Error>> {
        Ok(self.read9(ADDR_WIPER0 + channel)?.min(self.desc.max_code))
    }

    fn clamp(&self, code: i32) -> u16 {
        code.clamp(0, self.desc.max_code as i32) as u16
    }

    /// Set the volatile wiper of a network (clamped to `0..=max_code`; a WiperLock-ed network ignores it).
    pub fn set_wiper(&mut self, code: i32, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.check_channel(channel)?;
        let code = self.clamp(code);
        self.write9(ADDR_WIPER0 + channel, code)
    }

    /// Read the volatile wiper of a network (`0..=max_code`).
    pub fn get_wiper(&mut self, channel: u8) -> Result<u16, Error<I2C::Error>> {
        self.check_channel(channel)?;
        self.read_wiper(channel)
    }

    /// Set the W–B resistance (ohms) to the nearest reachable value.
    pub fn set_resistance(&mut self, ohms: f32, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.check_channel(channel)?;
        let code = round_f32((ohms - self.r_wiper) / self.r_ab * self.desc.max_code as f32);
        let code = if code < 0.0 { 0 } else if code > self.desc.max_code as f32 { self.desc.max_code as i32 } else { code as i32 };
        self.set_wiper(code, channel)
    }

    /// Read the W–B resistance in ohms: `N / max_code * RAB + RW`.
    pub fn get_resistance(&mut self, channel: u8) -> Result<f32, Error<I2C::Error>> {
        let code = self.get_wiper(channel)?;
        Ok(code as f32 / self.desc.max_code as f32 * self.r_ab + self.r_wiper)
    }
}

/// MCP4XXX full driver — extends [`Mcp4xxxMinimal`] (via `Deref`) with ratio helpers,
/// increment / decrement, shutdown and terminal control, General Call, reset and raw access, and —
/// on the non-volatile parts only — the EEPROM wiper, the ten general-purpose EEPROM registers,
/// `STATUS`, WiperLock and write protect.
///
/// `P` is the optional `hvc` pin type switching the external circuit that drives `HVC/A0` to
/// 8.5–12.5 V (see [`with_hvc`](Mcp4xxxFull::with_hvc)); [`NoHvc`] when none is wired.
pub struct Mcp4xxxFull<I2C, P = NoHvc> {
    inner: Mcp4xxxMinimal<I2C>,
    hvc: Option<P>,
    hvc_settle_ms: u32,
}

impl<I2C, P> Deref for Mcp4xxxFull<I2C, P> {
    type Target = Mcp4xxxMinimal<I2C>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<I2C, P> DerefMut for Mcp4xxxFull<I2C, P> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl<I2C: I2c> Mcp4xxxFull<I2C, NoHvc> {
    /// Create a new `Mcp4xxxFull` without a high-voltage control pin; same arguments as [`Mcp4xxxMinimal::new`].
    pub fn new(i2c: I2C, addr: u8, part: Part, r_ab: f32, r_wiper: f32) -> Self {
        Self { inner: Mcp4xxxMinimal::new(i2c, addr, part, r_ab, r_wiper), hvc: None, hvc_settle_ms: 1 }
    }
}

impl<I2C: I2c, P: OutputPin> Mcp4xxxFull<I2C, P> {
    /// Create a new `Mcp4xxxFull` with an `hvc` pin (needed only by `set_wiper_lock` /
    /// `set_write_protect`); `hvc_settle_ms` is the delay between asserting `hvc` and the first
    /// SCL edge (the datasheet gives no value; 1 ms is a good default).
    pub fn with_hvc(i2c: I2C, addr: u8, part: Part, r_ab: f32, r_wiper: f32, hvc: P, hvc_settle_ms: u32) -> Self {
        Self { inner: Mcp4xxxMinimal::new(i2c, addr, part, r_ab, r_wiper), hvc: Some(hvc), hvc_settle_ms }
    }

    fn require_nv(&self) -> Result<(), Error<I2C::Error>> {
        if self.inner.desc.nonvolatile { Ok(()) } else { Err(Error::Unsupported) }
    }

    fn require_pot(&self) -> Result<(), Error<I2C::Error>> {
        if self.inner.desc.potentiometer { Ok(()) } else { Err(Error::Unsupported) }
    }

    /// Number of resistor networks (1 or 2).
    pub fn channels(&self) -> u8 {
        self.inner.desc.channels
    }

    /// Number of wiper positions (129 or 257).
    pub fn steps(&self) -> u16 {
        self.inner.desc.max_code + 1
    }

    /// `true` for the X1 parts (terminal A on a pin).
    pub fn is_potentiometer(&self) -> bool {
        self.inner.desc.potentiometer
    }

    /// `true` for the 454X / 456X / 464X / 466X parts.
    pub fn is_nonvolatile(&self) -> bool {
        self.inner.desc.nonvolatile
    }

    /// Set the wiper to `round(ratio × max_code)` (ratio 0.0–1.0).
    pub fn set_ratio(&mut self, ratio: f32, channel: u8) -> Result<(), Error<I2C::Error>> {
        let r = ratio.clamp(0.0, 1.0);
        let code = round_f32(r * self.inner.desc.max_code as f32) as i32;
        self.inner.set_wiper(code, channel)
    }

    /// Read the wiper position as a ratio: `code / max_code`.
    pub fn get_ratio(&mut self, channel: u8) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.get_wiper(channel)? as f32 / self.inner.desc.max_code as f32)
    }

    /// W–A resistance `(max_code − N) / max_code × RAB + RW` (potentiometer parts only).
    pub fn get_resistance_wa(&mut self, channel: u8) -> Result<f32, Error<I2C::Error>> {
        self.require_pot()?;
        let max = self.inner.desc.max_code as f32;
        let code = self.inner.get_wiper(channel)? as f32;
        Ok((max - code) / max * self.inner.r_ab + self.inner.r_wiper)
    }

    /// Set the W–A resistance to the nearest reachable value (potentiometer parts only).
    pub fn set_resistance_wa(&mut self, ohms: f32, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.require_pot()?;
        self.inner.check_channel(channel)?;
        let max = self.inner.desc.max_code as i32;
        let n = round_f32((ohms - self.inner.r_wiper) / self.inner.r_ab * max as f32) as i32;
        self.inner.set_wiper(max - n, channel)
    }

    /// Resistance of one step, `RAB / max_code`, in ohms.
    pub fn get_resistance_step(&self) -> f32 {
        self.inner.r_ab / self.inner.desc.max_code as f32
    }

    /// Write both wipers of a dual part (two transactions); single parts return [`Error::Unsupported`].
    pub fn set_both(&mut self, code0: i32, code1: i32) -> Result<(), Error<I2C::Error>> {
        if self.inner.desc.channels < 2 {
            return Err(Error::Unsupported);
        }
        self.inner.set_wiper(code0, 0)?;
        self.inner.set_wiper(code1, 1)
    }

    fn step(&mut self, op: u8, steps: u32, channel: u8) -> Result<u16, Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        let command = ((ADDR_WIPER0 + channel) << 4) | op;
        let mut left = steps;
        while left > 0 {
            // Continuous mode: one command byte per step, no data bytes, one transaction per run.
            let n = (left as usize).min(MAX_RUN);
            let buf = [command; MAX_RUN];
            self.inner.i2c.write(self.inner.addr, &buf[..n]).map_err(Error::I2c)?;
            left -= n as u32;
        }
        self.inner.get_wiper(channel)
    }

    /// Increment the wiper by `steps` (continuous transactions, saturating at max code); returns the new code (read back).
    pub fn increment(&mut self, steps: u32, channel: u8) -> Result<u16, Error<I2C::Error>> {
        self.step(OP_INC, steps, channel)
    }

    /// Decrement the wiper by `steps` (saturating at 0); returns the new code (read back).
    pub fn decrement(&mut self, steps: u32, channel: u8) -> Result<u16, Error<I2C::Error>> {
        self.step(OP_DEC, steps, channel)
    }

    fn modify_tcon(&mut self, changes: &[(u8, bool)]) -> Result<(), Error<I2C::Error>> {
        let mut value = self.inner.read9(ADDR_TCON)?;
        for &(bit, on) in changes {
            if on { value |= 1 << bit } else { value &= !(1 << bit) }
        }
        self.inner.write9(ADDR_TCON, value & 0x1FF)
    }

    /// Shut a network down (`TCON.RxHW` = 0): A disconnected, W connected to B; the wiper is kept.
    pub fn shutdown(&mut self, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        self.modify_tcon(&[(TCON_BITS[channel as usize][0], false)])
    }

    /// Leave shutdown (`TCON.RxHW` = 1).
    pub fn resume(&mut self, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        self.modify_tcon(&[(TCON_BITS[channel as usize][0], true)])
    }

    /// `true` if the network is in shutdown (read from `TCON`).
    pub fn is_shutdown(&mut self, channel: u8) -> Result<bool, Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        Ok((self.inner.read9(ADDR_TCON)? >> TCON_BITS[channel as usize][0]) & 1 == 0)
    }

    /// Connect or disconnect the A, W and B terminals of a network (`TCON.RxA` / `RxW` / `RxB`).
    pub fn set_terminals(&mut self, a: bool, w: bool, b: bool, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        let bits = TCON_BITS[channel as usize];
        self.modify_tcon(&[(bits[1], a), (bits[2], w), (bits[3], b)])
    }

    /// Read the `(a, w, b)` terminal connection state of a network from `TCON`.
    pub fn get_terminals(&mut self, channel: u8) -> Result<(bool, bool, bool), Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        let tcon = self.inner.read9(ADDR_TCON)?;
        let bits = TCON_BITS[channel as usize];
        Ok(((tcon >> bits[1]) & 1 == 1, (tcon >> bits[2]) & 1 == 1, (tcon >> bits[3]) & 1 == 1))
    }

    /// Raw 9-bit `TCON` register.
    pub fn get_tcon(&mut self) -> Result<u16, Error<I2C::Error>> {
        self.inner.read9(ADDR_TCON)
    }

    /// Enable or disable the General Call address (`TCON.GCEN`).
    pub fn set_general_call(&mut self, enabled: bool) -> Result<(), Error<I2C::Error>> {
        self.modify_tcon(&[(TCON_GCEN, enabled)])
    }

    /// Emulate a power-on reset: write every volatile wiper with its power-up value (mid-scale, or
    /// the EEPROM wiper on non-volatile parts) and `TCON` `0x1FF`; networks locked by WiperLock are skipped.
    pub fn reset(&mut self) -> Result<(), Error<I2C::Error>> {
        let locked = if self.inner.desc.nonvolatile { self.read_status()?.wiper_lock } else { [false; 2] };
        let mut any_locked = false;
        for channel in 0..self.inner.desc.channels {
            if locked[channel as usize] {
                any_locked = true;
                continue;
            }
            let code = if self.inner.desc.nonvolatile { self.get_nv_wiper(channel)? } else { self.inner.desc.max_code / 2 };
            self.inner.set_wiper(code as i32, channel)?;
        }
        if !any_locked {
            self.inner.write9(ADDR_TCON, TCON_DEFAULT)?;
        }
        Ok(())
    }

    fn check_address(&self, address: u8, writable: bool) -> Result<(), Error<I2C::Error>> {
        let d = &self.inner.desc;
        let mut ok = address == ADDR_TCON || address < d.channels;
        if d.nonvolatile {
            ok = ok || ((address == 2 || address == 3) && address - 2 < d.channels) || (ADDR_DATA0..=0x0F).contains(&address);
            if address == ADDR_STATUS {
                ok = !writable;
            }
        }
        if address > 0x0F || !ok { Err(Error::InvalidArgument) } else { Ok(()) }
    }

    /// Raw 9-bit read of a memory address the part has.
    pub fn read_register(&mut self, address: u8) -> Result<u16, Error<I2C::Error>> {
        self.check_address(address, false)?;
        self.inner.read9(address)
    }

    /// Raw write of a writable memory address the part has (`0..=0x3FF` for wipers, `0..=0x1FF` otherwise).
    pub fn write_register(&mut self, address: u8, value: u16) -> Result<(), Error<I2C::Error>> {
        self.check_address(address, true)?;
        let limit = if address < 2 { 0x3FF } else { 0x1FF };
        self.inner.write9(address, value.min(limit))
    }

    /// Change the resistance model used by the conversions (no I²C traffic).
    pub fn set_rab(&mut self, r_ab: f32, r_wiper: Option<f32>) {
        self.inner.r_ab = r_ab;
        if let Some(rw) = r_wiper {
            self.inner.r_wiper = rw;
        }
    }

    /// **NV.** Read and decode `STATUS`.
    pub fn read_status(&mut self) -> Result<Status, Error<I2C::Error>> {
        self.require_nv()?;
        let v = self.inner.read9(ADDR_STATUS)?;
        Ok(Status {
            eeprom_busy: v & STATUS_EEWA != 0,
            wiper_lock: [v & STATUS_WL[0] != 0, self.inner.desc.channels > 1 && v & STATUS_WL[1] != 0],
            write_protect: v & STATUS_WP != 0,
        })
    }

    /// **NV.** Poll `STATUS.EEWA` until the EEPROM write cycle has finished (tWC is 5 ms typical,
    /// 10 ms maximum); [`Error::Timeout`] after `timeout_ms`.
    pub fn wait_ready<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<(), Error<I2C::Error>> {
        self.require_nv()?;
        for _ in 0..=timeout_ms {
            if self.inner.read9(ADDR_STATUS)? & STATUS_EEWA == 0 {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    /// **NV.** Read the EEPROM wiper (power-up value), clamped to the max code.
    pub fn get_nv_wiper(&mut self, channel: u8) -> Result<u16, Error<I2C::Error>> {
        self.require_nv()?;
        self.inner.check_channel(channel)?;
        Ok(self.inner.read9(ADDR_NVWIPER0 + channel)?.min(self.inner.desc.max_code))
    }

    /// **NV.** Write the EEPROM wiper (the volatile wiper is not changed) and wait for the write cycle.
    pub fn set_nv_wiper<D: DelayNs>(&mut self, delay: &mut D, code: i32, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.require_nv()?;
        self.inner.check_channel(channel)?;
        let code = self.inner.clamp(code);
        self.inner.write9(ADDR_NVWIPER0 + channel, code)?;
        self.wait_ready(delay, 20)
    }

    /// **NV.** Write the current volatile wiper to the EEPROM wiper; returns the stored code.
    pub fn store_wiper<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<u16, Error<I2C::Error>> {
        self.require_nv()?;
        let code = self.inner.get_wiper(channel)?;
        self.set_nv_wiper(delay, code as i32, channel)?;
        Ok(code)
    }

    /// **NV.** Read general-purpose EEPROM register `index` (0–9).
    pub fn read_data(&mut self, index: u8) -> Result<u16, Error<I2C::Error>> {
        self.require_nv()?;
        if index > 9 {
            return Err(Error::InvalidArgument);
        }
        self.inner.read9(ADDR_DATA0 + index)
    }

    /// **NV.** Write general-purpose EEPROM register `index` (0–9, value `0..=0x1FF`; uses one write cycle).
    pub fn write_data<D: DelayNs>(&mut self, delay: &mut D, index: u8, value: u16) -> Result<(), Error<I2C::Error>> {
        self.require_nv()?;
        if index > 9 {
            return Err(Error::InvalidArgument);
        }
        self.inner.write9(ADDR_DATA0 + index, value.min(0x1FF))?;
        self.wait_ready(delay, 20)
    }

    /// **NV.** `STATUS.WLx` of a network.
    pub fn is_wiper_locked(&mut self, channel: u8) -> Result<bool, Error<I2C::Error>> {
        self.inner.check_channel(channel)?;
        Ok(self.read_status()?.wiper_lock[channel as usize])
    }

    /// **NV.** `STATUS.WP` (hardware `WP` pin OR the nonvolatile bit).
    pub fn is_write_protected(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.read_status()?.write_protect)
    }

    fn hv_command<D: DelayNs>(&mut self, delay: &mut D, address: u8, decrement: bool) -> Result<(), Error<I2C::Error>> {
        let command = (address << 4) | if decrement { OP_DEC } else { OP_INC };
        let settle = self.hvc_settle_ms;
        let pin = self.hvc.as_mut().ok_or(Error::Unsupported)?;
        pin.set_high().map_err(|_| Error::Pin)?;
        delay.delay_ms(settle);
        let result = register::write_register(&mut self.inner.i2c, self.inner.addr, command as u32, 1, &[]).map_err(Error::I2c);
        let pin = self.hvc.as_mut().ok_or(Error::Unsupported)?;
        let released = pin.set_low().map_err(|_| Error::Pin);
        result?;
        released?;
        self.wait_ready(delay, 20)
    }

    /// **NV, needs the `hvc` pin.** Enable (decrement) or disable (increment) WiperLock of a network
    /// with a high-voltage command; waits for the EEPROM write cycle that stores the bit.
    pub fn set_wiper_lock<D: DelayNs>(&mut self, delay: &mut D, locked: bool, channel: u8) -> Result<(), Error<I2C::Error>> {
        self.require_nv()?;
        self.inner.check_channel(channel)?;
        self.hv_command(delay, ADDR_NVWIPER0 + channel, locked)
    }

    /// **NV, needs the `hvc` pin.** Set or clear the nonvolatile EEPROM write-protect bit with a
    /// high-voltage command; the hardware `WP` pin (MCP4641 / MCP4661) can still block writes.
    pub fn set_write_protect<D: DelayNs>(&mut self, delay: &mut D, enabled: bool) -> Result<(), Error<I2C::Error>> {
        self.require_nv()?;
        self.hv_command(delay, ADDR_WP, enabled)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::digital::{Mock as PinMock, State, Transaction as PinT};
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    fn rd(cmd: u8, value: u16) -> T {
        T::write_read(0x58, vec![cmd], vec![(value >> 8) as u8 & 1, value as u8])
    }
    fn wr(bytes: &[u8]) -> T {
        T::write(0x58, bytes.to_vec())
    }
    fn mk(part: Part, addr: u8, exp: &[T]) -> (I2cMock, Mcp4xxxFull<I2cMock>) {
        let i2c = I2cMock::new(exp);
        (i2c.clone(), Mcp4xxxFull::new(i2c, addr, part, 10000.0, 75.0))
    }

    #[test]
    fn part_table() {
        assert_eq!(Part::ALL.len(), 16);
        assert_eq!(Part::from_name("mcp4532"), Some(Part::Mcp4532));
        assert_eq!(Part::from_name("MCP9999"), None);
        assert_eq!(Part::Mcp4531.default_address(), 0x2E);
        assert_eq!(Part::Mcp4632.default_address(), 0x58);
        assert_eq!(Part::Mcp4661.default_address(), 0x28);
        for p in Part::ALL {
            let i = p.info();
            assert_eq!(i.nonvolatile, matches!(p, Part::Mcp4541 | Part::Mcp4542 | Part::Mcp4561 | Part::Mcp4562 | Part::Mcp4641 | Part::Mcp4642 | Part::Mcp4661 | Part::Mcp4662));
        }
    }

    #[test]
    #[should_panic]
    fn address_outside_range_panics() {
        let _ = Mcp4xxxMinimal::new(I2cMock::new(&[]), 0x20, Part::Mcp4532, 10000.0, 75.0);
    }

    #[test]
    fn init_reads_only() {
        let exp = [rd(0x0C, 64), rd(0x1C, 64), rd(0x4C, 0x1FF), rd(0x5C, 0x1F0)];
        let (mut i2c, mut chip) = mk(Part::Mcp4662, 0x58, &exp);
        chip.init().unwrap();
        assert_eq!(chip.channels(), 2);
        assert_eq!(chip.steps(), 257);
        assert!(!chip.is_potentiometer() && chip.is_nonvolatile());
        i2c.done();
    }

    #[test]
    fn wiper_formats_and_clamps() {
        let exp = [
            wr(&[0x00, 100]), wr(&[0x00, 128]), wr(&[0x00, 0]),
            rd(0x0C, 100), wr(&[0x00, 64]), wr(&[0x00, 128]),
        ];
        let (mut i2c, mut chip) = mk(Part::Mcp4532, 0x58, &exp);
        chip.set_wiper(100, 0).unwrap();
        chip.set_wiper(999, 0).unwrap();
        chip.set_wiper(-3, 0).unwrap();
        assert_eq!(chip.get_wiper(0).unwrap(), 100);
        chip.set_resistance(5075.0, 0).unwrap();
        chip.set_resistance(1e9, 0).unwrap();
        assert_eq!(chip.set_wiper(1, 1), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn eight_bit_uses_d8() {
        let exp = [wr(&[0x11, 0x00]), wr(&[0x00, 255]), rd(0x1C, 256), rd(0x0C, 0x3FF)];
        let (mut i2c, mut chip) = mk(Part::Mcp4652, 0x58, &exp);
        chip.set_wiper(256, 1).unwrap();
        chip.set_wiper(255, 0).unwrap();
        assert_eq!(chip.get_wiper(1).unwrap(), 256);
        assert_eq!(chip.get_wiper(0).unwrap(), 256); // above full scale clamps to the max code
        i2c.done();
    }

    #[test]
    fn resistance_model_and_ratio() {
        let ex = [
            T::write_read(0x2E, vec![0x0C], vec![0, 64]),
            T::write_read(0x2E, vec![0x0C], vec![0, 0]),
            T::write_read(0x2E, vec![0x0C], vec![0, 0]),
            T::write(0x2E, vec![0x00, 64]),
            T::write_read(0x2E, vec![0x0C], vec![0, 64]),
        ];
        let (mut i2c, mut chip) = mk(Part::Mcp4531, 0x2E, &ex);
        assert!((chip.get_resistance(0).unwrap() - 5075.0).abs() < 1e-3);
        assert!((chip.get_resistance(0).unwrap() - 75.0).abs() < 1e-3);
        assert!((chip.get_resistance_wa(0).unwrap() - 10075.0).abs() < 1e-3);
        chip.set_ratio(0.5, 0).unwrap();
        assert!((chip.get_ratio(0).unwrap() - 0.5).abs() < 1e-6);
        assert!((chip.get_resistance_step() - 10000.0 / 128.0).abs() < 1e-3);
        i2c.done();
    }

    #[test]
    fn rheostat_has_no_wa() {
        let (mut i2c, mut chip) = mk(Part::Mcp4532, 0x58, &[]);
        assert_eq!(chip.get_resistance_wa(0), Err(Error::Unsupported));
        assert_eq!(chip.set_resistance_wa(1000.0, 0), Err(Error::Unsupported));
        assert_eq!(chip.set_both(1, 2), Err(Error::Unsupported));
        i2c.done();
    }

    #[test]
    fn set_both_and_increment_decrement() {
        let mut run = vec![0x14u8; 10];
        run.truncate(10);
        let exp = [
            wr(&[0x00, 10]), wr(&[0x10, 20]),
            wr(&run), rd(0x1C, 128),
            wr(&[0x08]), rd(0x0C, 0),
        ];
        let (mut i2c, mut chip) = mk(Part::Mcp4632, 0x58, &exp);
        chip.set_both(10, 20).unwrap();
        assert_eq!(chip.increment(10, 1).unwrap(), 128);
        assert_eq!(chip.decrement(1, 0).unwrap(), 0);
        i2c.done();
    }

    #[test]
    fn long_runs_are_chunked() {
        let exp = [wr(&[0x04; 32]), wr(&[0x04; 8]), rd(0x0C, 128)];
        let (mut i2c, mut chip) = mk(Part::Mcp4532, 0x58, &exp);
        assert_eq!(chip.increment(40, 0).unwrap(), 128);
        i2c.done();
    }

    #[test]
    fn shutdown_and_terminals_are_read_modify_write() {
        let exp = [
            rd(0x4C, 0x1FF), wr(&[0x41, 0x7F]),   // shutdown ch1: clear bit 7 -> 0x17F
            rd(0x4C, 0x17F), wr(&[0x41, 0xFF]),          // resume ch1: set bit 7 -> 0x1FF
            rd(0x4C, 0x17F),                             // is_shutdown ch1
            rd(0x4C, 0x1FF), wr(&[0x41, 0xDF]),   // terminals ch1 w=false -> clear bit 5 -> 0x1DF
            rd(0x4C, 0x1DF),                             // get_terminals ch1
            rd(0x4C, 0x1FF), wr(&[0x40, 0xFF]),          // general call off: clear bit 8 -> 0x0FF
        ];
        let (mut i2c, mut chip) = mk(Part::Mcp4632, 0x58, &exp);
        chip.shutdown(1).unwrap();
        chip.resume(1).unwrap();
        assert!(chip.is_shutdown(1).unwrap());
        chip.set_terminals(true, false, true, 1).unwrap();
        assert_eq!(chip.get_terminals(1).unwrap(), (true, false, true));
        chip.set_general_call(false).unwrap();
        i2c.done();
    }

    #[test]
    fn nv_ops_unsupported_on_volatile() {
        let (mut i2c, mut chip) = mk(Part::Mcp4532, 0x58, &[]);
        let mut d = NoopDelay::new();
        assert_eq!(chip.read_status(), Err(Error::Unsupported));
        assert_eq!(chip.wait_ready(&mut d, 5), Err(Error::Unsupported));
        assert_eq!(chip.get_nv_wiper(0), Err(Error::Unsupported));
        assert_eq!(chip.store_wiper(&mut d, 0), Err(Error::Unsupported));
        assert_eq!(chip.read_data(0), Err(Error::Unsupported));
        assert_eq!(chip.set_write_protect(&mut d, true), Err(Error::Unsupported));
        i2c.done();
    }

    #[test]
    fn nv_status_wiper_and_data() {
        let exp = [
            rd(0x5C, 0x1F0 | 0x08 | 0x02 | 0x01),
            rd(0x5C, 0x1F0 | 0x08), rd(0x5C, 0x1F0),
            wr(&[0x30, 200]), rd(0x5C, 0x1F0),                    // set_nv_wiper ch1 -> NV address 3
            rd(0x0C, 150), wr(&[0x20, 150]), rd(0x5C, 0x1F0),     // store_wiper ch0 -> NV address 2
            wr(&[0x91, 0xAB]), rd(0x5C, 0x1F0),                   // write_data 3 = 0x1AB -> address 9
            rd(0x9C, 0x1AB),                                      // read_data 3
        ];
        let (mut i2c, mut chip) = mk(Part::Mcp4662, 0x58, &exp);
        let mut d = NoopDelay::new();
        let st = chip.read_status().unwrap();
        assert!(st.eeprom_busy && st.wiper_lock == [true, false] && st.write_protect);
        chip.wait_ready(&mut d, 20).unwrap();
        chip.set_nv_wiper(&mut d, 200, 1).unwrap();
        assert_eq!(chip.store_wiper(&mut d, 0).unwrap(), 150);
        chip.write_data(&mut d, 3, 0x1AB).unwrap();
        assert_eq!(chip.read_data(3).unwrap(), 0x1AB);
        assert_eq!(chip.read_data(10), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn wait_ready_times_out() {
        let exp = [rd(0x5C, 0x1F8), rd(0x5C, 0x1F8), rd(0x5C, 0x1F8)];
        let (mut i2c, mut chip) = mk(Part::Mcp4562, 0x58, &exp);
        let mut d = NoopDelay::new();
        assert_eq!(chip.wait_ready(&mut d, 2), Err(Error::Timeout));
        i2c.done();
    }

    #[test]
    fn high_voltage_commands_toggle_the_pin() {
        let exp = [
            wr(&[0x28]), rd(0x5C, 0x1F0),   // WiperLock 0 enable
            wr(&[0x34]), rd(0x5C, 0x1F0),   // WiperLock 1 disable
            wr(&[0xF8]), rd(0x5C, 0x1F0),   // write protect enable
            wr(&[0xF4]), rd(0x5C, 0x1F0),   // write protect disable
        ];
        let i2c = I2cMock::new(&exp);
        let mut handle = i2c.clone();
        let pin_exp: Vec<PinT> = (0..4).flat_map(|_| [PinT::set(State::High), PinT::set(State::Low)]).collect();
        let mut pin = PinMock::new(&pin_exp);
        let mut chip = Mcp4xxxFull::with_hvc(i2c, 0x58, Part::Mcp4662, 10000.0, 75.0, pin.clone(), 1);
        let mut d = NoopDelay::new();
        chip.set_wiper_lock(&mut d, true, 0).unwrap();
        chip.set_wiper_lock(&mut d, false, 1).unwrap();
        chip.set_write_protect(&mut d, true).unwrap();
        chip.set_write_protect(&mut d, false).unwrap();
        handle.done();
        pin.done();
    }

    #[test]
    fn high_voltage_needs_pin() {
        let (mut i2c, mut chip) = mk(Part::Mcp4562, 0x58, &[]);
        let mut d = NoopDelay::new();
        assert_eq!(chip.set_wiper_lock(&mut d, true, 0), Err(Error::Unsupported));
        i2c.done();
    }

    #[test]
    fn reset_volatile() {
        let exp = [wr(&[0x00, 64]), wr(&[0x10, 64]), wr(&[0x41, 0xFF])];
        let (mut i2c, mut chip) = mk(Part::Mcp4632, 0x58, &exp);
        chip.reset().unwrap();
        i2c.done();
    }

    #[test]
    fn reset_nv_restores_eeprom_wiper() {
        // STATUS (no locks), NV wiper 0, volatile wiper 0, TCON.
        let exp = [rd(0x5C, 0x1F0), rd(0x2C, 0x80), wr(&[0x00, 0x80]), wr(&[0x41, 0xFF])];
        let (mut i2c, mut chip) = mk(Part::Mcp4562, 0x58, &exp);
        chip.reset().unwrap();
        i2c.done();
    }

    #[test]
    fn reset_skips_locked_networks() {
        // WL0 set: network 0 and TCON are skipped.
        let exp = [rd(0x5C, 0x1F0 | 0x02)];
        let (mut i2c, mut chip) = mk(Part::Mcp4562, 0x58, &exp);
        chip.reset().unwrap();
        i2c.done();
    }

    #[test]
    fn raw_access_checks_addresses() {
        let exp = [rd(0x4C, 0x1FF), wr(&[0x00, 77])];
        let (mut i2c, mut chip) = mk(Part::Mcp4532, 0x58, &exp);
        assert_eq!(chip.read_register(4).unwrap(), 0x1FF);
        chip.write_register(0, 77).unwrap();
        assert_eq!(chip.write_register(5, 0), Err(Error::InvalidArgument));
        assert_eq!(chip.read_register(2), Err(Error::InvalidArgument));
        assert_eq!(chip.read_register(16), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn set_rab_has_no_traffic() {
        let exp = [rd(0x0C, 128)];
        let (mut i2c, mut chip) = mk(Part::Mcp4532, 0x58, &exp);
        chip.set_rab(50000.0, Some(100.0));
        assert!((chip.get_resistance(0).unwrap() - 50100.0).abs() < 1e-2);
        i2c.done();
    }
}
