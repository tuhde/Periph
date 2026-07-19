//! SiPo (serial-in/parallel-out shift register) transport for chip drivers.
//!
//! Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
//! SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. The transport is
//! generic over any `embedded-hal` 1.0 [`SpiBus`] — a hardware bus or a
//! caller-supplied bit-banged `SpiBus` impl (e.g. from `embedded-hal-bus`)
//! are interchangeable, since the transport's type signature doesn't
//! distinguish them — plus [`OutputPin`] for RCK and `Option<impl OutputPin>`
//! for SRCLR/G.
//!
//! Write-only: there is no `read`/`write_read`.
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::transport::sipo::SiPoTransport;
//!
//! let mut transport = SiPoTransport::new(spi, rck, Some(srclr), Some(g))?;
//! transport.write(&[0xA5])?;
//! transport.clear()?;
//! transport.set_output_enable(false)?;
//! ```
//!
//! ## Linux host (`linux-embedded-hal` crate)
//!
//! Use `linux_embedded_hal::SpidevBus` for hardware SPI, or any bit-banged
//! `SpiBus` impl (e.g. built on `linux_embedded_hal::CdevPin` via
//! `embedded-hal-bus`) for software SPI. `CdevPin` also provides RCK/SRCLR/G
//! in both modes.
//!
//! ```toml
//! linux-embedded-hal = "0.4"
//! embedded-hal = "1"
//! ```

use embedded_hal::digital::OutputPin;
use embedded_hal::spi::SpiBus;

/// Error type for [`SiPoTransport`] operations.
#[derive(Debug)]
pub enum SiPoError<SE, RE, CE, GE> {
    /// Error transferring data over the SPI bus.
    Spi(SE),
    /// Error driving the RCK pin.
    Rck(RE),
    /// Error driving the SRCLR pin.
    Srclr(CE),
    /// Error driving the G pin.
    G(GE),
    /// [`SiPoTransport::clear`] was called but SRCLR was not configured.
    SrclrNotConfigured,
    /// [`SiPoTransport::set_output_enable`] was called but G was not configured.
    GNotConfigured,
}

/// SiPo (serial-in/parallel-out shift register) transport.
///
/// Generic over any `embedded-hal` 1.0 [`SpiBus`] (SER IN/SRCK, hardware or
/// bit-banged) and [`OutputPin`] for RCK, with `Option<impl OutputPin>` for
/// SRCLR/G.
pub struct SiPoTransport<SPI, RCK, SRCLR, G> {
    spi: SPI,
    rck: RCK,
    srclr: Option<SRCLR>,
    g: Option<G>,
}

impl<SPI, RCK, SRCLR, G> SiPoTransport<SPI, RCK, SRCLR, G>
where
    SPI: SpiBus,
    RCK: OutputPin,
    SRCLR: OutputPin,
    G: OutputPin,
{
    /// Create a new transport: drive RCK LOW, SRCLR HIGH (if configured), and
    /// G LOW (if configured).
    ///
    /// # Arguments
    ///
    /// * `spi`   – Hardware or bit-banged SPI bus for SER IN/SRCK.
    /// * `rck`   – Pin connected to RCK (register clock).
    /// * `srclr` – Pin connected to SRCLR, or `None` to disable it.
    /// * `g`     – Pin connected to G (output enable), or `None` to disable it.
    pub fn new(
        spi: SPI,
        mut rck: RCK,
        mut srclr: Option<SRCLR>,
        mut g: Option<G>,
    ) -> Result<Self, SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        rck.set_low().map_err(SiPoError::Rck)?;
        if let Some(ref mut srclr) = srclr {
            srclr.set_high().map_err(SiPoError::Srclr)?;
        }
        if let Some(ref mut g) = g {
            g.set_low().map_err(SiPoError::G)?;
        }
        Ok(Self { spi, rck, srclr, g })
    }

    /// Shift `data` out MSB-first, then latch it into the output register.
    ///
    /// Transfers `data` over the SPI bus, then pulses RCK HIGH then LOW to
    /// latch the shifted data into the storage register that drives the
    /// outputs.
    ///
    /// # Arguments
    ///
    /// * `data` – Bytes to shift out, one byte per cascaded device.
    pub fn write(
        &mut self,
        data: &[u8],
    ) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        self.spi.write(data).map_err(SiPoError::Spi)?;
        self.rck.set_high().map_err(SiPoError::Rck)?;
        self.rck.set_low().map_err(SiPoError::Rck)?;
        Ok(())
    }

    /// Pulse SRCLR LOW then HIGH to clear the shift register.
    ///
    /// The storage register (and therefore the outputs) is unaffected until
    /// the next [`SiPoTransport::write`].
    ///
    /// # Errors
    ///
    /// Returns [`SiPoError::SrclrNotConfigured`] if SRCLR was not configured.
    pub fn clear(
        &mut self,
    ) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        let srclr = self.srclr.as_mut().ok_or(SiPoError::SrclrNotConfigured)?;
        srclr.set_low().map_err(SiPoError::Srclr)?;
        srclr.set_high().map_err(SiPoError::Srclr)?;
        Ok(())
    }

    /// Drive G LOW (`enabled = true`) or HIGH (`enabled = false`).
    ///
    /// `true` lets the storage register drive the outputs; `false` forces
    /// every output off without disturbing the storage register's contents.
    ///
    /// # Errors
    ///
    /// Returns [`SiPoError::GNotConfigured`] if G was not configured.
    pub fn set_output_enable(
        &mut self,
        enabled: bool,
    ) -> Result<(), SiPoError<SPI::Error, RCK::Error, SRCLR::Error, G::Error>> {
        let g = self.g.as_mut().ok_or(SiPoError::GNotConfigured)?;
        if enabled {
            g.set_low().map_err(SiPoError::G)?;
        } else {
            g.set_high().map_err(SiPoError::G)?;
        }
        Ok(())
    }

    /// Consume the transport and return the SPI bus and pins.
    pub fn release(self) -> (SPI, RCK, Option<SRCLR>, Option<G>) {
        (self.spi, self.rck, self.srclr, self.g)
    }
}
