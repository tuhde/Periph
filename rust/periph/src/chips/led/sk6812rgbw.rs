//! SK6812RGBW addressable RGBW LED strip driver.
//!
//! Drives a chain of SK6812RGBW pixels over a [`NeoPixelConnection`].
//! Maintains an internal GRBW buffer; [`Sk6812RgbwMinimal::fill`] writes every
//! pixel and transmits immediately. Each pixel has four channels: red, green,
//! blue, and white (dedicated white LED element).
//!
//! # Pixel limit
//! The internal buffer holds up to `MAX_PIXELS` (256) pixels (1024 GRBW bytes).
//! Pixel indices beyond this limit are clamped silently.

use embedded_hal::spi::SpiBus;
use crate::connection::neopixel::NeoPixelConnection;

/// Maximum supported pixel count for the internal GRBW buffer.
pub const MAX_PIXELS: usize = 256;
const MAX_BUF: usize = MAX_PIXELS * 4;

/// SK6812RGBW minimal driver — fill the entire strip with one colour.
///
/// Wraps a [`NeoPixelConnection`] and manages an internal GRBW pixel buffer.
/// [`fill`](Sk6812RgbwMinimal::fill) updates every pixel and transmits immediately;
/// [`off`](Sk6812RgbwMinimal::off) is shorthand for `fill(0, 0, 0, 0)`.
pub struct Sk6812RgbwMinimal<SPI> {
    conn: NeoPixelConnection<SPI>,
    n: usize,
    buf: heapless::Vec<u8, MAX_BUF>,
}

impl<SPI: SpiBus> Sk6812RgbwMinimal<SPI> {
    /// Create a new `Sk6812RgbwMinimal`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    pub fn new(spi: SPI, n: usize) -> Self {
        let n = n.min(MAX_PIXELS);
        let mut buf = heapless::Vec::new();
        buf.resize_default(n * 4).ok();
        Self {
            conn: NeoPixelConnection::new(spi),
            n,
            buf,
        }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    ///
    /// Stores G, R, B, W in the internal buffer (GRBW wire order) then calls
    /// [`NeoPixelConnection::write`]. `w=0` for RGB-only usage.
    pub fn fill(&mut self, r: u8, g: u8, b: u8, w: u8) -> Result<(), SPI::Error> {
        for i in 0..self.n {
            self.buf[i * 4]     = g;
            self.buf[i * 4 + 1] = r;
            self.buf[i * 4 + 2] = b;
            self.buf[i * 4 + 3] = w;
        }
        self.conn.write_ext(&self.buf[..self.n * 4], 24)
    }

    /// Turn off all pixels (fill with all zeros and send).
    ///
    /// Equivalent to `fill(0, 0, 0, 0)`.
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.fill(0, 0, 0, 0)
    }
}

/// SK6812RGBW full driver — extends [`Sk6812RgbwMinimal`] with per-pixel control.
///
/// Adds individual pixel addressing, explicit [`show`](Sk6812RgbwFull::show),
/// global brightness scaling, buffer rotation, and HSV fill.
/// Call [`set_pixel`](Sk6812RgbwFull::set_pixel) to update the buffer,
/// then [`show`](Sk6812RgbwFull::show) to transmit; or use the inherited
/// [`fill`](Sk6812RgbwMinimal::fill) for an immediate all-same-colour update.
pub struct Sk6812RgbwFull<SPI> {
    inner: Sk6812RgbwMinimal<SPI>,
    brightness: u8,
}

impl<SPI: SpiBus> Sk6812RgbwFull<SPI> {
    /// Create a new `Sk6812RgbwFull`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    pub fn new(spi: SPI, n: usize) -> Self {
        Self {
            inner: Sk6812RgbwMinimal::new(spi, n),
            brightness: 255,
        }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    pub fn fill(&mut self, r: u8, g: u8, b: u8, w: u8) -> Result<(), SPI::Error> {
        self.inner.fill(r, g, b, w)
    }

    /// Turn off all pixels (fill with all zeros and send).
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.inner.off()
    }

    /// Set one pixel in the buffer without sending.
    ///
    /// Index is clamped to [0, n-1]. Call [`show`](Self::show) to transmit.
    pub fn set_pixel(&mut self, index: usize, r: u8, g: u8, b: u8, w: u8) {
        let index = index.min(self.inner.n.saturating_sub(1));
        self.inner.buf[index * 4]     = g;
        self.inner.buf[index * 4 + 1] = r;
        self.inner.buf[index * 4 + 2] = b;
        self.inner.buf[index * 4 + 3] = w;
    }

    /// Transmit the current buffer to the strip, applying brightness scaling.
    ///
    /// Each channel is scaled: `sent = stored * brightness / 255`.
    pub fn show(&mut self) -> Result<(), SPI::Error> {
        let bri = self.brightness;
        let n4 = self.inner.n * 4;
        if bri == 255 {
            return self.inner.conn.write_ext(&self.inner.buf[..n4], 24);
        }
        let mut scaled: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        scaled.resize_default(n4).ok();
        for i in 0..n4 {
            scaled[i] = (self.inner.buf[i] as u16 * bri as u16 / 255) as u8;
        }
        self.inner.conn.write_ext(&scaled[..n4], 24)
    }

    /// Get the global brightness scalar (0–255).
    pub fn get_brightness(&self) -> u8 {
        self.brightness
    }

    /// Set the global brightness scalar (0–255).
    ///
    /// Applied non-destructively at [`show`](Self::show) time.
    pub fn set_brightness(&mut self, value: u8) {
        self.brightness = value;
    }

    /// Shift the pixel buffer left by `steps` positions (wraps around).
    ///
    /// Does not transmit — call [`show`](Self::show) afterwards.
    pub fn rotate(&mut self, steps: usize) {
        let n = self.inner.n;
        if n == 0 { return; }
        let steps = steps % n;
        if steps == 0 { return; }
        let bytes = steps * 4;
        let n4 = n * 4;
        let mut tmp: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        tmp.extend_from_slice(&self.inner.buf[..bytes]).ok();
        self.inner.buf.copy_within(bytes..n4, 0);
        for (i, &v) in tmp.iter().enumerate() {
            self.inner.buf[n4 - bytes + i] = v;
        }
    }

    /// Fill every pixel with one HSV colour and send to the strip immediately.
    ///
    /// Converts HSV (all inputs 0.0–1.0) to RGB (w=0) then calls [`fill`](Self::fill).
    pub fn fill_hsv(&mut self, h: f32, s: f32, v: f32) -> Result<(), SPI::Error> {
        let (r, g, b) = hsv_to_rgb(h, s, v);
        self.fill(r, g, b, 0)
    }
}

use super::color::hsv_to_rgb;

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    // Same reasoning as ws2812b.rs's test module: mirrors
    // NeoPixelConnection's private encode() (0 -> 0b100 triplet, 1 -> 0b110
    // triplet, MSB-first) to compute the exact SPI bytes the mock should
    // expect, parametrized by reset_bytes since this chip requests 24
    // (~80us) instead of WS2812B's default 16 (~53us) - see
    // rust/periph/src/connection/neopixel.rs's write_ext().
    fn encode(data: &[u8], reset_bytes: usize) -> Vec<u8> {
        let mut out = Vec::with_capacity(data.len() * 3 + reset_bytes);
        for &byte in data {
            let mut bits: u32 = 0;
            for bit in (0..8).rev() {
                bits = (bits << 3) | if (byte >> bit) & 1 == 1 { 0b110 } else { 0b100 };
            }
            out.push(((bits >> 16) & 0xFF) as u8);
            out.push(((bits >> 8) & 0xFF) as u8);
            out.push((bits & 0xFF) as u8);
        }
        out.extend(std::iter::repeat(0u8).take(reset_bytes));
        out
    }

    const N: usize = 3;

    #[test]
    fn fill_transmits_grbw_order() {
        let expected = encode(&[0x22, 0x11, 0x33, 0x44].repeat(N), 24);
        let spi = SpiMock::new(&[SpiTransaction::write_vec(expected)]);
        let mut sensor = Sk6812RgbwFull::new(spi, N);
        sensor.fill(0x11, 0x22, 0x33, 0x44).unwrap();
        sensor.inner.conn.spi.done();
    }

    #[test]
    fn fill_white_defaults_zero() {
        let expected = encode(&[0x20, 0x10, 0x30, 0x00].repeat(N), 24);
        let spi = SpiMock::new(&[SpiTransaction::write_vec(expected)]);
        let mut sensor = Sk6812RgbwFull::new(spi, N);
        sensor.fill(0x10, 0x20, 0x30, 0).unwrap();
        sensor.inner.conn.spi.done();
    }

    #[test]
    fn set_pixel_then_show_and_index_clamp() {
        let mut buf = vec![0u8; N * 4];
        buf[4] = 0xBB; buf[5] = 0xAA; buf[6] = 0xCC; buf[7] = 0xDD; // pixel 1, GRBW
        let mut buf2 = buf.clone();
        buf2[(N - 1) * 4] = 0x06; buf2[(N - 1) * 4 + 1] = 0x05;
        buf2[(N - 1) * 4 + 2] = 0x07; buf2[(N - 1) * 4 + 3] = 0x08; // clamped index 99 -> N-1

        let spi = SpiMock::new(&[
            SpiTransaction::write_vec(encode(&buf, 24)),
            SpiTransaction::write_vec(encode(&buf2, 24)),
        ]);
        let mut sensor = Sk6812RgbwFull::new(spi, N);
        sensor.set_pixel(1, 0xAA, 0xBB, 0xCC, 0xDD);
        sensor.show().unwrap();
        sensor.set_pixel(99, 0x05, 0x06, 0x07, 0x08);
        sensor.show().unwrap();
        sensor.inner.conn.spi.done();
    }

    #[test]
    fn brightness_scaling() {
        let stored: [u8; 4] = [200, 100, 50, 40]; // r, g, b, w for pixel 0
        let bri: u16 = 128;
        let mut expected_buf = vec![0u8; N * 4];
        expected_buf[0] = (stored[1] as u16 * bri / 255) as u8; // g
        expected_buf[1] = (stored[0] as u16 * bri / 255) as u8; // r
        expected_buf[2] = (stored[2] as u16 * bri / 255) as u8; // b
        expected_buf[3] = (stored[3] as u16 * bri / 255) as u8; // w

        let spi = SpiMock::new(&[SpiTransaction::write_vec(encode(&expected_buf, 24))]);
        let mut sensor = Sk6812RgbwFull::new(spi, N);
        sensor.set_brightness(128);
        sensor.set_pixel(0, stored[0], stored[1], stored[2], stored[3]);
        sensor.show().unwrap();
        sensor.inner.conn.spi.done();
    }

    #[test]
    fn rotate_shifts_left_by_whole_pixels() {
        // Pixels (r-only): [1,0,0,0], [2,0,0,0], [3,0,0,0]. After rotate(1):
        // [2,0,0,0], [3,0,0,0], [1,0,0,0].
        let expected: Vec<u8> = vec![0, 2, 0, 0, 0, 3, 0, 0, 0, 1, 0, 0];

        let spi = SpiMock::new(&[SpiTransaction::write_vec(encode(&expected, 24))]);
        let mut sensor = Sk6812RgbwFull::new(spi, N);
        sensor.set_pixel(0, 1, 0, 0, 0);
        sensor.set_pixel(1, 2, 0, 0, 0);
        sensor.set_pixel(2, 3, 0, 0, 0);
        sensor.rotate(1);
        sensor.show().unwrap();
        sensor.inner.conn.spi.done();
    }

    #[test]
    fn fill_hsv_red() {
        // Pure red: h=0, s=1, v=1 -> RGB (255, 0, 0), white=0.
        let expected = encode(&[0x00, 0xFF, 0x00, 0x00].repeat(N), 24); // GRBW
        let spi = SpiMock::new(&[SpiTransaction::write_vec(expected)]);
        let mut sensor = Sk6812RgbwFull::new(spi, N);
        sensor.fill_hsv(0.0, 1.0, 1.0).unwrap();
        sensor.inner.conn.spi.done();
    }
}
