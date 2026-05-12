//! WS2812B addressable RGB LED strip driver (Worldsemi).
//!
//! Drives a chain of WS2812B pixels over a [`NeoPixelTransport`].
//! Maintains an internal GRB buffer; [`Ws2812bMinimal::fill`] writes every
//! pixel and transmits immediately.
//!
//! # Pixel limit
//! The internal buffer holds up to `MAX_PIXELS` (256) pixels (768 GRB bytes).
//! Pixel indices beyond this limit are clamped silently.

use embedded_hal::spi::SpiBus;
use crate::transport::neopixel::NeoPixelTransport;

/// Maximum supported pixel count for the internal GRB buffer.
pub const MAX_PIXELS: usize = 256;
const MAX_BUF: usize = MAX_PIXELS * 3;

/// WS2812B minimal driver — fill the entire strip with one colour.
///
/// Wraps a [`NeoPixelTransport`] and manages an internal GRB pixel buffer.
/// [`fill`](Ws2812bMinimal::fill) updates every pixel and transmits immediately;
/// [`off`](Ws2812bMinimal::off) is shorthand for `fill(0, 0, 0)`.
pub struct Ws2812bMinimal<SPI> {
    transport: NeoPixelTransport<SPI>,
    n: usize,
    buf: heapless::Vec<u8, MAX_BUF>,
}

impl<SPI: SpiBus> Ws2812bMinimal<SPI> {
    /// Create a new `Ws2812bMinimal`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    pub fn new(spi: SPI, n: usize) -> Self {
        let n = n.min(MAX_PIXELS);
        let mut buf = heapless::Vec::new();
        buf.resize_default(n * 3).ok();
        Self {
            transport: NeoPixelTransport::new(spi),
            n,
            buf,
        }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    ///
    /// Each channel is clamped to [0, 255]. Stores G, R, B in the internal
    /// buffer (GRB wire order) then calls [`NeoPixelTransport::write`].
    pub fn fill(&mut self, r: u8, g: u8, b: u8) -> Result<(), SPI::Error> {
        for i in 0..self.n {
            self.buf[i * 3]     = g;
            self.buf[i * 3 + 1] = r;
            self.buf[i * 3 + 2] = b;
        }
        self.transport.write(&self.buf[..self.n * 3])
    }

    /// Turn off all pixels (fill with black and send).
    ///
    /// Equivalent to `fill(0, 0, 0)`.
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.fill(0, 0, 0)
    }
}

/// WS2812B full driver — extends [`Ws2812bMinimal`] with per-pixel control.
///
/// Adds individual pixel addressing, explicit [`show`](Ws2812bFull::show),
/// global brightness scaling, buffer rotation, and HSV fill.
/// Call [`set_pixel`](Ws2812bFull::set_pixel) to update the buffer,
/// then [`show`](Ws2812bFull::show) to transmit; or use the inherited
/// [`fill`](Ws2812bMinimal::fill) for an immediate all-same-colour update.
pub struct Ws2812bFull<SPI> {
    inner: Ws2812bMinimal<SPI>,
    brightness: u8,
}

impl<SPI: SpiBus> Ws2812bFull<SPI> {
    /// Create a new `Ws2812bFull`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    pub fn new(spi: SPI, n: usize) -> Self {
        Self {
            inner: Ws2812bMinimal::new(spi, n),
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
    /// Index is clamped to [0, n-1]. Call [`show`](Self::show) to transmit.
    pub fn set_pixel(&mut self, index: usize, r: u8, g: u8, b: u8) {
        let index = index.min(self.inner.n.saturating_sub(1));
        self.inner.buf[index * 3]     = g;
        self.inner.buf[index * 3 + 1] = r;
        self.inner.buf[index * 3 + 2] = b;
    }

    /// Transmit the current buffer to the strip, applying brightness scaling.
    ///
    /// Each channel is scaled: `sent = stored * brightness / 255`.
    pub fn show(&mut self) -> Result<(), SPI::Error> {
        let bri = self.brightness;
        let n3 = self.inner.n * 3;
        if bri == 255 {
            return self.inner.transport.write(&self.inner.buf[..n3]);
        }
        let mut scaled: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        scaled.resize_default(n3).ok();
        for i in 0..n3 {
            scaled[i] = (self.inner.buf[i] as u16 * bri as u16 / 255) as u8;
        }
        self.inner.transport.write(&scaled[..n3])
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
        let bytes = steps * 3;
        let n3 = n * 3;
        let mut tmp: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        tmp.extend_from_slice(&self.inner.buf[..bytes]).ok();
        self.inner.buf.copy_within(bytes..n3, 0);
        for (i, &v) in tmp.iter().enumerate() {
            self.inner.buf[n3 - bytes + i] = v;
        }
    }

    /// Fill every pixel with one HSV colour and send to the strip immediately.
    ///
    /// Converts HSV (all inputs 0.0–1.0) to RGB then calls [`fill`](Self::fill).
    pub fn fill_hsv(&mut self, h: f32, s: f32, v: f32) -> Result<(), SPI::Error> {
        let (r, g, b) = hsv_to_rgb(h, s, v);
        self.fill(r, g, b)
    }
}

fn hsv_to_rgb(h: f32, s: f32, v: f32) -> (u8, u8, u8) {
    if s == 0.0 {
        let c = (v * 255.0) as u8;
        return (c, c, c);
    }
    let i = (h * 6.0) as i32;
    let f = h * 6.0 - i as f32;
    let p = (v * (1.0 - s) * 255.0) as u8;
    let q = (v * (1.0 - s * f) * 255.0) as u8;
    let t = (v * (1.0 - s * (1.0 - f)) * 255.0) as u8;
    let vv = (v * 255.0) as u8;
    match i % 6 {
        0 => (vv, t,  p),
        1 => (q,  vv, p),
        2 => (p,  vv, t),
        3 => (p,  q,  vv),
        4 => (t,  p,  vv),
        _ => (vv, p,  q),
    }
}
