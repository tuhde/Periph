//! Shared driver logic for 4-channel (RGBW) NeoPixel-protocol LED chips.
//!
//! Drives a chain of pixels over a [`NeoPixelConnection`]. Maintains an
//! internal buffer in wire order; [`NeoPixelRgbwMinimal::fill`] writes every
//! pixel and transmits immediately. Each pixel has four channels: red,
//! green, blue, and white. Concrete chip drivers (e.g. `Sk6812RgbwMinimal`)
//! wrap these types, fixing `channel_order` and `reset_bytes` for their chip.
//!
//! # Pixel limit
//! The internal buffer holds up to `MAX_PIXELS` (256) pixels (1024 bytes).
//! Pixel indices beyond this limit are clamped silently.

use embedded_hal::spi::SpiBus;
use crate::connection::neopixel::NeoPixelConnection;

/// Maximum supported pixel count for the internal RGBW buffer.
pub const MAX_PIXELS: usize = 256;
const MAX_BUF: usize = MAX_PIXELS * 4;

/// Shared minimal-tier NeoPixel RGBW driver — fill the entire strip with one colour.
///
/// Wraps a [`NeoPixelConnection`] and manages an internal RGBW pixel buffer in
/// wire order. [`fill`](Self::fill) updates every pixel and transmits
/// immediately; [`off`](Self::off) is shorthand for `fill(0, 0, 0, 0)`.
pub struct NeoPixelRgbwMinimal<SPI> {
    // pub(crate) so concrete chip drivers' own unit tests (e.g. sk6812rgbw.rs's
    // `mod tests`, defined in a different module) can reach the underlying
    // mock's .done() via `sensor.inner.inner.conn.spi` - not part of the
    // public API.
    pub(crate) conn: NeoPixelConnection<SPI>,
    pub(crate) n: usize,
    pub(crate) buf: heapless::Vec<u8, MAX_BUF>,
    channel_order: [usize; 4],
    reset_bytes: usize,
}

impl<SPI: SpiBus> NeoPixelRgbwMinimal<SPI> {
    /// Create a new `NeoPixelRgbwMinimal`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    /// * `channel_order` — `[iR, iG, iB, iW]`; `wire[k] = [r,g,b,w][channel_order[k]]`.
    /// * `reset_bytes` — Total trailing zero bytes for this chip's reset pulse.
    pub fn new(spi: SPI, n: usize, channel_order: [usize; 4], reset_bytes: usize) -> Self {
        let n = n.min(MAX_PIXELS);
        let mut buf = heapless::Vec::new();
        buf.resize_default(n * 4).ok();
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
    /// Stores the four channels in the internal buffer using this chip's
    /// wire channel order, then calls [`NeoPixelConnection::write_ext`].
    /// `w=0` for RGB-only usage.
    pub fn fill(&mut self, r: u8, g: u8, b: u8, w: u8) -> Result<(), SPI::Error> {
        let vals = [r, g, b, w];
        let w0 = vals[self.channel_order[0]];
        let w1 = vals[self.channel_order[1]];
        let w2 = vals[self.channel_order[2]];
        let w3 = vals[self.channel_order[3]];
        for i in 0..self.n {
            self.buf[i * 4]     = w0;
            self.buf[i * 4 + 1] = w1;
            self.buf[i * 4 + 2] = w2;
            self.buf[i * 4 + 3] = w3;
        }
        self.conn.write_ext(&self.buf[..self.n * 4], self.reset_bytes)
    }

    /// Turn off all pixels (fill with all zeros and send).
    ///
    /// Equivalent to `fill(0, 0, 0, 0)`.
    pub fn off(&mut self) -> Result<(), SPI::Error> {
        self.fill(0, 0, 0, 0)
    }
}

/// Shared full-tier NeoPixel RGBW driver — per-pixel control, brightness,
/// rotation, and HSV fill.
///
/// Adds individual pixel addressing, explicit [`show`](Self::show),
/// global brightness scaling, buffer rotation, and HSV fill on top of
/// [`NeoPixelRgbwMinimal`]. Call [`set_pixel`](Self::set_pixel) to update the
/// buffer, then [`show`](Self::show) to transmit; or use the inherited
/// [`fill`](NeoPixelRgbwMinimal::fill) for an immediate all-same-colour update.
pub struct NeoPixelRgbwFull<SPI> {
    pub(crate) inner: NeoPixelRgbwMinimal<SPI>,
    brightness: u8,
}

impl<SPI: SpiBus> NeoPixelRgbwFull<SPI> {
    /// Create a new `NeoPixelRgbwFull`.
    ///
    /// # Arguments
    /// * `spi` — SPI bus configured at 2.4 MHz, mode 0, MSB-first.
    /// * `n`   — Number of pixels in the strip (clamped to [`MAX_PIXELS`]).
    /// * `channel_order` — `[iR, iG, iB, iW]` wire channel order.
    /// * `reset_bytes` — Total trailing zero bytes for this chip's reset pulse.
    pub fn new(spi: SPI, n: usize, channel_order: [usize; 4], reset_bytes: usize) -> Self {
        Self {
            inner: NeoPixelRgbwMinimal::new(spi, n, channel_order, reset_bytes),
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
        let vals = [r, g, b, w];
        let co = self.inner.channel_order;
        self.inner.buf[index * 4]     = vals[co[0]];
        self.inner.buf[index * 4 + 1] = vals[co[1]];
        self.inner.buf[index * 4 + 2] = vals[co[2]];
        self.inner.buf[index * 4 + 3] = vals[co[3]];
    }

    /// Transmit the current buffer to the strip, applying brightness scaling.
    ///
    /// Each channel is scaled: `sent = stored * brightness / 255`.
    pub fn show(&mut self) -> Result<(), SPI::Error> {
        let bri = self.brightness;
        let n4 = self.inner.n * 4;
        if bri == 255 {
            return self.inner.conn.write_ext(&self.inner.buf[..n4], self.inner.reset_bytes);
        }
        let mut scaled: heapless::Vec<u8, MAX_BUF> = heapless::Vec::new();
        scaled.resize_default(n4).ok();
        for i in 0..n4 {
            scaled[i] = (self.inner.buf[i] as u16 * bri as u16 / 255) as u8;
        }
        self.inner.conn.write_ext(&scaled[..n4], self.inner.reset_bytes)
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
        let (r, g, b) = super::color::hsv_to_rgb(h, s, v);
        self.fill(r, g, b, 0)
    }
}
