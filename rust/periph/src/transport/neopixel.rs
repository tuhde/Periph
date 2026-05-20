//! NeoPixel (WS2812B) transport using SPI bit-encoding.
//!
//! Drives cascaded WS2812B-compatible addressable LEDs over a single data line
//! using a timing-encoded NZR protocol. The transport is write-only: pixels
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

/// Encode pixel data into WS2812B-compatible SPI bitstream.
///
/// Each NeoPixel byte becomes 3 SPI bytes (24 bits), followed by 16 trailing
/// zero bytes for the reset pulse. The encoding is MSB-first.
fn encode(data: &[u8]) -> heapless::Vec<u8, 768> {
    let mut out = heapless::Vec::new();
    out.resize_default(data.len() * 3 + 16).ok();

    for (i, &byte) in data.iter().enumerate() {
        let mut bits: u32 = 0;
        for bit in (0..8).rev() {
            bits = (bits << 3) | if (byte >> bit) & 1 == 1 { 0b110 } else { 0b100 };
        }
        out[i * 3]     = ((bits >> 16) & 0xFF) as u8;
        out[i * 3 + 1] = ((bits >> 8) & 0xFF) as u8;
        out[i * 3 + 2] = (bits & 0xFF) as u8;
    }

    out
}

/// NeoPixel SPI transport.
///
/// Wraps any `embedded_hal::spi::SpiBus` configured at 2.4 MHz, mode 0.
/// The caller is responsible for configuring the SPI bus before passing it.
pub struct NeoPixelTransport<SPI> {
    spi: SPI,
}

impl<SPI: embedded_hal::spi::SpiBus> NeoPixelTransport<SPI> {
    /// Construct and initialise the NeoPixel transport.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    pub fn new(spi: SPI) -> Self {
        Self { spi }
    }

    /// Encode and transmit pixel data, then hold MOSI low for reset.
    ///
    /// # Arguments
    /// * `data` — Pixel bytes to send (3 bytes per RGB pixel, 4 bytes per RGBW pixel).
    pub fn write(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        let encoded = encode(data);
        self.spi.write(&encoded)
    }
}