//! UART transport for chip drivers.
//!
//! Chip drivers that communicate over UART are generic over
//! [`embedded_io::Read`] + [`embedded_io::Write`]. For embedded targets (e.g.
//! ESP32-S3 with `esp-hal`), the UART peripheral implements both traits
//! directly and can be passed straight to the chip driver constructor.
//!
//! ## Writing a chip driver
//!
//! Bound the UART type parameter on both traits:
//!
//! ```rust
//! use embedded_io::{Read, Write};
//!
//! pub struct MyChipMinimal<U> {
//!     uart: U,
//! }
//!
//! impl<U: Read + Write> MyChipMinimal<U> {
//!     pub fn new(uart: U) -> Self {
//!         Self { uart }
//!     }
//! }
//! ```
//!
//! ## Performing transactions
//!
//! ```rust,ignore
//! // Write: send command bytes
//! self.uart.write_all(&[0xAB, 0xCD])?;
//!
//! // Read: receive n bytes
//! let mut buf = [0u8; 4];
//! self.uart.read(&mut buf)?;
//!
//! // Write-then-read
//! self.uart.write_all(&[0xAB])?;
//! self.uart.read(&mut buf)?;
//! ```
//!
//! ## RS-485 mode (embedded)
//!
//! For RS-485, wrap the UART in a newtype that asserts a GPIO pin around
//! `write` calls and waits for TX drain before deasserting DE. The chip
//! driver itself never sees this wrapping.
//!
//! ## Linux host (`serialport` crate)
//!
//! On Linux, use [`LinuxUart`] from `uart_linux` which wraps the `serialport`
//! crate in a newtype implementing `embedded_io::Read + Write`:
//!
//! ```rust,ignore
//! use periph::transport::uart_linux::LinuxUart;
//!
//! let uart = LinuxUart::open("/dev/ttyS0", 9600)?;
//! let chip = MyChipMinimal::new(uart);
//! ```
//!
//! Add to `Cargo.toml`:
//! ```toml
//! periph = { path = "..." }
//! ```

/// Re-export the `embedded_io` traits that every UART chip driver is generic
/// over.
pub use embedded_io::{Read, Write};
