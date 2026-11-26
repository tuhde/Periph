//! NeoPixel (WS2812B) connection using SPI bit-encoding.
//!
//! Drives cascaded WS2812B-compatible addressable LEDs over a single data line
//! using a timing-encoded NZR protocol. The connection is write-only: pixels
//! accept data but never respond.
//!
//! All platforms use the same SPI bit-encoding approach: each NeoPixel bit is
//! encoded as 3 SPI bits at 2.4 MHz and shifted out on the MOSI line. The
//! encoding algorithm, configuration parameters, and reset handling are
//! identical across all platforms.
//!
//! # Hardware constraint
//! The NeoPixel DIN pin must be connected to the SPI MOSI pin. SCK, MISO,
//! and CS are unused by the strip.
//!
//! # Protocol
//! Each NeoPixel bit is encoded as 3 SPI bits at 2.4 MHz (one SPI bit = 416.7 ns):
//! - `0` → `100` (417 ns high, 833 ns low)
//! - `1` → `110` (833 ns high, 417 ns low)
//!
//! Each NeoPixel byte (8 bits) → 24 SPI bits → 3 SPI bytes, packed MSB-first.
//! For a buffer of `n` NeoPixel bytes: output `3n` encoded SPI bytes, then
//! 16 zero bytes (≈53 µs) for the reset.

/// Largest pixel payload a single write accepts: 256 RGBW pixels (1024 bytes),
/// the `MAX_PIXELS` limit of the RGB and RGBW strip drivers.
pub const MAX_DATA_BYTES: usize = 1024;

/// Largest reset tail a single write accepts, in zero bytes (128 bytes × 8 bits ×
/// 416.7 ns ≈ 427 µs; WS2814's 90-byte ≈ 300 µs reset is the longest a driver uses).
pub const MAX_RESET_BYTES: usize = 128;

/// Capacity of the encoded SPI bitstream: 3 SPI bytes per data byte plus the reset tail.
const MAX_ENCODED: usize = MAX_DATA_BYTES * 3 + MAX_RESET_BYTES;

/// Error returned by [`NeoPixelConnection::write`] / [`NeoPixelConnection::write_ext`].
#[derive(Debug, PartialEq, Eq)]
pub enum NeoPixelError<E> {
    /// The SPI bus reported an error.
    Spi(E),
    /// `data` or `reset_bytes` exceeds [`MAX_DATA_BYTES`] / [`MAX_RESET_BYTES`];
    /// nothing was sent.
    TooLong {
        /// Bytes of pixel data passed in.
        data_len: usize,
        /// Reset bytes requested.
        reset_bytes: usize,
    },
}

/// Encode pixel data into WS2812B-compatible SPI bitstream.
///
/// Each NeoPixel byte becomes 3 SPI bytes (24 bits), followed by
/// `reset_bytes` trailing zero bytes for the reset pulse (16 bytes ≈ 53 µs
/// for WS2812B's ≥50 µs minimum; chips needing a longer reset, e.g.
/// SK6812RGBW's ≥80 µs, pass more via [`NeoPixelConnection::write_ext`]).
/// The encoding is MSB-first.
///
/// Returns `None` when the result would not fit in [`MAX_ENCODED`] bytes.
fn encode(data: &[u8], reset_bytes: usize) -> Option<heapless::Vec<u8, MAX_ENCODED>> {
    if data.len() > MAX_DATA_BYTES || reset_bytes > MAX_RESET_BYTES {
        return None;
    }
    let mut out = heapless::Vec::new();
    out.resize_default(data.len() * 3 + reset_bytes).ok()?;

    for (i, &byte) in data.iter().enumerate() {
        let mut bits: u32 = 0;
        for bit in (0..8).rev() {
            bits = (bits << 3) | if (byte >> bit) & 1 == 1 { 0b110 } else { 0b100 };
        }
        out[i * 3]     = ((bits >> 16) & 0xFF) as u8;
        out[i * 3 + 1] = ((bits >> 8) & 0xFF) as u8;
        out[i * 3 + 2] = (bits & 0xFF) as u8;
    }

    Some(out)
}

/// NeoPixel SPI connection.
///
/// Wraps any `embedded_hal::spi::SpiBus` configured at 2.4 MHz, mode 0.
/// The caller is responsible for configuring the SPI bus before passing it.
pub struct NeoPixelConnection<SPI> {
    // pub(crate) rather than private so chip drivers' own unit tests (e.g.
    // ws2812b.rs's `mod tests`) can reach the underlying mock's .done() to
    // verify every expected SPI transaction actually happened - not part
    // of the public API.
    pub(crate) spi: SPI,
    enabled: bool,
}

impl<SPI: embedded_hal::spi::SpiBus> NeoPixelConnection<SPI> {
    /// Construct and initialise the NeoPixel connection.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    pub fn new(spi: SPI) -> Self {
        Self { spi, enabled: true }
    }

    /// Resume writes.
    pub fn enable(&mut self) { self.enabled = true; }

    /// Gate [`NeoPixelConnection::write`] — it becomes a no-op while disabled.
    pub fn disable(&mut self) { self.enabled = false; }

    /// Return the current software-gate state.
    pub fn is_enabled(&self) -> bool { self.enabled }

    /// Encode and transmit pixel data, then hold MOSI low for a 16-byte
    /// (≈53 µs) reset. Shorthand for [`write_ext`](Self::write_ext) with
    /// WS2812B's default reset length - chips needing a longer minimum
    /// reset pulse (e.g. SK6812RGBW's ≥80 µs) should call `write_ext`
    /// directly instead.
    ///
    /// No-op if this connection is disabled.
    ///
    /// # Arguments
    /// * `data` — Pixel bytes to send (3 bytes per RGB pixel, 4 bytes per RGBW pixel).
    ///
    /// # Errors
    /// [`NeoPixelError::TooLong`] if `data` exceeds [`MAX_DATA_BYTES`];
    /// [`NeoPixelError::Spi`] if the bus fails.
    pub fn write(&mut self, data: &[u8]) -> Result<(), NeoPixelError<SPI::Error>> {
        self.write_ext(data, 16)
    }

    /// Encode and transmit pixel data, then hold MOSI low for `reset_bytes`
    /// zero bytes (`reset_bytes * 416.7 ns`) instead of the default 16.
    ///
    /// No-op if this connection is disabled.
    ///
    /// # Arguments
    /// * `data` — Pixel bytes to send (3 bytes per RGB pixel, 4 bytes per RGBW pixel).
    /// * `reset_bytes` — Trailing zero bytes to append after the encoded data.
    ///
    /// # Errors
    /// [`NeoPixelError::TooLong`] if `data` exceeds [`MAX_DATA_BYTES`] or
    /// `reset_bytes` exceeds [`MAX_RESET_BYTES`] (nothing is sent; this used to
    /// send an empty frame silently); [`NeoPixelError::Spi`] if the bus fails.
    pub fn write_ext(&mut self, data: &[u8], reset_bytes: usize) -> Result<(), NeoPixelError<SPI::Error>> {
        if !self.enabled {
            return Ok(());
        }
        let encoded = encode(data, reset_bytes)
            .ok_or(NeoPixelError::TooLong { data_len: data.len(), reset_bytes })?;
        self.spi.write(&encoded).map_err(NeoPixelError::Spi)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    // 256 RGBW pixels with WS2814's 90-byte reset: the largest frame a strip
    // driver can produce. The old 768-byte encode buffer silently sent nothing
    // for anything above 250 data bytes (#767).
    #[test]
    fn largest_driver_frame_is_sent() {
        let data = [0u8; MAX_DATA_BYTES];
        let expected = encode(&data, 90).expect("fits").to_vec();
        assert_eq!(expected.len(), MAX_DATA_BYTES * 3 + 90);
        let mut spi = SpiMock::new(&[SpiTransaction::write_vec(expected)]);
        let mut conn = NeoPixelConnection::new(spi.clone());
        conn.write_ext(&data, 90).expect("write");
        spi.done();
    }

    #[test]
    fn too_long_data_is_an_error_not_an_empty_frame() {
        let mut spi = SpiMock::new(&[]);
        let mut conn = NeoPixelConnection::new(spi.clone());
        let data = [0u8; MAX_DATA_BYTES + 1];
        assert_eq!(
            conn.write(&data),
            Err(NeoPixelError::TooLong { data_len: MAX_DATA_BYTES + 1, reset_bytes: 16 })
        );
        spi.done(); // nothing was sent
    }

    #[test]
    fn too_long_reset_is_an_error() {
        let mut spi = SpiMock::new(&[]);
        let mut conn = NeoPixelConnection::new(spi.clone());
        assert!(matches!(
            conn.write_ext(&[0, 0, 0], MAX_RESET_BYTES + 1),
            Err(NeoPixelError::TooLong { .. })
        ));
        spi.done();
    }
}
