#![no_std]

//! NeoPixel transport using SPI bit-banging.
//!
//! Drives WS2812B-compatible addressable LEDs over a single data line using
//! timing-encoded NZR protocol via SPI MOSI at 800 Kbps.

use embedded_hal::spi::SpiBus;

fn encode(data: &[u8]) -> heapless::Vec<u8, 256> {
    //! Encode NeoPixel data bytes to SPI bitstream.
    //!
    //! Each NeoPixel bit is encoded as 3 SPI bits:
    //! - 0: 0b100 (417ns high, 833ns low)
    //! - 1: 0b110 (833ns high, 417ns low)
    let mut out = heapless::Vec::new();
    for &byte in data {
        for bit in (0..8).rev() {
            out.push(if (byte >> bit) & 1 == 1 { 0b110 } else { 0b100 }).ok();
        }
    }
    for _ in 0..16 {
        out.push(0x00).ok();
    }
    out
}

/// NeoPixel transport using SPI bit-banging.
pub struct NeoPixelTransport<SPI> {
    spi: SPI,
}

impl<SPI: SpiBus> NeoPixelTransport<SPI> {
    /// Construct a new NeoPixelTransport.
    ///
    /// # Arguments
    /// * `spi` - SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    pub fn new(spi: SPI) -> Self {
        Self { spi }
    }

    /// Encode and transmit NeoPixel data.
    ///
    /// # Arguments
    /// * `data` - Pixel data (3 bytes/pixel for RGB, 4 bytes/pixel for RGBW).
    ///
    /// # Errors
    /// Returns the SPI bus error if transmission fails.
    pub fn write(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        let encoded = encode(data);
        self.spi.write(&encoded)
    }
}