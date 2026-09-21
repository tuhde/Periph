//! TPIC6B595 8-bit power SIPO shift register driver.
//!
//! Drives up to `num_devices` cascaded TPIC6B595s through a SiPo (serial-
//! in/parallel-out) connection. Each device exposes 8 open-drain outputs
//! (DRAIN0–DRAIN7); every write shifts the entire cascade MSB-first and pulses
//! RCK to latch all outputs atomically. Outputs only sink current — they never
//! source it; an external pull-up or load supply is required for the "off"/
//! high state.
//!
//! The driver owns a `num_devices`-byte shadow register so single-pin updates
//! work without re-reading the bus. Every write (pin, port, fill, write_all)
//! rebuilds and retransmits the entire reversed cascade — see
//! `specs/io_expander/tpic6b595.md` for the wire-order reversal that cascading
//! requires.
//!
//! Initialises every output to OFF at construction (shadow zero, latched
//! once). If the SiPo connection has SRCLR wired, the constructor pulses it
//! to clear the shift register before the all-zero latch.
//!
//! ## Safety
//!
//! The SiPo connection is owned through a shared reference. Multiple
//! [`ExPin`] objects may coexist, but simultaneous access from different ISR
//! contexts is not safe. Use only from a single execution context.

use core::cell::Cell;
use embedded_hal::digital::{ErrorKind, ErrorType, OutputPin, StatefulOutputPin};
use embedded_hal::spi::SpiBus;

use crate::connection::sipo::{SiPoConnection, SiPoError};

/// Wraps a SiPo error so it satisfies `embedded_hal::digital::Error`.
#[derive(Debug)]
pub struct PinError<E>(pub E);

impl<E: embedded_hal::spi::Error> embedded_hal::digital::Error for PinError<E> {
    fn kind(&self) -> ErrorKind { ErrorKind::Other }
}

/// Maximum cascade depth supported by the driver's shadow buffer.
pub const MAX_DEVICES: usize = 8;

/// TPIC6B595 minimal driver — exposes 8 × `num_devices` output pins as
/// [`ExPin`] GPIO proxies.
pub struct Tpic6b595Minimal<SPI, RCK, SRCLR, G> {
    sipo:       SiPoConnection<SPI, RCK, SRCLR, G>,
    num_devices: u8,
    shadow:     [Cell<u8>; MAX_DEVICES],
}

impl<SPI, RCK, SRCLR, G> Tpic6b595Minimal<SPI, RCK, SRCLR, G>
where
    SPI:  SpiBus,
    RCK:  embedded_hal::digital::OutputPin,
    SRCLR: embedded_hal::digital::OutputPin,
    G:    embedded_hal::digital::OutputPin,
{
    /// Create a new `Tpic6b595Minimal` and clear every output.
    ///
    /// # Arguments
    /// * `spi`        — Hardware or bit-banged SPI bus for SER IN / SRCK.
    /// * `rck`        — Pin connected to RCK (register clock).
    /// * `srclr`      — Pin connected to SRCLR, or `None` to disable it.
    /// * `g`          — Pin connected to G, or `None` to disable it.
    /// * `num_devices` — Number of cascaded TPIC6B595s on the wire (1–8).
    ///
    /// # Errors
    ///
    /// Returns whatever [`SiPoConnection::new`] returns (e.g. a GPIO error
    /// driving RCK/SRCLR/G to their idle states). Construction never
    /// returns an error from `SRCLR not configured` — a missing SRCLR line
    /// is silently ignored, since the chip is fully usable without it
    /// (the construction's `clear()` call is best-effort, and the all-zero
    /// write that follows guarantees every output starts OFF).
    pub fn new(
        spi: SPI,
        rck: RCK,
        srclr: Option<SRCLR>,
        g: Option<G>,
        num_devices: u8,
    ) -> Result<Self, SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        let n = if (num_devices as usize) > MAX_DEVICES { MAX_DEVICES as u8 } else { num_devices };
        let mut shadow = [Cell::new(0u8); MAX_DEVICES];
        for cell in shadow.iter_mut() { cell.set(0); }

        let sipo = SiPoConnection::new(spi, rck, srclr, g)?;
        // Best-effort clear of the shift register; missing SRCLR is fine.
        let _ = sipo.clear();

        let chip = Self { sipo, num_devices: n, shadow };
        chip.flush().ok();
        Ok(chip)
    }

    fn flush(&self) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        let mut wire = [0u8; MAX_DEVICES];
        for i in 0..(self.num_devices as usize) {
            wire[i] = self.shadow[self.num_devices as usize - 1 - i].get();
        }
        self.sipo.write(&wire[..self.num_devices as usize])
    }

    fn set_pin(&self, n: u8, high: bool) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        let port_idx = (n / 8) as usize;
        let bit = n % 8;
        let mut s = self.shadow[port_idx].get();
        if high { s |=   1 << bit; }
        else    { s &= !(1 << bit); }
        self.shadow[port_idx].set(s);
        self.flush()
    }

    /// Write all 8 outputs of cascaded device `port` from `mask`.
    ///
    /// `mask` bit 0 = DRAIN0, bit 7 = DRAIN7. 1 = ON (DMOS conducting,
    /// sinks current); 0 = OFF (high-impedance).
    pub fn write_port(&self, port: usize, mask: u8) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.shadow[port].set(mask);
        self.flush()
    }

    /// Set every pin on every cascaded device to `value`.
    pub fn fill(&self, value: bool) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        let b = if value { 0xFF } else { 0x00 };
        for cell in self.shadow.iter() { cell.set(b); }
        self.flush()
    }

    /// Turn every output off (equivalent to `fill(false)`).
    pub fn off(&self) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.fill(false)
    }

    /// Return an [`ExPin`] proxy for global pin `n` (0..`num_devices` * 8 - 1).
    pub fn pin(&self, n: u8) -> ExPin<'_, SPI, RCK, SRCLR, G> {
        ExPin { chip: self, n }
    }

    /// Return the shadow byte for the given port (no bus read).
    pub(crate) fn shadow_byte(&self, port: usize) -> u8 {
        self.shadow[port].get()
    }
}

/// GPIO proxy for a single TPIC6B595 pin — output-only.
pub struct ExPin<'a, SPI, RCK, SRCLR, G> {
    chip: &'a Tpic6b595Minimal<SPI, RCK, SRCLR, G>,
    n:    u8,
}

impl<SPI, RCK, SRCLR, G> ErrorType for ExPin<'_, SPI, RCK, SRCLR, G>
where
    SPI:  SpiBus,
    RCK:  embedded_hal::digital::OutputPin,
    SRCLR: embedded_hal::digital::OutputPin,
    G:    embedded_hal::digital::OutputPin,
{
    type Error = SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>;
}

impl<SPI, RCK, SRCLR, G> OutputPin for ExPin<'_, SPI, RCK, SRCLR, G>
where
    SPI:  SpiBus,
    RCK:  embedded_hal::digital::OutputPin,
    SRCLR: embedded_hal::digital::OutputPin,
    G:    embedded_hal::digital::OutputPin,
{
    fn set_high(&mut self) -> Result<(), Self::Error> {
        self.chip.set_pin(self.n, true)
    }

    fn set_low(&mut self) -> Result<(), Self::Error> {
        self.chip.set_pin(self.n, false)
    }
}

impl<SPI, RCK, SRCLR, G> StatefulOutputPin for ExPin<'_, SPI, RCK, SRCLR, G>
where
    SPI:  SpiBus,
    RCK:  embedded_hal::digital::OutputPin,
    SRCLR: embedded_hal::digital::OutputPin,
    G:    embedded_hal::digital::OutputPin,
{
    fn is_set_high(&mut self) -> Result<bool, Self::Error> {
        let port = (self.n / 8) as usize;
        let bit  = self.n % 8;
        Ok((self.chip.shadow_byte(port) >> bit) & 1 == 1)
    }

    fn is_set_low(&mut self) -> Result<bool, Self::Error> {
        Ok(!self.is_set_high()?)
    }
}

/// TPIC6B595 full driver — extends [`Tpic6b595Minimal`] with hardware features.
pub struct Tpic6b595Full<SPI, RCK, SRCLR, G> {
    inner: Tpic6b595Minimal<SPI, RCK, SRCLR, G>,
}

impl<SPI, RCK, SRCLR, G> Tpic6b595Full<SPI, RCK, SRCLR, G>
where
    SPI:  SpiBus,
    RCK:  embedded_hal::digital::OutputPin,
    SRCLR: embedded_hal::digital::OutputPin,
    G:    embedded_hal::digital::OutputPin,
{
    /// Create a new `Tpic6b595Full` and clear every output.
    pub fn new(
        spi: SPI,
        rck: RCK,
        srclr: Option<SRCLR>,
        g: Option<G>,
        num_devices: u8,
    ) -> Result<Self, SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        Ok(Self {
            inner: Tpic6b595Minimal::new(spi, rck, srclr, g, num_devices)?,
        })
    }

    /// Return an [`ExPin`] proxy for global pin `n`.
    pub fn pin(&self, n: u8) -> ExPin<'_, SPI, RCK, SRCLR, G> {
        self.inner.pin(n)
    }

    /// Write all 8 outputs of cascaded device `port` from `mask`.
    pub fn write_port(&self, port: usize, mask: u8) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.inner.write_port(port, mask)
    }

    /// Set every pin on every cascaded device to `value`.
    pub fn fill(&self, value: bool) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.inner.fill(value)
    }

    /// Turn every output off.
    pub fn off(&self) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.inner.off()
    }

    /// Pulse SRCLR to clear the shift register only.
    ///
    /// The storage register (and therefore the DRAIN outputs) keeps its
    /// last-latched value until the next RCK pulse.
    pub fn clear(&mut self) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.inner.sipo.clear()
    }

    /// Drive G LOW (`enabled = true`) or HIGH (`enabled = false`).
    pub fn set_output_enable(&mut self, enabled: bool) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.inner.sipo.set_output_enable(enabled)
    }

    /// Write every cascaded device's byte in one call.
    ///
    /// `values` is length-truncated or zero-extended to `num_devices` as needed.
    pub fn write_all(&mut self, values: &[u8]) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        for i in 0..(self.inner.num_devices as usize) {
            let v = if i < values.len() { values[i] } else { 0 };
            self.inner.shadow[i].set(v);
        }
        self.inner.flush()
    }
}
