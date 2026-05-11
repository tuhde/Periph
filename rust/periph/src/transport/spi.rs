//! SPI transport for chip drivers.
//!
//! Chip drivers in this library are generic over [`SpiDevice`] from
//! `embedded-hal` 1.0. CS management is handled by the `SpiDevice`
//! implementation — callers pass a fully configured device object and never
//! touch CS directly.
//!
//! ## Writing a chip driver
//!
//! Bound the SPI type parameter on [`SpiDevice`]:
//!
//! ```rust
//! use periph::transport::spi::SpiDevice;
//!
//! pub struct MyChipMinimal<SPI> {
//!     spi: SPI,
//! }
//!
//! impl<SPI: SpiDevice> MyChipMinimal<SPI> {
//!     pub fn new(spi: SPI) -> Self {
//!         Self { spi }
//!     }
//! }
//! ```
//!
//! ## Performing transactions
//!
//! Use [`SpiDevice::transaction`] with [`Operation`] slices to keep CS
//! asserted across a write-then-read sequence:
//!
//! ```rust,ignore
//! use periph::transport::spi::Operation;
//!
//! let mut buf = [0u8; 2];
//! self.spi.transaction(&mut [
//!     Operation::Write(&[reg_addr]),
//!     Operation::Read(&mut buf),
//! ])?;
//! ```
//!
//! ## Linux host (`spidev` crate)
//!
//! `spidev` implements `SpiBus`, not `SpiDevice`. Wrap it with
//! `embedded_hal_bus::spi::ExclusiveDevice` to obtain a type that implements
//! [`SpiDevice`]:
//!
//! ```rust,ignore
//! use spidev::{Spidev, SpidevOptions, SpiModeFlags};
//! use embedded_hal_bus::spi::ExclusiveDevice;
//!
//! let mut spi = Spidev::open("/dev/spidev0.0")?;
//! spi.configure(
//!     &SpidevOptions::new()
//!         .max_speed_hz(1_000_000)
//!         .mode(SpiModeFlags::SPI_MODE_0)
//!         .build(),
//! )?;
//! let device = ExclusiveDevice::new_no_delay(spi, cs)?;
//! // `device` implements SpiDevice — pass it to chip driver constructors
//! ```
//!
//! `Cargo.toml` dependencies for Linux examples and tests:
//!
//! ```toml
//! spidev = "0.5"
//! embedded-hal = "1"
//! embedded-hal-bus = "0.2"
//! ```

/// The trait every SPI chip driver is generic over.
///
/// Re-exported from [`embedded_hal::spi::SpiDevice`]. Implementations include
/// CS management; chip drivers never assert or deassert CS themselves.
pub use embedded_hal::spi::SpiDevice;

/// Operation type for [`SpiDevice::transaction`] calls.
///
/// Re-exported from [`embedded_hal::spi::Operation`]. Use
/// `Operation::Write` followed by `Operation::Read` within a single
/// `transaction` call to keep CS asserted across both phases.
pub use embedded_hal::spi::Operation;
