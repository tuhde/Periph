//! WS2812B addressable RGB LED strip driver (Worldsemi).
//!
//! Thin wrapper over [`NeoPixelRgbMinimal`]/[`NeoPixelRgbFull`] fixing GRB
//! wire order and WS2812B's default 16-byte (~53 µs) reset.
//!
//! # Pixel limit
//! See [`super::neopixel_rgb_base::MAX_PIXELS`].

use embedded_hal::spi::SpiBus;
use super::neopixel_rgb_base::{NeoPixelRgbMinimal, NeoPixelRgbFull};

const CHANNEL_ORDER: [usize; 3] = [1, 0, 2]; // GRB: wire[0]=G, wire[1]=R, wire[2]=B
const RESET_BYTES: usize = 16;               // ~53us, WS2812B's default minimum

/// WS2812B minimal driver — fill the entire strip with one colour.
pub struct Ws2812bMinimal<SPI> {
    inner: NeoPixelRgbMinimal<SPI>,
}

impl<SPI: SpiBus> Ws2812bMinimal<SPI> {
    /// Create a new `Ws2812bMinimal`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to `MAX_PIXELS`).
    pub fn new(spi: SPI, n: usize) -> Self {
        Self { inner: NeoPixelRgbMinimal::new(spi, n, CHANNEL_ORDER, RESET_BYTES) }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    ///
    /// Each channel is clamped to [0, 255]. Stores G, R, B in the internal
    /// buffer (GRB wire order) then transmits.
    pub fn fill(&mut self, r: u8, g: u8, b: u8) -> Result<(), SPI::Error> {
        self.inner.fill(r, g, b)
    }

    /// Turn off all pixels (fill with black and send).
    ///
    /// Equivalent to `fill(0, 0, 0)`.
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.inner.off()
    }
}

/// WS2812B full driver — extends [`Ws2812bMinimal`] with per-pixel control.
///
/// Adds individual pixel addressing, explicit [`show`](Ws2812bFull::show),
/// global brightness scaling, buffer rotation, and HSV fill.
/// Call [`set_pixel`](Ws2812bFull::set_pixel) to update the buffer,
/// then [`show`](Ws2812bFull::show) to transmit; or use
/// [`fill`](Ws2812bFull::fill) for an immediate all-same-colour update.
pub struct Ws2812bFull<SPI> {
    inner: NeoPixelRgbFull<SPI>,
}

impl<SPI: SpiBus> Ws2812bFull<SPI> {
    /// Create a new `Ws2812bFull`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to `MAX_PIXELS`).
    pub fn new(spi: SPI, n: usize) -> Self {
        Self { inner: NeoPixelRgbFull::new(spi, n, CHANNEL_ORDER, RESET_BYTES) }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    pub fn fill(&mut self, r: u8, g: u8, b: u8) -> Result<(), SPI::Error> {
        self.inner.fill(r, g, b)
    }

    /// Turn off all pixels (fill with black and send).
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.inner.off()
    }

    /// Set one pixel in the buffer without sending.
    ///
    /// Index is clamped to [0, n-1]. Call [`show`](Self::show) to transmit.
    pub fn set_pixel(&mut self, index: usize, r: u8, g: u8, b: u8) {
        self.inner.set_pixel(index, r, g, b)
    }

    /// Transmit the current buffer to the strip, applying brightness scaling.
    ///
    /// Each channel is scaled: `sent = stored * brightness / 255`.
    pub fn show(&mut self) -> Result<(), SPI::Error> {
        self.inner.show()
    }

    /// Get the global brightness scalar (0–255).
    pub fn get_brightness(&self) -> u8 {
        self.inner.get_brightness()
    }

    /// Set the global brightness scalar (0–255).
    ///
    /// Applied non-destructively at [`show`](Self::show) time.
    pub fn set_brightness(&mut self, value: u8) {
        self.inner.set_brightness(value)
    }

    /// Shift the pixel buffer left by `steps` positions (wraps around).
    ///
    /// Does not transmit — call [`show`](Self::show) afterwards.
    pub fn rotate(&mut self, steps: usize) {
        self.inner.rotate(steps)
    }

    /// Fill every pixel with one HSV colour and send to the strip immediately.
    ///
    /// Converts HSV (all inputs 0.0–1.0) to RGB then calls [`fill`](Self::fill).
    pub fn fill_hsv(&mut self, h: f32, s: f32, v: f32) -> Result<(), SPI::Error> {
        self.inner.fill_hsv(h, s, v)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    // Ws2812bFull is generic over `SpiBus` directly (NeoPixelConnection
    // encodes NZR bit timing onto raw SPI internally - unlike every other
    // Rust chip here, which is generic over `I2c`/`SpiDevice` and lets the
    // mock see undecoded register bytes). NeoPixelConnection::write()'s
    // `encode()` is private to its own module, so this mirrors that exact
    // algorithm (0 -> 0b100 triplet, 1 -> 0b110 triplet, MSB-first, +16
    // trailing zero bytes for reset) to compute the exact SPI bytes the
    // mock should expect - not a shortcut around the driver, since pixel
    // buffer manipulation, clamping, and brightness scaling all still run
    // for real before this encodes whatever they produced.
    fn encode(data: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(data.len() * 3 + 16);
        for &byte in data {
            let mut bits: u32 = 0;
            for bit in (0..8).rev() {
                bits = (bits << 3) | if (byte >> bit) & 1 == 1 { 0b110 } else { 0b100 };
            }
            out.push(((bits >> 16) & 0xFF) as u8);
            out.push(((bits >> 8) & 0xFF) as u8);
            out.push((bits & 0xFF) as u8);
        }
        out.extend(std::iter::repeat(0u8).take(16));
        out
    }

    const N: usize = 4;

    #[test]
    fn fill_transmits_grb_order_and_clamps() {
        let expected = encode(&[0x22, 0x11, 0x33].repeat(N));
        let spi = SpiMock::new(&[SpiTransaction::write_vec(expected)]);
        let mut sensor = Ws2812bFull::new(spi, N);
        sensor.fill(0x11, 0x22, 0x33).unwrap();
        sensor.inner.inner.conn.spi.done();
    }

    #[test]
    fn set_pixel_then_show_and_index_clamp() {
        let mut buf = vec![0u8; N * 3];
        buf[3] = 0xBB; buf[4] = 0xAA; buf[5] = 0xCC; // pixel 1, GRB
        let mut buf2 = buf.clone();
        buf2[(N - 1) * 3] = 0x02; buf2[(N - 1) * 3 + 1] = 0x01; buf2[(N - 1) * 3 + 2] = 0x03; // clamped index 99 -> N-1

        let spi = SpiMock::new(&[
            SpiTransaction::write_vec(encode(&buf)),
            SpiTransaction::write_vec(encode(&buf2)),
        ]);
        let mut sensor = Ws2812bFull::new(spi, N);
        sensor.set_pixel(1, 0xAA, 0xBB, 0xCC);
        sensor.show().unwrap();
        sensor.set_pixel(99, 0x01, 0x02, 0x03);
        sensor.show().unwrap();
        sensor.inner.inner.conn.spi.done();
    }

    #[test]
    fn brightness_scaling() {
        let stored: [u8; 3] = [200, 100, 50]; // r, g, b for pixel 0
        let bri: u16 = 128;
        let scaled_g = (stored[1] as u16 * bri / 255) as u8;
        let scaled_r = (stored[0] as u16 * bri / 255) as u8;
        let scaled_b = (stored[2] as u16 * bri / 255) as u8;
        let mut expected_buf = vec![0u8; N * 3];
        expected_buf[0] = scaled_g;
        expected_buf[1] = scaled_r;
        expected_buf[2] = scaled_b;

        let spi = SpiMock::new(&[SpiTransaction::write_vec(encode(&expected_buf))]);
        let mut sensor = Ws2812bFull::new(spi, N);
        sensor.set_brightness(128);
        sensor.set_pixel(0, stored[0], stored[1], stored[2]);
        sensor.show().unwrap();
        assert_eq!(sensor.get_brightness(), 128);
        sensor.inner.inner.conn.spi.done();
    }

    #[test]
    fn rotate_shifts_left_by_whole_pixels() {
        // Pixels (r-only, for clarity): [1,0,0], [2,0,0], [3,0,0], [4,0,0]
        // stored GRB: g=0 for all, r at offset+1. After rotate(1), every
        // pixel shifts left by one whole pixel and wraps: [2,0,0], [3,0,0],
        // [4,0,0], [1,0,0].
        let expected: Vec<u8> = vec![0, 2, 0, 0, 3, 0, 0, 4, 0, 0, 1, 0];

        let spi = SpiMock::new(&[SpiTransaction::write_vec(encode(&expected))]);
        let mut sensor = Ws2812bFull::new(spi, N);
        sensor.set_pixel(0, 1, 0, 0);
        sensor.set_pixel(1, 2, 0, 0);
        sensor.set_pixel(2, 3, 0, 0);
        sensor.set_pixel(3, 4, 0, 0);
        sensor.rotate(1);
        sensor.show().unwrap();
        sensor.inner.inner.conn.spi.done();
    }

    #[test]
    fn fill_hsv_red() {
        // Pure red: h=0, s=1, v=1 -> RGB (255, 0, 0).
        let expected = encode(&[0x00, 0xFF, 0x00].repeat(N)); // GRB
        let spi = SpiMock::new(&[SpiTransaction::write_vec(expected)]);
        let mut sensor = Ws2812bFull::new(spi, N);
        sensor.fill_hsv(0.0, 1.0, 1.0).unwrap();
        sensor.inner.inner.conn.spi.done();
    }
}
