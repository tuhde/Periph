//! APA102 addressable RGB LED strip driver.
//!
//! Drives a chain of APA102 pixels over a standard SPI connection (Mode 0, MSB first).
//! Maintains an internal BGR+brightness buffer; [`Apa102Minimal::fill`] writes every
//! pixel and transmits the full frame (start + pixels + end) immediately.
//!
//! The APA102 frame format is:
//!   start frame: 4 zero-bytes (0x00 × 4)
//!   pixel data:  n × 4 bytes [0xE0|brightness, B, G, R] (BGR wire order)
//!   end frame:   max(4, (n+15)//16) bytes of 0xFF
//!
//! # Pixel limit
//! The internal buffer holds up to `MAX_PIXELS` (256) pixels (1024 BGR+brightness bytes).
//! Pixel indices beyond this limit are clamped silently.

use embedded_hal::spi::SpiDevice;
use super::color::hsv_to_rgb;

/// Maximum supported pixel count for the internal BGR+brightness buffer.
pub const MAX_PIXELS: usize = 256;
const MAX_BUF: usize = MAX_PIXELS * 4;

/// APA102 minimal driver — fill the entire strip with one colour.
///
/// Wraps an [`SpiDevice`] and manages an internal BGR+brightness pixel buffer.
/// [`fill`](Apa102Minimal::fill) updates every pixel and transmits immediately;
/// [`off`](Apa102Minimal::off) is shorthand for `fill(0, 0, 0)`.
pub struct Apa102Minimal<SPI> {
    spi: SPI,
    n: usize,
    buf: heapless::Vec<u8, MAX_BUF>,
}

impl<SPI: SpiDevice> Apa102Minimal<SPI> {
    /// Create a new `Apa102Minimal`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 1 MHz, mode 0, MSB-first (APA102 synchronous protocol).
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    pub fn new(spi: SPI, n: usize) -> Self {
        let n = n.min(MAX_PIXELS);
        let mut buf = heapless::Vec::new();
        buf.resize_default(n * 4).ok();
        // Initialize buffer with hardware brightness=31, all channels off
        for i in 0..n {
            buf[i * 4]     = 0xE0 | 31;  // brightness byte (3 high bits = 1)
            buf[i * 4 + 1] = 0;          // blue
            buf[i * 4 + 2] = 0;          // green
            buf[i * 4 + 3] = 0;          // red
        }
        Self { spi, n, buf }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    ///
    /// Each channel is clamped to [0, 255]. Stores brightness/B/G/R in the
    /// internal buffer (BGR wire order with hardware brightness byte first)
    /// then transmits the full APA102 frame.
    ///
    /// # Arguments
    /// * `r` — Red channel (0–255).
    /// * `g` — Green channel (0–255).
    /// * `b` — Blue channel (0–255).
    pub fn fill(&mut self, r: u8, g: u8, b: u8) -> Result<(), SPI::Error> {
        for i in 0..self.n {
            self.buf[i * 4]     = 0xE0 | 31;  // hardware brightness = 31 (max)
            self.buf[i * 4 + 1] = b;          // blue
            self.buf[i * 4 + 2] = g;          // green
            self.buf[i * 4 + 3] = r;          // red
        }
        self._send_frame()
    }

    /// Turn off all pixels (fill with black and send).
    ///
    /// Equivalent to `fill(0, 0, 0)`.
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.fill(0, 0, 0)
    }

    /// Send the full APA102 frame (start + pixel buffer + end).
    fn _send_frame(&mut self) -> Result<(), SPI::Error> {
        let end_bytes = ((self.n + 15) / 16).max(4);
        let mut frame: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        frame.resize_default(4 + self.n * 4 + end_bytes).ok();
        // Start frame: 4 zero bytes
        frame[0] = 0x00;
        frame[1] = 0x00;
        frame[2] = 0x00;
        frame[3] = 0x00;
        // Pixel data
        for i in 0..self.n * 4 {
            frame[4 + i] = self.buf[i];
        }
        // End frame: 0xFF bytes
        for i in 0..end_bytes {
            frame[4 + self.n * 4 + i] = 0xFF;
        }
        self.spi.write(&frame)
    }
}

/// APA102 full driver — extends [`Apa102Minimal`] with per-pixel control.
///
/// Adds individual pixel addressing with per-pixel hardware brightness,
/// explicit [`show`](Apa102Full::show), global software brightness scaling,
/// buffer rotation, and HSV fill. Call [`set_pixel`](Apa102Full::set_pixel)
/// to update the buffer, then [`show`](Apa102Full::show) to transmit; or
/// use the inherited [`fill`](Apa102Minimal::fill) for an immediate
/// all-same-colour update.
pub struct Apa102Full<SPI> {
    inner: Apa102Minimal<SPI>,
    brightness: u8,
}

impl<SPI: SpiDevice> Apa102Full<SPI> {
    /// Create a new `Apa102Full`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 1 MHz, mode 0, MSB-first (APA102 synchronous protocol).
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    pub fn new(spi: SPI, n: usize) -> Self {
        Self {
            inner: Apa102Minimal::new(spi, n),
            brightness: 255,
        }
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
    /// Index is clamped to [0, n-1]; each RGB channel is clamped to [0, 255];
    /// pixel_brightness is clamped to [0, 31]. Call [`show`](Self::show) to transmit.
    ///
    /// # Arguments
    /// * `index`             — Zero-based pixel index.
    /// * `r`                 — Red channel (0–255).
    /// * `g`                 — Green channel (0–255).
    /// * `b`                 — Blue channel (0–255).
    /// * `pixel_brightness`  — Per-pixel hardware brightness 0–31 (default 31).
    pub fn set_pixel(&mut self, index: usize, r: u8, g: u8, b: u8, pixel_brightness: u8) {
        let index = index.min(self.inner.n.saturating_sub(1));
        let pixel_brightness = pixel_brightness.min(31);
        self.inner.buf[index * 4]     = 0xE0 | pixel_brightness;
        self.inner.buf[index * 4 + 1] = b;
        self.inner.buf[index * 4 + 2] = g;
        self.inner.buf[index * 4 + 3] = r;
    }

    /// Set one pixel in the buffer without sending (default pixel_brightness=31).
    pub fn set_pixel_default_brightness(&mut self, index: usize, r: u8, g: u8, b: u8) {
        self.set_pixel(index, r, g, b, 31);
    }

    /// Transmit the current buffer to the strip, applying software brightness scaling.
    ///
    /// Each RGB channel value is scaled: `sent = stored * brightness / 255`.
    /// The per-pixel hardware brightness byte is NOT scaled.
    pub fn show(&mut self) -> Result<(), SPI::Error> {
        let bri = self.brightness;
        let end_bytes = ((self.inner.n + 15) / 16).max(4);
        let pixel_data_len = self.inner.n * 4;
        let total_len = 4 + pixel_data_len + end_bytes;

        let mut frame: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        frame.resize_default(total_len).ok();
        frame[0] = 0x00;
        frame[1] = 0x00;
        frame[2] = 0x00;
        frame[3] = 0x00;

        if bri == 255 {
            for i in 0..pixel_data_len {
                frame[4 + i] = self.inner.buf[i];
            }
        } else {
            // Scale RGB channels, leave hardware brightness byte unchanged
            for i in 0..self.inner.n {
                let base = i * 4;
                frame[4 + base]     = self.inner.buf[base];                                        // hardware brightness
                frame[4 + base + 1] = (self.inner.buf[base + 1] as u16 * bri as u16 / 255) as u8;  // blue
                frame[4 + base + 2] = (self.inner.buf[base + 2] as u16 * bri as u16 / 255) as u8;  // green
                frame[4 + base + 3] = (self.inner.buf[base + 3] as u16 * bri as u16 / 255) as u8;  // red
            }
        }

        for i in 0..end_bytes {
            frame[4 + pixel_data_len + i] = 0xFF;
        }

        self.inner.spi.write(&frame)
    }

    /// Set multiple pixels from an array of [r, g, b] or [r, g, b, pixel_brightness].
    ///
    /// Each element is [r, g, b] or [r, g, b, pixel_brightness]. Missing brightness
    /// defaults to 31. Extra entries beyond the strip length are ignored.
    /// Does not transmit — call [`show`](Self::show) afterwards.
    ///
    /// # Arguments
    /// * `colors` — Slice of pixel data arrays.
    pub fn set_pixels(&mut self, colors: &[&[u8]]) {
        for (i, color) in colors.iter().enumerate() {
            if i >= self.inner.n { break; }
            let r = color[0];
            let g = color[1];
            let b = color[2];
            let pixel_brightness = color.get(3).copied().unwrap_or(31).min(31);
            self.inner.buf[i * 4]     = 0xE0 | pixel_brightness;
            self.inner.buf[i * 4 + 1] = b;
            self.inner.buf[i * 4 + 2] = g;
            self.inner.buf[i * 4 + 3] = r;
        }
    }

    /// Get the global software brightness scalar (0–255).
    pub fn get_brightness(&self) -> u8 {
        self.brightness
    }

    /// Set the global software brightness scalar (0–255).
    ///
    /// Applied non-destructively at [`show`](Self::show) time: stored RGB values are unchanged.
    /// The per-pixel hardware brightness byte is NOT affected.
    ///
    /// # Arguments
    /// * `value` — Brightness (0 = off, 255 = full).
    pub fn set_brightness(&mut self, value: u8) {
        self.brightness = value;
    }

    /// Shift the pixel buffer left by `steps` whole-pixel positions (wraps around).
    ///
    /// Each step shifts 4 bytes (one BGR+brightness pixel). Does not transmit —
    /// call [`show`](Self::show) afterwards.
    ///
    /// # Arguments
    /// * `steps` — Number of pixel positions to shift left (default 1).
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
    /// Converts HSV (all inputs 0.0–1.0) to RGB then calls [`fill`](Self::fill)
    /// at hardware brightness 31.
    ///
    /// # Arguments
    /// * `h` — Hue (0.0–1.0).
    /// * `s` — Saturation (0.0–1.0).
    /// * `v` — Value/brightness (0.0–1.0).
    pub fn fill_hsv(&mut self, h: f32, s: f32, v: f32) -> Result<(), SPI::Error> {
        let (r, g, b) = hsv_to_rgb(h, s, v);
        self.fill(r, g, b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    const N: usize = 4;

    fn expected_frame(n: usize, pixels: &[u8], end_bytes: usize) -> Vec<u8> {
        let mut frame = Vec::with_capacity(4 + n * 4 + end_bytes);
        frame.extend_from_slice(&[0x00, 0x00, 0x00, 0x00]);
        frame.extend_from_slice(pixels);
        frame.extend(std::iter::repeat(0xFF).take(end_bytes));
        frame
    }

    #[test]
    fn fill_transmits_bgr_with_brightness_and_clamps() {
        let end_bytes = ((N + 15) / 16).max(4);
        // pixels: [0xFF, 0x33, 0x22, 0x11] repeated N times (brightness=31, B=0x33, G=0x22, R=0x11)
        let mut pixels = Vec::with_capacity(N * 4);
        for _ in 0..N {
            pixels.extend_from_slice(&[0xFF, 0x33, 0x22, 0x11]);
        }
        let expected = expected_frame(N, &pixels, end_bytes);
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.fill(0x11, 0x22, 0x33).unwrap();
        sensor.inner.spi.done();
    }

    #[test]
    fn set_pixel_then_show_and_index_clamp() {
        let end_bytes = ((N + 15) / 16).max(4);
        // pixel 1: [0xFF, 0xCC, 0xBB, 0xAA] (brightness=31, B=0xCC, G=0xBB, R=0xAA);
        // every other pixel keeps the constructor's default brightness byte (0xFF)
        let mut pixels1 = vec![0u8; N * 4];
        for i in 0..N { pixels1[i * 4] = 0xFF; }
        pixels1[4] = 0xFF; pixels1[5] = 0xCC; pixels1[6] = 0xBB; pixels1[7] = 0xAA;
        // clamped index 99 -> N-1: [0xFF, 0x03, 0x02, 0x01]
        let mut pixels2 = pixels1.clone();
        pixels2[(N - 1) * 4] = 0xFF; pixels2[(N - 1) * 4 + 1] = 0x03; pixels2[(N - 1) * 4 + 2] = 0x02; pixels2[(N - 1) * 4 + 3] = 0x01;

        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels1, end_bytes)),
            SpiTransaction::transaction_end(),
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels2, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.set_pixel(1, 0xAA, 0xBB, 0xCC, 31);
        sensor.show().unwrap();
        sensor.set_pixel(99, 0x01, 0x02, 0x03, 31);
        sensor.show().unwrap();
        sensor.inner.spi.done();
    }

    #[test]
    fn set_pixel_with_custom_brightness() {
        let end_bytes = ((N + 15) / 16).max(4);
        // pixel 0: brightness=16 -> 0xE0|16 = 0xF0, B=0x30, G=0x20, R=0x10;
        // every other pixel keeps the constructor's default brightness byte (0xFF)
        let mut pixels = vec![0u8; N * 4];
        for i in 0..N { pixels[i * 4] = 0xFF; }
        pixels[0] = 0xF0; pixels[1] = 0x30; pixels[2] = 0x20; pixels[3] = 0x10;
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.set_pixel(0, 0x10, 0x20, 0x30, 16);
        sensor.show().unwrap();
        sensor.inner.spi.done();
    }

    #[test]
    fn set_pixels_basic() {
        let end_bytes = ((N + 15) / 16).max(4);
        // pixel 0: [0xFF, 0x30, 0x20, 0x10]
        // pixel 1: [0xFF, 0x60, 0x50, 0x40]
        // pixel 2: [0xFF, 0x90, 0x80, 0x70]
        // pixel 3: [0xFF, 0xC0, 0xB0, 0xA0]
        let mut pixels = vec![0u8; N * 4];
        pixels[0] = 0xFF; pixels[1] = 0x30; pixels[2] = 0x20; pixels[3] = 0x10;
        pixels[4] = 0xFF; pixels[5] = 0x60; pixels[6] = 0x50; pixels[7] = 0x40;
        pixels[8] = 0xFF; pixels[9] = 0x90; pixels[10] = 0x80; pixels[11] = 0x70;
        pixels[12] = 0xFF; pixels[13] = 0xC0; pixels[14] = 0xB0; pixels[15] = 0xA0;
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.set_pixels(&[
            &[0x10, 0x20, 0x30],
            &[0x40, 0x50, 0x60],
            &[0x70, 0x80, 0x90],
            &[0xA0, 0xB0, 0xC0],
        ]);
        sensor.show().unwrap();
        sensor.inner.spi.done();
    }

    #[test]
    fn set_pixels_with_brightness() {
        let end_bytes = ((N + 15) / 16).max(4);
        // pixel 0: brightness=31 -> 0xFF
        // pixel 1: brightness=16 -> 0xF0
        // pixel 2: brightness=8  -> 0xE8
        // pixel 3: brightness=4  -> 0xE4
        let mut pixels = vec![0u8; N * 4];
        pixels[0] = 0xFF; pixels[1] = 0x30; pixels[2] = 0x20; pixels[3] = 0x10;
        pixels[4] = 0xF0; pixels[5] = 0x60; pixels[6] = 0x50; pixels[7] = 0x40;
        pixels[8] = 0xE8; pixels[9] = 0x90; pixels[10] = 0x80; pixels[11] = 0x70;
        pixels[12] = 0xE4; pixels[13] = 0xC0; pixels[14] = 0xB0; pixels[15] = 0xA0;
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.set_pixels(&[
            &[0x10, 0x20, 0x30, 31],
            &[0x40, 0x50, 0x60, 16],
            &[0x70, 0x80, 0x90, 8],
            &[0xA0, 0xB0, 0xC0, 4],
        ]);
        sensor.show().unwrap();
        sensor.inner.spi.done();
    }

    #[test]
    fn brightness_scaling() {
        let end_bytes = ((N + 15) / 16).max(4);
        // stored: [0xFF, 50, 100, 200] for pixel 0
        // scaled with brightness=128: R=200*128/255=100, G=100*128/255=50, B=50*128/255=25
        // hardware brightness byte (0xFF) unchanged
        let scaled_r = (200u16 * 128 / 255) as u8;
        let scaled_g = (100u16 * 128 / 255) as u8;
        let scaled_b = (50u16 * 128 / 255) as u8;
        // every other pixel keeps its default hardware brightness byte (0xFF, unscaled);
        // its RGB scales to 0 regardless since the stored channels are 0
        let mut pixels = vec![0u8; N * 4];
        for i in 0..N { pixels[i * 4] = 0xFF; }
        pixels[0] = 0xFF; pixels[1] = scaled_b; pixels[2] = scaled_g; pixels[3] = scaled_r;
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.set_brightness(128);
        sensor.set_pixel(0, 200, 100, 50, 31);
        sensor.show().unwrap();
        assert_eq!(sensor.get_brightness(), 128);
        sensor.inner.spi.done();
    }

    #[test]
    fn rotate_shifts_left_by_whole_pixels() {
        let end_bytes = ((N + 15) / 16).max(4);
        // Pixels (r-only): [1,0,0], [2,0,0], [3,0,0], [4,0,0]
        // After rotate(1): [2,0,0], [3,0,0], [4,0,0], [1,0,0]
        // Stored: each pixel [0xFF, 0, 0, r_val]
        let mut pixels = vec![0u8; N * 4];
        pixels[0] = 0xFF; pixels[3] = 2;
        pixels[4] = 0xFF; pixels[7] = 3;
        pixels[8] = 0xFF; pixels[11] = 4;
        pixels[12] = 0xFF; pixels[15] = 1;
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.set_pixel(0, 1, 0, 0, 31);
        sensor.set_pixel(1, 2, 0, 0, 31);
        sensor.set_pixel(2, 3, 0, 0, 31);
        sensor.set_pixel(3, 4, 0, 0, 31);
        sensor.rotate(1);
        sensor.show().unwrap();
        sensor.inner.spi.done();
    }

    #[test]
    fn fill_hsv_red() {
        let end_bytes = ((N + 15) / 16).max(4);
        // Pure red: h=0, s=1, v=1 -> RGB (255, 0, 0) -> wire [0xFF, 0, 0, 255]
        let mut pixels = vec![0u8; N * 4];
        for i in 0..N {
            pixels[i * 4] = 0xFF; pixels[i * 4 + 3] = 255;
        }
        let spi = SpiMock::new(&[
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(expected_frame(N, &pixels, end_bytes)),
            SpiTransaction::transaction_end(),
        ]);
        let mut sensor = Apa102Full::new(spi, N);
        sensor.fill_hsv(0.0, 1.0, 1.0).unwrap();
        sensor.inner.spi.done();
    }
}