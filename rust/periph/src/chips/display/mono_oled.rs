//! Shared building blocks of the monochrome page-organised OLED controller drivers (SSD130x style).
//!
//! * [`MonoFramebuffer`] – a page-major 1 bpp framebuffer (bit 0 of a byte = top pixel of its page) with
//!   the drawing primitives, no heap.
//! * [`Transport`] with [`I2cBus`] (control-byte framed I²C) and [`SpiBus`] (D/C-pin framed 4-wire SPI on a
//!   [`DisplayBus`]) – the two ways a command or RAM-data stream reaches the chip.
//! * [`Error`] – the error type of these drivers.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::display::DisplayBus;

/// Largest I²C write in bytes including the control byte (the 32-byte limit of some I²C layers).
pub const I2C_CHUNK: usize = 32;
/// Largest data write in bytes on the SPI transport.
pub const SPI_CHUNK: usize = 128;

/// Error type of the monochrome OLED drivers.
#[derive(Debug)]
pub enum Error<E> {
    /// The bus reported an error.
    Bus(E),
    /// A size, position or buffer argument was out of range.
    InvalidArgument,
    /// RAM access was attempted while a hardware scroll is active (stop it first).
    ScrollActive,
}

/// Transport of the monochrome OLED drivers.
///
/// Implemented by [`I2cBus`] (control-byte framing on one I²C address) and [`SpiBus`] (a display connection).
pub trait Transport {
    /// Error type of the transfers.
    type Error;
    /// Send bytes as one command stream.
    fn write_commands(&mut self, bytes: &[u8]) -> Result<(), Self::Error>;
    /// Send RAM data bytes (in chunks if the transport needs it).
    fn write_data(&mut self, bytes: &[u8]) -> Result<(), Self::Error>;
    /// Pulse the hardware reset line, if any.
    fn hardware_reset(&mut self) -> Result<(), Self::Error>;
    /// Block for `ms` milliseconds.
    fn delay_ms(&mut self, ms: u32);
}

/// I²C transport with control-byte framing (`0x00` commands, `0x40` RAM data).
pub struct I2cBus<I2C, D> {
    pub(crate) i2c: I2C,
    pub(crate) delay: D,
    pub(crate) addr: u8,
}

impl<I2C: I2c, D: DelayNs> Transport for I2cBus<I2C, D> {
    type Error = I2C::Error;

    fn write_commands(&mut self, bytes: &[u8]) -> Result<(), Self::Error> {
        let mut buf = [0u8; I2C_CHUNK];
        for chunk in bytes.chunks(I2C_CHUNK - 1) {
            buf[0] = 0x00;
            buf[1..=chunk.len()].copy_from_slice(chunk);
            self.i2c.write(self.addr, &buf[..=chunk.len()])?;
        }
        Ok(())
    }

    fn write_data(&mut self, bytes: &[u8]) -> Result<(), Self::Error> {
        let mut buf = [0u8; I2C_CHUNK];
        for chunk in bytes.chunks(I2C_CHUNK - 1) {
            buf[0] = 0x40;
            buf[1..=chunk.len()].copy_from_slice(chunk);
            self.i2c.write(self.addr, &buf[..=chunk.len()])?;
        }
        Ok(())
    }

    fn hardware_reset(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }

    fn delay_ms(&mut self, ms: u32) {
        self.delay.delay_ms(ms);
    }
}

/// 4-wire SPI transport on a [`DisplayBus`]: D/C low for every command byte, high for RAM data.
pub struct SpiBus<B> {
    pub(crate) conn: B,
}

impl<B: DisplayBus> Transport for SpiBus<B> {
    type Error = B::Error;

    fn write_commands(&mut self, bytes: &[u8]) -> Result<(), Self::Error> {
        for &b in bytes {
            self.conn.send_command(b, &[])?;
        }
        Ok(())
    }

    fn write_data(&mut self, bytes: &[u8]) -> Result<(), Self::Error> {
        for chunk in bytes.chunks(SPI_CHUNK) {
            self.conn.send_data(chunk)?;
        }
        Ok(())
    }

    fn hardware_reset(&mut self) -> Result<(), Self::Error> {
        self.conn.reset()
    }

    fn delay_ms(&mut self, ms: u32) {
        let _ = self.conn.delay_ms(ms);
    }
}

/// Maximum framebuffer size in bytes (128 columns x 8 pages).
pub const FB_SIZE: usize = 128 * 8;

/// Page-major monochrome framebuffer of up to 128 x 64 pixels with drawing primitives.
pub struct MonoFramebuffer {
    width: usize,
    height: usize,
    data: [u8; FB_SIZE],
}

impl MonoFramebuffer {
    /// Create a zeroed framebuffer; `width` ≤ 128 and `height` a multiple of 8 and ≤ 64 (not checked here).
    pub fn new(width: usize, height: usize) -> Self {
        Self { width, height, data: [0; FB_SIZE] }
    }

    /// Columns.
    pub fn width(&self) -> usize {
        self.width
    }

    /// Rows.
    pub fn height(&self) -> usize {
        self.height
    }

    /// Number of 8-pixel pages.
    pub fn pages(&self) -> usize {
        self.height / 8
    }

    /// The `width * pages` page-major bytes.
    pub fn bytes(&self) -> &[u8] {
        &self.data[..self.width * self.pages()]
    }

    /// The `width` bytes of one page.
    pub fn page(&self, page: usize) -> &[u8] {
        &self.data[page * self.width..(page + 1) * self.width]
    }

    /// The framebuffer column-major (for vertical addressing); the result holds [`Self::bytes`]`.len()` bytes.
    pub fn transposed(&self, out: &mut [u8; FB_SIZE]) {
        let mut k = 0;
        for x in 0..self.width {
            for p in 0..self.pages() {
                out[k] = self.data[p * self.width + x];
                k += 1;
            }
        }
    }

    /// Zero the framebuffer.
    pub fn clear(&mut self) {
        self.fill(false);
    }

    /// Set (or clear) every pixel.
    pub fn fill(&mut self, on: bool) {
        let n = self.width * self.pages();
        self.data[..n].fill(if on { 0xFF } else { 0 });
    }

    /// Set or clear one pixel; out-of-range coordinates are ignored.
    pub fn set_pixel(&mut self, x: i32, y: i32, on: bool) {
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return;
        }
        let i = (y as usize >> 3) * self.width + x as usize;
        let mask = 1u8 << (y & 7);
        if on {
            self.data[i] |= mask;
        } else {
            self.data[i] &= !mask;
        }
    }

    /// Return a pixel; out-of-range coordinates read as `false`.
    pub fn get_pixel(&self, x: i32, y: i32) -> bool {
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return false;
        }
        self.data[(y as usize >> 3) * self.width + x as usize] & (1 << (y & 7)) != 0
    }

    /// Bresenham line.
    pub fn draw_line(&mut self, mut x0: i32, mut y0: i32, x1: i32, y1: i32, on: bool) {
        let dx = (x1 - x0).abs();
        let dy = -(y1 - y0).abs();
        let sx = if x0 < x1 { 1 } else { -1 };
        let sy = if y0 < y1 { 1 } else { -1 };
        let mut err = dx + dy;
        loop {
            self.set_pixel(x0, y0, on);
            if x0 == x1 && y0 == y1 {
                break;
            }
            let e2 = 2 * err;
            if e2 >= dy {
                err += dy;
                x0 += sx;
            }
            if e2 <= dx {
                err += dx;
                y0 += sy;
            }
        }
    }

    /// Rectangle outline or filled rectangle.
    pub fn draw_rect(&mut self, x: i32, y: i32, w: i32, h: i32, on: bool, fill: bool) {
        if w <= 0 || h <= 0 {
            return;
        }
        if fill {
            for yy in y..y + h {
                for xx in x..x + w {
                    self.set_pixel(xx, yy, on);
                }
            }
            return;
        }
        self.draw_line(x, y, x + w - 1, y, on);
        self.draw_line(x, y + h - 1, x + w - 1, y + h - 1, on);
        self.draw_line(x, y, x, y + h - 1, on);
        self.draw_line(x + w - 1, y, x + w - 1, y + h - 1, on);
    }

    /// Copy a column-major bitmap (8 pixels per byte, bit 0 = top); `columns` holds `height_pages` groups of
    /// columns, page by page. Clipped to the framebuffer.
    ///
    /// Returns `false` (nothing drawn) if `y` is not a multiple of 8 or `height_pages` is 0.
    pub fn blit(&mut self, x: i32, y: i32, columns: &[u8], height_pages: usize) -> bool {
        if y % 8 != 0 || height_pages == 0 {
            return false;
        }
        let n = columns.len() / height_pages;
        for p in 0..height_pages {
            let page = y / 8 + p as i32;
            if page < 0 || page as usize >= self.pages() {
                continue;
            }
            for c in 0..n {
                let xx = x + c as i32;
                if xx >= 0 && (xx as usize) < self.width {
                    self.data[page as usize * self.width + xx as usize] = columns[p * n + c];
                }
            }
        }
        true
    }
}
