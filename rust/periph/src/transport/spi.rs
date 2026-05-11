//! SPI transport interface.
//!
//! Chip drivers are generic over [`embedded_hal::spi::SpiDevice`]. The trait
//! provides CS management internally, so callers do not handle CS at all.

pub use embedded_hal::spi::SpiDevice;