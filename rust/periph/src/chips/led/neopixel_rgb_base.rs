//! Shared driver logic for 3-channel (RGB) NeoPixel-protocol LED chips.
//!
//! Drives a chain of pixels over a [`NeoPixelConnection`]. Maintains an
//! internal buffer in wire order; [`NeoPixelRgbMinimal::fill`] writes every
//! pixel and transmits immediately. Concrete chip drivers (e.g. `Ws2812bMinimal`)
//! wrap these types, fixing `channel_order` and `reset_bytes` for their chip.
//!
//! # Pixel limit
//! The internal buffer holds up to `MAX_PIXELS` (256) pixels (768 bytes).
//! Pixel indices beyond this limit are clamped silently.

use embedded_hal::spi::SpiBus;
use crate::connection::neopixel::NeoPixelConnection;

/// Maximum supported pixel count for the internal RGB buffer.
pub const MAX_PIXELS: usize = 256;
const MAX_BUF: usize = MAX_PIXELS * 3;

/// Shared minimal-tier NeoPixel RGB driver — fill the entire strip with one colour.
///
/// Wraps a [`NeoPixelConnection`] and manages an internal RGB pixel buffer in
/// wire order. [`fill`](Self::fill) updates every pixel and transmits
/// immediately; [`off`](Self::off) is shorthand for `fill(0, 0, 0)`.
pub struct NeoPixelRgbMinimal<SPI> {
    // pub(crate) so concrete chip drivers' own unit tests (e.g. ws2812b.rs's
    // `mod tests`, defined in a different module) can reach the underlying
    // mock's .done() via `sensor.inner.inner.conn.spi` - not part of the
    // public API.
    pub(crate) conn: NeoPixelConnection<SPI>,
    pub(crate) n: usize,
    pub(crate) buf: heapless::Vec<u8, MAX_BUF>,
    channel_order: [usize; 3],
    reset_bytes: usize,
}

impl<SPI: SpiBus> NeoPixelRgbMinimal<SPI> {
    /// Create a new `NeoPixelRgbMinimal`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    /// * `channel_order` — `[iR, iG, iB]`; `wire[k] = [r,g,b][channel_order[k]]`.
    /// * `reset_bytes` — Total trailing zero bytes for this chip's reset pulse.
    pub fn new(spi: SPI, n: usize, channel_order: [usize; 3], reset_bytes: usize) -> Self {
        let n = n.min(MAX_PIXELS);
        let mut buf = heapless::Vec::new();
        buf.resize_default(n * 3).ok();
        Self {
            conn: NeoPixelConnection::new(spi),
            n,
            buf,
            channel_order,
            reset_bytes,
        }
    }

    /// Fill every pixel with one colour and send to the strip immediately.
    ///
    /// Stores the three channels in the internal buffer using this chip's
    /// wire channel order, then calls [`NeoPixelConnection::write_ext`].
    pub fn fill(&mut self, r: u8, g: u8, b: u8) -> Result<(), SPI::Error> {
        let vals = [r, g, b];
        let w0 = vals[self.channel_order[0]];
        let w1 = vals[self.channel_order[1]];
        let w2 = vals[self.channel_order[2]];
        for i in 0..self.n {
            self.buf[i * 3]     = w0;
            self.buf[i * 3 + 1] = w1;
            self.buf[i * 3 + 2] = w2;
        }
        self.conn.write_ext(&self.buf[..self.n * 3], self.reset_bytes)
    }

    /// Turn off all pixels (fill with black and send).
    ///
    /// Equivalent to `fill(0, 0, 0)`.
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.fill(0, 0, 0)
    }
}

/// Shared full-tier NeoPixel RGB driver — per-pixel control, brightness,
/// rotation, and HSV fill.
///
/// Adds individual pixel addressing, explicit [`show`](Self::show),
/// global brightness scaling, buffer rotation, and HSV fill on top of
/// [`NeoPixelRgbMinimal`]. Call [`set_pixel`](Self::set_pixel) to update the
/// buffer, then [`show`](Self::show) to transmit; or use the inherited
/// [`fill`](NeoPixelRgbMinimal::fill) for an immediate all-same-colour update.
pub struct NeoPixelRgbFull<SPI> {
    pub(crate) inner: NeoPixelRgbMinimal<SPI>,
    brightness: u8,
}

impl<SPI: SpiBus> NeoPixelRgbFull<SPI> {
    /// Create a new `NeoPixelRgbFull`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    /// * `channel_order` — `[iR, iG, iB]` wire channel order.
    /// * `reset_bytes` — Total trailing zero bytes for this chip's reset pulse.
    pub fn new(spi: SPI, n: usize, channel_order: [usize; 3], reset_bytes: usize) -> Self {
        Self {
            inner: NeoPixelRgbMinimal::new(spi, n, channel_order, reset_bytes),
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
        let vals = [r, g, b];
        let co = self.inner.channel_order;
        self.inner.buf[index * 3]     = vals[co[0]];
        self.inner.buf[index * 3 + 1] = vals[co[1]];
        self.inner.buf[index * 3 + 2] = vals[co[2]];
    }

    /// Transmit the current buffer to the strip, applying brightness scaling.
    ///
    /// Each channel is scaled: `sent = stored * brightness / 255`.
    pub fn show(&mut self) -> Result<(), SPI::Error> {
        let bri = self.brightness;
        let n3 = self.inner.n * 3;
        if bri == 255 {
            return self.inner.conn.write_ext(&self.inner.buf[..n3], self.inner.reset_bytes);
        }
        let mut scaled: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        scaled.resize_default(n3).ok();
        for i in 0..n3 {
            scaled[i] = (self.inner.buf[i] as u16 * bri as u16 / 255) as u8;
        }
        self.inner.conn.write_ext(&scaled[..n3], self.inner.reset_bytes)
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
        let (r, g, b) = super::color::hsv_to_rgb(h, s, v);
        self.fill(r, g, b)
    }
}
