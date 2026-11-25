//! SSD1305 — 132 x 64 OLED/PLED segment/common driver with controller (Solomon Systech), the chip behind the
//! 2.23" 128 x 32 and 1.3" 128 x 64 monochrome OLED modules.
//!
//! Drives a monochrome SSD1305 panel over I²C or 4-wire SPI.
//!
//! ## I²C
//!
//! 7-bit address `0x3C` (SA0 = low) or `0x3D` (SA0 = high). Every write starts with a control byte (`0x00` = the
//! rest are commands, `0x40` = the rest is display RAM data); data is sent in writes of at most [`I2C_CHUNK`]
//! bytes including the control byte ([`Ssd1305Minimal::new`]).
//!
//! ## 4-wire SPI
//!
//! [`Ssd1305Minimal::new_spi`] / [`Ssd1305Full::new_spi`] drive the chip through a
//! [`DisplayBus`](crate::connection::display::DisplayBus) (CS, D/C, RES optional): SPI mode 0, up to 4 MHz
//! (2 MHz recommended), BS0 = BS1 = BS2 = 0. Every command byte, including arguments, is sent with D/C low;
//! RAM data with D/C high. SPI cannot read: `read_status` / `read_page` return [`Error::Unsupported`].
//! [`Ssd1305SpiMinimal`] and [`Ssd1305SpiFull`] are the aliases for these types.
//!
//! ## Framebuffer
//!
//! The chip has 132 RAM columns; 128-pixel-wide modules usually show RAM columns 4..131, so `column_offset` is 4 in
//! the examples. All drawing goes into a framebuffer inside the driver (`width * height / 8` bytes, page-major,
//! bit 0 = top pixel of the page, no heap); `show` sends it page by page (page addressing, the reset default) or as
//! one stream in horizontal / vertical addressing ([`Ssd1305Full::set_addressing_mode`]).
//! The chip has **no charge pump**: the OLED supply VCC (7..15 V) must be provided by the board. The initialisation
//! follows the datasheet power-on flow: setup stream with the display off (external-VCC master configuration, page
//! addressing), RAM cleared, display on + 100 ms.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::display::DisplayBus;

/// Maximum number of RAM columns.
pub const WIDTH: usize = 132;
/// Maximum number of rows.
pub const HEIGHT: usize = 64;
/// Largest I²C write in bytes including the control byte (the 32-byte limit of some I²C layers).
pub const I2C_CHUNK: usize = 32;
/// Largest data write in bytes on the SPI transport (one page).
pub const SPI_CHUNK: usize = 132;

const FB_SIZE: usize = WIDTH * HEIGHT / 8;

const CMD_COL_LO: u8 = 0x00; // | A[3:0]
const CMD_COL_HI: u8 = 0x10; // | A[7:4]
const CMD_ADDR_MODE: u8 = 0x20;
const CMD_COL_RANGE: u8 = 0x21;
const CMD_PAGE_RANGE: u8 = 0x22;
const CMD_START_LINE: u8 = 0x40; // | A[5:0]
const CMD_CONTRAST: u8 = 0x81;
const CMD_BRIGHTNESS: u8 = 0x82;
const CMD_LUT: u8 = 0x91;
const CMD_BANK_1_16: u8 = 0x92;
const CMD_BANK_17_32: u8 = 0x93;
const CMD_SEG_REMAP: u8 = 0xA0; // | ADC
const CMD_SCROLL_AREA: u8 = 0xA3;
const CMD_ALL_ON: u8 = 0xA4; // | 1
const CMD_INVERT: u8 = 0xA6; // | 1
const CMD_MULTIPLEX: u8 = 0xA8;
const CMD_DIM_MODE: u8 = 0xAB;
const CMD_DIM_ON: u8 = 0xAC;
const CMD_MASTER: u8 = 0xAD;
const CMD_DISPLAY: u8 = 0xAE; // | 1
const CMD_PAGE: u8 = 0xB0; // | A[2:0]
const CMD_SCAN: u8 = 0xC0; // | (scan << 3)
const CMD_OFFSET: u8 = 0xD3;
const CMD_CLOCK: u8 = 0xD5;
const CMD_AREA_COLOR: u8 = 0xD8;
const CMD_PRECHARGE: u8 = 0xD9;
const CMD_COM: u8 = 0xDA;
const CMD_VCOMH: u8 = 0xDB;

/// RAM addressing mode used by `show` (see [`Ssd1305Full::set_addressing_mode`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AddressingMode {
    /// Column pointer advances first, then the page (`0x20 0x00`).
    Horizontal,
    /// Page pointer advances first, then the column (`0x20 0x01`).
    Vertical,
    /// One page at a time with explicit page + column commands (`0x20 0x02`, reset default).
    Page,
}

/// Error type of the SSD1305 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The bus reported an error.
    Bus(E),
    /// A size, position or buffer argument was out of range.
    InvalidArgument,
    /// The operation needs read-back, which the 4-wire SPI interface does not have.
    Unsupported,
}

/// Transport of the SSD1305 drivers.
///
/// Implemented by [`I2cBus`] (control-byte framing on one I²C address) and [`SpiBus`] (a display connection).
pub trait Transport {
    /// Error type of the transfers.
    type Error;
    /// `true` if status and RAM can be read back (I²C); SPI is write-only.
    const CAN_READ: bool;
    /// Send bytes as one command stream.
    fn write_commands(&mut self, bytes: &[u8]) -> Result<(), Self::Error>;
    /// Send RAM data bytes (in chunks if the transport needs it).
    fn write_data(&mut self, bytes: &[u8]) -> Result<(), Self::Error>;
    /// Read the status byte.
    fn read_status(&mut self) -> Result<u8, Self::Error>;
    /// Read `buf.len() + 1` RAM bytes and return the bytes after the dummy byte.
    fn read_ram(&mut self, buf: &mut [u8]) -> Result<(), Self::Error>;
    /// Pulse the hardware reset line, if any.
    fn hardware_reset(&mut self) -> Result<(), Self::Error>;
    /// Block for `ms` milliseconds.
    fn delay_ms(&mut self, ms: u32);
}

/// I²C transport with control-byte framing (`0x00` commands, `0x40` RAM data).
pub struct I2cBus<I2C, D> {
    i2c: I2C,
    delay: D,
    addr: u8,
}

impl<I2C: I2c, D: DelayNs> Transport for I2cBus<I2C, D> {
    type Error = I2C::Error;
    const CAN_READ: bool = true;

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

    fn read_status(&mut self) -> Result<u8, Self::Error> {
        let mut s = [0u8; 1];
        self.i2c.write(self.addr, &[0x00])?;
        self.i2c.read(self.addr, &mut s)?;
        Ok(s[0])
    }

    fn read_ram(&mut self, buf: &mut [u8]) -> Result<(), Self::Error> {
        let mut raw = [0u8; I2C_CHUNK];
        let n = buf.len();
        self.i2c.write(self.addr, &[0x40])?;
        self.i2c.read(self.addr, &mut raw[..n + 1])?;
        buf.copy_from_slice(&raw[1..n + 1]);
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
    conn: B,
}

impl<B: DisplayBus> Transport for SpiBus<B> {
    type Error = B::Error;
    const CAN_READ: bool = false;

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

    fn read_status(&mut self) -> Result<u8, Self::Error> {
        let mut s = [0u8; 1];
        self.conn.read_data(&mut s)?;
        Ok(s[0])
    }

    fn read_ram(&mut self, buf: &mut [u8]) -> Result<(), Self::Error> {
        self.conn.read_data(buf)
    }

    fn hardware_reset(&mut self) -> Result<(), Self::Error> {
        self.conn.reset()
    }

    fn delay_ms(&mut self, ms: u32) {
        let _ = self.conn.delay_ms(ms);
    }
}

/// SSD1305 minimal driver — a monochrome framebuffer display of up to 132 x 64 pixels.
///
/// See the [module documentation](self).
pub struct Ssd1305Minimal<B> {
    bus: B,
    width: usize,
    height: usize,
    pages: u8,
    offset: u8,
    mode: AddressingMode,
    adc: u8,
    scan: u8,
    fb: [u8; FB_SIZE],
}

impl<I2C: I2c, D: DelayNs> Ssd1305Minimal<I2cBus<I2C, D>> {
    /// Create the driver on an I²C bus and run the initialisation sequence (about 100 ms).
    ///
    /// # Arguments
    ///
    /// * `i2c`           – I²C bus.
    /// * `addr`          – 7-bit address, `0x3C` (SA0 = low) or `0x3D` (SA0 = high).
    /// * `delay`         – Delay provider.
    /// * `width`         – Visible columns, 1..=132 (typically 128).
    /// * `height`        – Rows, a multiple of 8, 16..=64.
    /// * `column_offset` – First RAM column shown (4 for 128-pixel modules, 0 for a true 132-column panel).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size or offset, [`Error::Bus`] when a transfer fails.
    pub fn new(
        i2c: I2C,
        addr: u8,
        delay: D,
        width: usize,
        height: usize,
        column_offset: u8,
    ) -> Result<Self, Error<I2C::Error>> {
        Self::with_bus(I2cBus { i2c, delay, addr }, width, height, column_offset)
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        (self.bus.i2c, self.bus.delay)
    }
}

impl<DB: DisplayBus> Ssd1305Minimal<SpiBus<DB>> {
    /// Create the driver on a 4-wire SPI display connection and run the initialisation sequence (about 100 ms).
    ///
    /// # Arguments
    ///
    /// * `connection`    – [`DisplayBus`] (CS, D/C, RES optional, no BUSY).
    /// * `width`         – Visible columns, 1..=132.
    /// * `height`        – Rows, a multiple of 8, 16..=64.
    /// * `column_offset` – First RAM column shown.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size or offset, [`Error::Bus`] when a transfer fails.
    pub fn new_spi(
        connection: DB,
        width: usize,
        height: usize,
        column_offset: u8,
    ) -> Result<Self, Error<DB::Error>> {
        Self::with_bus(SpiBus { conn: connection }, width, height, column_offset)
    }

    /// Release the display connection.
    pub fn release_spi(self) -> DB {
        self.bus.conn
    }
}

impl<B: Transport> Ssd1305Minimal<B> {
    fn with_bus(
        bus: B,
        width: usize,
        height: usize,
        column_offset: u8,
    ) -> Result<Self, Error<B::Error>> {
        if !(1..=WIDTH).contains(&width)
            || !(16..=HEIGHT).contains(&height)
            || height % 8 != 0
            || column_offset as usize + width > WIDTH
        {
            return Err(Error::InvalidArgument);
        }
        let mut d = Self {
            bus,
            width,
            height,
            pages: (height / 8) as u8,
            offset: column_offset,
            mode: AddressingMode::Page,
            adc: 0,
            scan: 0,
            fb: [0; FB_SIZE],
        };
        d.init_sequence()?;
        Ok(d)
    }

    /// Visible columns of the framebuffer.
    pub fn width(&self) -> usize {
        self.width
    }

    /// Rows of the framebuffer.
    pub fn height(&self) -> usize {
        self.height
    }

    /// Zero the framebuffer. Call [`Self::show`] to update the panel.
    pub fn clear(&mut self) {
        self.fb.fill(0);
    }

    /// Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
    ///
    /// # Arguments
    ///
    /// * `x`  – Column, 0..width.
    /// * `y`  – Row, 0..height.
    /// * `on` – `true` to set the pixel, `false` to clear it.
    pub fn set_pixel(&mut self, x: i32, y: i32, on: bool) {
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return;
        }
        let i = (y as usize >> 3) * self.width + x as usize;
        if on {
            self.fb[i] |= 1 << (y & 7);
        } else {
            self.fb[i] &= !(1u8 << (y & 7));
        }
    }

    /// Write the whole framebuffer to the display RAM (page + column command, then `width` data bytes per page).
    ///
    /// # Errors
    ///
    /// [`Error::Bus`] if a transfer fails.
    pub fn show(&mut self) -> Result<(), Error<B::Error>> {
        self.show_all()
    }

    // ---- internals ------------------------------------------------------------------------

    fn command(&mut self, bytes: &[u8]) -> Result<(), Error<B::Error>> {
        self.bus.write_commands(bytes).map_err(Error::Bus)
    }

    fn address_bytes(page: u8, column: u8) -> [u8; 3] {
        [CMD_PAGE | (page & 7), CMD_COL_LO | (column & 0x0F), CMD_COL_HI | ((column >> 4) & 0x0F)]
    }

    fn show_page_inner(&mut self, page: u8) -> Result<(), Error<B::Error>> {
        let start = page as usize * self.width;
        let mut buf = [0u8; WIDTH];
        let w = self.width;
        buf[..w].copy_from_slice(&self.fb[start..start + w]);
        self.command(&Self::address_bytes(page, self.offset))?;
        self.bus.write_data(&buf[..w]).map_err(Error::Bus)
    }

    fn show_all(&mut self) -> Result<(), Error<B::Error>> {
        if self.mode == AddressingMode::Page {
            for page in 0..self.pages {
                self.show_page_inner(page)?;
            }
            return Ok(());
        }
        let n = self.width * self.pages as usize;
        let mut buf = [0u8; FB_SIZE];
        if self.mode == AddressingMode::Horizontal {
            buf[..n].copy_from_slice(&self.fb[..n]);
        } else {
            let mut k = 0;
            for x in 0..self.width {
                for p in 0..self.pages as usize {
                    buf[k] = self.fb[p * self.width + x];
                    k += 1;
                }
            }
        }
        let last_col = self.offset + (self.width - 1) as u8;
        self.command(&[CMD_COL_RANGE, self.offset, last_col, CMD_PAGE_RANGE, 0, self.pages - 1])?;
        self.bus.write_data(&buf[..n]).map_err(Error::Bus)
    }

    /// Datasheet power-on flow: reset, setup stream, RAM clear, display on.
    fn init_sequence(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.hardware_reset().map_err(Error::Bus)?;
        self.bus.delay_ms(1);
        let stream = [
            CMD_DISPLAY,
            CMD_CLOCK,
            0x70,
            CMD_MULTIPLEX,
            (self.height - 1) as u8,
            CMD_OFFSET,
            0x00,
            CMD_START_LINE,
            CMD_SEG_REMAP | self.adc,
            CMD_SCAN | (self.scan << 3),
            CMD_COM,
            if self.height > 32 { 0x12 } else { 0x02 },
            CMD_CONTRAST,
            0x80,
            CMD_PRECHARGE,
            0x22,
            CMD_VCOMH,
            0x34,
            CMD_MASTER,
            0x8E,
            CMD_ALL_ON,
            CMD_INVERT,
            CMD_ADDR_MODE,
            0x02,
        ];
        self.command(&stream)?;
        let zeros = [0u8; WIDTH];
        for page in 0..8u8 {
            self.command(&Self::address_bytes(page, 0))?;
            self.bus.write_data(&zeros).map_err(Error::Bus)?;
        }
        self.command(&[CMD_DISPLAY | 1])?;
        self.bus.delay_ms(100);
        Ok(())
    }
}

/// SSD1305 full driver — extends [`Ssd1305Minimal`] (reachable through `Deref`) with drawing primitives, contrast,
/// brightness, dim mode, sleep, invert, all-on test, flip, start line, display offset, multiplex ratio, clock,
/// pre-charge, COM configuration, VCOMH level, addressing mode, hardware scrolling, area-colour mode with LUT /
/// bank colours, status and RAM reads (I²C only) and raw commands.
pub struct Ssd1305Full<B> {
    inner: Ssd1305Minimal<B>,
}

impl<B> core::ops::Deref for Ssd1305Full<B> {
    type Target = Ssd1305Minimal<B>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<B> core::ops::DerefMut for Ssd1305Full<B> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

/// SSD1305 over 4-wire SPI — minimal driver ([`Ssd1305Minimal`] on a [`SpiBus`]; create it with `new_spi`).
pub type Ssd1305SpiMinimal<DB> = Ssd1305Minimal<SpiBus<DB>>;

/// SSD1305 over 4-wire SPI — full driver ([`Ssd1305Full`] on a [`SpiBus`]; create it with `new_spi`).
pub type Ssd1305SpiFull<DB> = Ssd1305Full<SpiBus<DB>>;

impl<I2C: I2c, D: DelayNs> Ssd1305Full<I2cBus<I2C, D>> {
    /// Create the driver on an I²C bus and run the initialisation sequence.
    ///
    /// # Arguments
    ///
    /// * `i2c`           – I²C bus.
    /// * `addr`          – 7-bit address, `0x3C` or `0x3D`.
    /// * `delay`         – Delay provider.
    /// * `width`         – Visible columns, 1..=132.
    /// * `height`        – Rows, a multiple of 8, 16..=64.
    /// * `column_offset` – First RAM column shown (4 for 128-pixel modules).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size or offset, [`Error::Bus`] when a transfer fails.
    pub fn new(
        i2c: I2C,
        addr: u8,
        delay: D,
        width: usize,
        height: usize,
        column_offset: u8,
    ) -> Result<Self, Error<I2C::Error>> {
        Ok(Self { inner: Ssd1305Minimal::new(i2c, addr, delay, width, height, column_offset)? })
    }
}

impl<DB: DisplayBus> Ssd1305Full<SpiBus<DB>> {
    /// Create the driver on a 4-wire SPI display connection and run the initialisation sequence.
    ///
    /// # Arguments
    ///
    /// * `connection`    – [`DisplayBus`] (CS, D/C, RES optional, no BUSY).
    /// * `width`         – Visible columns, 1..=132.
    /// * `height`        – Rows, a multiple of 8, 16..=64.
    /// * `column_offset` – First RAM column shown.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size or offset, [`Error::Bus`] when a transfer fails.
    pub fn new_spi(
        connection: DB,
        width: usize,
        height: usize,
        column_offset: u8,
    ) -> Result<Self, Error<DB::Error>> {
        Ok(Self { inner: Ssd1305Minimal::new_spi(connection, width, height, column_offset)? })
    }
}

impl<B: Transport> Ssd1305Full<B> {
    // ---- framebuffer ----------------------------------------------------------------------

    /// Return a framebuffer pixel; out-of-range coordinates read as `false`.
    pub fn get_pixel(&self, x: i32, y: i32) -> bool {
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return false;
        }
        self.fb[(y as usize >> 3) * self.width + x as usize] & (1 << (y & 7)) != 0
    }

    /// Set (or clear) the whole framebuffer.
    pub fn fill(&mut self, on: bool) {
        self.fb.fill(if on { 0xFF } else { 0 });
    }

    /// Draw a Bresenham line into the framebuffer.
    pub fn draw_line(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, on: bool) {
        let (mut x0, mut y0) = (x0, y0);
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

    /// Draw a rectangle outline or a filled rectangle into the framebuffer.
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

    /// Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer.
    ///
    /// `columns` holds `height_pages` groups of columns, page by page: byte `p * n + c` is column `c` of page row
    /// `p` (n = `columns.len() / height_pages`). The bitmap is clipped to the framebuffer.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] if `y` is not a multiple of 8 or `height_pages` is 0.
    pub fn blit(&mut self, x: i32, y: i32, columns: &[u8], height_pages: usize) -> Result<(), Error<B::Error>> {
        if y % 8 != 0 || height_pages == 0 {
            return Err(Error::InvalidArgument);
        }
        let n = columns.len() / height_pages;
        for p in 0..height_pages {
            let page = y / 8 + p as i32;
            if page < 0 || page >= self.pages as i32 {
                continue;
            }
            for c in 0..n {
                let xx = x + c as i32;
                if xx >= 0 && (xx as usize) < self.width {
                    let i = page as usize * self.width + xx as usize;
                    self.fb[i] = columns[p * n + c];
                }
            }
        }
        Ok(())
    }

    /// Write one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are ignored.
    pub fn show_page(&mut self, page: u8) -> Result<(), Error<B::Error>> {
        if page >= self.pages {
            return Ok(());
        }
        if self.mode == AddressingMode::Page {
            return self.show_page_inner(page);
        }
        let start = page as usize * self.width;
        let mut buf = [0u8; WIDTH];
        let w = self.width;
        buf[..w].copy_from_slice(&self.fb[start..start + w]);
        let offset = self.offset;
        let last_col = offset + (w - 1) as u8;
        self.command(&[CMD_COL_RANGE, offset, last_col, CMD_PAGE_RANGE, page, page])?;
        self.bus.write_data(&buf[..w]).map_err(Error::Bus)
    }

    // ---- display control ------------------------------------------------------------------

    /// Set the segment output current (contrast), 0..=255 (reset `0x80`).
    pub fn set_contrast(&mut self, value: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_CONTRAST, value])
    }

    /// Switch the display on or off (RAM and settings are kept).
    pub fn set_display(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_DISPLAY | on as u8])
    }

    /// Display off (`0xAE`); RAM and settings are kept and stay writable. Switch VCC off afterwards.
    pub fn sleep(&mut self) -> Result<(), Error<B::Error>> {
        self.set_display(false)
    }

    /// Display on, then wait 100 ms (tAF) for SEG / COM to turn on.
    pub fn wake(&mut self) -> Result<(), Error<B::Error>> {
        self.set_display(true)?;
        self.bus.delay_ms(100);
        Ok(())
    }

    /// Display on in dim mode (`true`, `0xAC`) or normal display on (`false`, `0xAF`); see [`Self::set_dim_mode`].
    pub fn set_dim(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.command(&[if on { CMD_DIM_ON } else { CMD_DISPLAY | 1 }])
    }

    /// Configure the BANK0 contrast and colour-bank brightness used in dim mode (`0xAB`).
    pub fn set_dim_mode(&mut self, contrast: u8, brightness: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_DIM_MODE, 0x00, contrast, brightness])
    }

    /// Invert the displayed image without changing RAM.
    pub fn set_invert(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_INVERT | on as u8])
    }

    /// Force all pixels on (test mode, RAM unchanged).
    pub fn set_all_on(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_ALL_ON | on as u8])
    }

    /// Mirror columns (segment re-map, ADC) and/or rows (COM scan direction); the framebuffer is unchanged.
    pub fn set_flip(&mut self, horizontal: bool, vertical: bool) -> Result<(), Error<B::Error>> {
        self.adc = horizontal as u8;
        self.scan = vertical as u8;
        let stream = [CMD_SEG_REMAP | self.adc, CMD_SCAN | (self.scan << 3)];
        self.command(&stream)
    }

    /// Set the RAM line shown at COM0 (hardware vertical scroll), 0..=63.
    pub fn set_start_line(&mut self, line: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_START_LINE | (line & 0x3F)])
    }

    /// Shift the COM mapping, 0..=63.
    pub fn set_display_offset(&mut self, offset: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_OFFSET, offset & 0x3F])
    }

    /// Set the multiplex ratio, 16..=64.
    pub fn set_multiplex(&mut self, ratio: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_MULTIPLEX, ratio.wrapping_sub(1) & 0x3F])
    }

    /// Set the clock divide ratio (1..=16) and oscillator setting (0..=15, 7 = reset value).
    pub fn set_clock(&mut self, divide: u8, osc: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_CLOCK, ((osc & 0x0F) << 4) | (divide.wrapping_sub(1) & 0x0F)])
    }

    /// Set the pre-charge phase 1 and phase 2 periods (1..=15 display clocks each).
    pub fn set_precharge(&mut self, phase1: u8, phase2: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_PRECHARGE, ((phase2 & 0x0F) << 4) | (phase1 & 0x0F)])
    }

    /// Select the COM pin configuration: alternative (`true`, reset) or sequential; `remap` enables the COM left / right remap.
    pub fn set_com_config(&mut self, alternative: bool, remap: bool) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_COM, 0x02 | if alternative { 0x10 } else { 0 } | if remap { 0x20 } else { 0 }])
    }

    /// Set the VCOMH deselect level (only bits 5:2 used; reset `0x34`).
    pub fn set_vcomh(&mut self, value: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_VCOMH, value & 0x3C])
    }

    /// Select the RAM addressing mode used by [`Ssd1305Minimal::show`] (reset: [`AddressingMode::Page`]).
    pub fn set_addressing_mode(&mut self, mode: AddressingMode) -> Result<(), Error<B::Error>> {
        self.mode = mode;
        let code = match mode {
            AddressingMode::Horizontal => 0,
            AddressingMode::Vertical => 1,
            AddressingMode::Page => 2,
        };
        self.command(&[CMD_ADDR_MODE, code])
    }

    // ---- hardware scrolling ---------------------------------------------------------------

    /// Set up a horizontal scroll (start with [`Self::scroll_start`]); `interval` is a table index 0..=6 =
    /// 6, 32, 64, 128, 3, 4, 2 frames, `columns` 1..=4. Do not call while scrolling is active.
    pub fn set_scroll_horizontal(
        &mut self,
        right: bool,
        start_page: u8,
        end_page: u8,
        interval: u8,
        columns: u8,
    ) -> Result<(), Error<B::Error>> {
        self.command(&[if right { 0x26 } else { 0x27 }, columns & 7, start_page & 7, interval.min(6), end_page & 7])
    }

    /// Set up a vertical + horizontal scroll (`columns` 0..=4, `rows` 1..=63 per step).
    pub fn set_scroll_vertical(
        &mut self,
        right: bool,
        start_page: u8,
        end_page: u8,
        interval: u8,
        columns: u8,
        rows: u8,
    ) -> Result<(), Error<B::Error>> {
        self.command(&[
            if right { 0x29 } else { 0x2A },
            columns & 7,
            start_page & 7,
            interval.min(6),
            end_page & 7,
            rows & 0x3F,
        ])
    }

    /// Set the vertical scroll area: `top_fixed` rows (0..=63) fixed, `scroll_rows` (1..=64) scrolling.
    pub fn set_scroll_area(&mut self, top_fixed: u8, scroll_rows: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_SCROLL_AREA, top_fixed & 0x3F, scroll_rows & 0x7F])
    }

    /// Activate the configured scroll (`0x2F`).
    pub fn scroll_start(&mut self) -> Result<(), Error<B::Error>> {
        self.command(&[0x2F])
    }

    /// Deactivate scrolling (`0x2E`) and rewrite the RAM from the framebuffer, as the datasheet requires.
    pub fn scroll_stop(&mut self) -> Result<(), Error<B::Error>> {
        self.command(&[0x2E])?;
        self.show_all()
    }

    // ---- area colour ----------------------------------------------------------------------

    /// Enable the area-colour mode and/or the low-power display mode (`0xD8`).
    pub fn set_area_color(&mut self, enable: bool, low_power: bool) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_AREA_COLOR, if enable { 0x30 } else { 0 } | if low_power { 0x05 } else { 0 }])
    }

    /// Set the colour-bank brightness, 0..=255 (reset `0x80`).
    pub fn set_brightness(&mut self, value: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_BRIGHTNESS, value])
    }

    /// Set the drive pulse width codes (31..=63) of BANK0 and colours A, B, C.
    pub fn set_lut(&mut self, bank0: u8, a: u8, b: u8, c: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_LUT, bank0 & 0x3F, a & 0x3F, b & 0x3F, c & 0x3F])
    }

    /// Assign colours A..D (2 bits per bank) to banks 1..=16 and optionally banks 17..=32.
    pub fn set_bank_colors(&mut self, banks_1_16: [u8; 4], banks_17_32: Option<[u8; 4]>) -> Result<(), Error<B::Error>> {
        let b = banks_1_16;
        self.command(&[CMD_BANK_1_16, b[0], b[1], b[2], b[3]])?;
        if let Some(b) = banks_17_32 {
            self.command(&[CMD_BANK_17_32, b[0], b[1], b[2], b[3]])?;
        }
        Ok(())
    }

    // ---- reads and raw access -------------------------------------------------------------

    /// Read the status byte (I²C only): bit 6 = 1 when the display is off; other bits reserved.
    ///
    /// # Errors
    ///
    /// [`Error::Unsupported`] on SPI, [`Error::Bus`] if the read fails.
    pub fn read_status(&mut self) -> Result<u8, Error<B::Error>> {
        if !B::CAN_READ {
            return Err(Error::Unsupported);
        }
        self.bus.read_status().map_err(Error::Bus)
    }

    /// Read `buf.len()` bytes (at most `I2C_CHUNK - 1`) of display RAM (I²C only); the dummy byte is discarded.
    ///
    /// # Errors
    ///
    /// [`Error::Unsupported`] on SPI, [`Error::InvalidArgument`] if `buf` is too long, [`Error::Bus`] on failure.
    pub fn read_page(&mut self, page: u8, column: u8, buf: &mut [u8]) -> Result<(), Error<B::Error>> {
        if !B::CAN_READ {
            return Err(Error::Unsupported);
        }
        if buf.len() >= I2C_CHUNK {
            return Err(Error::InvalidArgument);
        }
        self.command(&Ssd1305Minimal::<B>::address_bytes(page, column))?;
        self.bus.read_ram(buf).map_err(Error::Bus)
    }

    /// Send raw bytes as one command stream.
    pub fn send_command(&mut self, bytes: &[u8]) -> Result<(), Error<B::Error>> {
        self.command(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as Tx};

    const A: u8 = 0x3C;

    fn setup(height: u8) -> Vec<u8> {
        vec![
            0x00, 0xAE, 0xD5, 0x70, 0xA8, height - 1, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA,
            if height > 32 { 0x12 } else { 0x02 }, 0x81, 0x80, 0xD9, 0x22, 0xDB, 0x34, 0xAD, 0x8E, 0xA4, 0xA6, 0x20, 0x02,
        ]
    }

    fn addr(page: u8, col: u8) -> Vec<u8> {
        vec![0x00, 0xB0 | page, col & 0x0F, 0x10 | (col >> 4)]
    }

    fn data_tx(data: &[u8]) -> Vec<Tx> {
        data.chunks(I2C_CHUNK - 1)
            .map(|c| {
                let mut v = vec![0x40];
                v.extend_from_slice(c);
                Tx::write(A, v)
            })
            .collect()
    }

    /// Transactions of a complete initialisation (128 x 64).
    fn init_tx() -> Vec<Tx> {
        let mut v = vec![Tx::write(A, setup(64))];
        for p in 0..8 {
            v.push(Tx::write(A, addr(p, 0)));
            v.extend(data_tx(&[0u8; 132]));
        }
        v.push(Tx::write(A, vec![0x00, 0xAF]));
        v
    }

    fn full(extra: Vec<Tx>) -> Ssd1305Full<I2cBus<I2cMock, NoopDelay>> {
        let mut tx = init_tx();
        tx.extend(extra);
        Ssd1305Full::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 64, 4).unwrap()
    }

    fn finish(chip: Ssd1305Full<I2cBus<I2cMock, NoopDelay>>) {
        let (mut i2c, _) = chip.inner.release();
        i2c.done();
    }

    #[test]
    fn init_sequence_and_geometry() {
        let tx = init_tx();
        let chip = Ssd1305Minimal::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 64, 4).unwrap();
        assert_eq!((chip.width(), chip.height()), (128, 64));
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn small_height_uses_sequential_com() {
        let mut tx = vec![Tx::write(A, setup(32))];
        for p in 0..8 {
            tx.push(Tx::write(A, addr(p, 0)));
            tx.extend(data_tx(&[0u8; 132]));
        }
        tx.push(Tx::write(A, vec![0x00, 0xAF]));
        let chip = Ssd1305Minimal::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 32, 0).unwrap();
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn invalid_arguments_are_rejected_without_bus_traffic() {
        for (w, h, o) in [(133, 64, 0), (0, 64, 0), (128, 60, 0), (128, 72, 0), (128, 0, 0), (128, 8, 0), (128, 64, 5)] {
            let mut i2c = I2cMock::new(&[]);
            let r = Ssd1305Minimal::new(i2c.clone(), A, NoopDelay::new(), w, h, o);
            assert!(matches!(r, Err(Error::InvalidArgument)), "{w}x{h}+{o}");
            i2c.done();
        }
    }

    #[test]
    fn pixels_and_show_with_column_offset() {
        let mut p1 = vec![0u8; 128];
        p1[3] = 0x04; // pixel (3, 10)
        let mut p7 = vec![0u8; 128];
        p7[127] = 0x80; // pixel (127, 63)
        let mut tx = init_tx();
        for p in 0..8u8 {
            tx.push(Tx::write(A, addr(p, 4)));
            let page = match p {
                1 => p1.clone(),
                7 => p7.clone(),
                _ => vec![0u8; 128],
            };
            tx.extend(data_tx(&page));
        }
        let mut chip = Ssd1305Minimal::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 64, 4).unwrap();
        chip.set_pixel(3, 10, true);
        chip.set_pixel(127, 63, true);
        chip.set_pixel(128, 0, true); // out of range, ignored
        chip.set_pixel(-1, 5, true);
        chip.show().unwrap();
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn commands() {
        let c = |v: &[u8]| {
            let mut x = vec![0x00];
            x.extend_from_slice(v);
            Tx::write(A, x)
        };
        let extra = vec![
            c(&[0x81, 0x55]),
            c(&[0xAE]),
            c(&[0xAF]),
            c(&[0xAE]),
            c(&[0xAF]),
            c(&[0xA7]),
            c(&[0xA6]),
            c(&[0xA5]),
            c(&[0xA4]),
            c(&[0xA1, 0xC8]),
            c(&[0xA0, 0xC8]),
            c(&[0xA0, 0xC0]),
            c(&[0x68]),
            c(&[0xD3, 12]),
            c(&[0xA8, 47]),
            c(&[0xD5, 0x82]),
            c(&[0xD9, 0x52]),
            c(&[0xDA, 0x02]),
            c(&[0xDA, 0x12]),
            c(&[0xDA, 0x32]),
            c(&[0xDB, 0x3C]),
            c(&[0xAB, 0x00, 0x40, 0x20]),
            c(&[0xAC]),
            c(&[0xAF]),
            c(&[0x82, 0x33]),
            c(&[0x91, 49, 63, 40, 31]),
            c(&[0x92, 1, 2, 3, 4]),
            c(&[0x92, 1, 2, 3, 4]),
            c(&[0x93, 5, 6, 7, 8]),
            c(&[0xD8, 0x35]),
            c(&[0x26, 1, 0, 0, 7]),
            c(&[0x27, 2, 1, 4, 3]),
            c(&[0x29, 1, 0, 6, 7, 5]),
            c(&[0xA3, 8, 40]),
            c(&[0x2F]),
            c(&[0xE3, 0xE3]),
        ];
        let mut chip = full(extra);
        chip.set_contrast(0x55).unwrap();
        chip.set_display(false).unwrap();
        chip.set_display(true).unwrap();
        chip.sleep().unwrap();
        chip.wake().unwrap();
        chip.set_invert(true).unwrap();
        chip.set_invert(false).unwrap();
        chip.set_all_on(true).unwrap();
        chip.set_all_on(false).unwrap();
        chip.set_flip(true, true).unwrap();
        chip.set_flip(false, true).unwrap();
        chip.set_flip(false, false).unwrap();
        chip.set_start_line(40).unwrap();
        chip.set_display_offset(12).unwrap();
        chip.set_multiplex(48).unwrap();
        chip.set_clock(3, 8).unwrap();
        chip.set_precharge(2, 5).unwrap();
        chip.set_com_config(false, false).unwrap();
        chip.set_com_config(true, false).unwrap();
        chip.set_com_config(true, true).unwrap();
        chip.set_vcomh(0xFF).unwrap();
        chip.set_dim_mode(0x40, 0x20).unwrap();
        chip.set_dim(true).unwrap();
        chip.set_dim(false).unwrap();
        chip.set_brightness(0x33).unwrap();
        chip.set_lut(49, 63, 40, 31).unwrap();
        chip.set_bank_colors([1, 2, 3, 4], None).unwrap();
        chip.set_bank_colors([1, 2, 3, 4], Some([5, 6, 7, 8])).unwrap();
        chip.set_area_color(true, true).unwrap();
        chip.set_scroll_horizontal(true, 0, 7, 0, 1).unwrap();
        chip.set_scroll_horizontal(false, 1, 3, 4, 2).unwrap();
        chip.set_scroll_vertical(true, 0, 7, 6, 1, 5).unwrap();
        chip.set_scroll_area(8, 40).unwrap();
        chip.scroll_start().unwrap();
        chip.send_command(&[0xE3, 0xE3]).unwrap();
        finish(chip);
    }

    #[test]
    fn scroll_stop_and_addressing_modes() {
        let mut extra = vec![Tx::write(A, vec![0x00, 0x2E])];
        for p in 0..8u8 {
            extra.push(Tx::write(A, addr(p, 4)));
            extra.extend(data_tx(&[0u8; 128]));
        }
        extra.push(Tx::write(A, vec![0x00, 0x20, 0x00]));
        extra.push(Tx::write(A, vec![0x00, 0x21, 4, 131, 0x22, 0, 7]));
        extra.extend(data_tx(&[0u8; 1024]));
        extra.push(Tx::write(A, vec![0x00, 0x20, 0x01]));
        extra.push(Tx::write(A, vec![0x00, 0x21, 4, 131, 0x22, 0, 7]));
        let mut vert = vec![0u8; 1024];
        vert[1] = 0x02; // pixel (0, 9): column 0, page 1 -> second byte of the column-major stream
        extra.extend(data_tx(&vert));
        extra.push(Tx::write(A, vec![0x00, 0x21, 4, 131, 0x22, 2, 2]));
        extra.extend(data_tx(&[0u8; 128]));
        let mut chip = full(extra);
        chip.scroll_stop().unwrap();
        chip.set_addressing_mode(AddressingMode::Horizontal).unwrap();
        chip.show().unwrap();
        chip.set_addressing_mode(AddressingMode::Vertical).unwrap();
        chip.set_pixel(0, 9, true);
        chip.show().unwrap();
        chip.set_pixel(0, 9, false);
        chip.show_page(2).unwrap();
        finish(chip);
    }

    #[test]
    fn show_page_and_out_of_range() {
        let mut extra = vec![Tx::write(A, addr(2, 4))];
        extra.extend(data_tx(&[0u8; 128]));
        let mut chip = full(extra);
        chip.show_page(2).unwrap();
        chip.show_page(8).unwrap(); // ignored
        finish(chip);
    }

    #[test]
    fn framebuffer_drawing() {
        let mut chip = full(vec![]);
        chip.fill(true);
        assert!(chip.get_pixel(0, 0) && chip.get_pixel(127, 63));
        chip.fill(false);
        assert!(!chip.get_pixel(0, 0) && !chip.get_pixel(-1, 0) && !chip.get_pixel(0, 64));
        chip.draw_line(0, 0, 9, 9, true);
        assert!(chip.get_pixel(0, 0) && chip.get_pixel(5, 5) && chip.get_pixel(9, 9) && !chip.get_pixel(5, 6));
        chip.fill(false);
        chip.draw_rect(10, 20, 5, 4, true, false);
        assert!(chip.get_pixel(10, 20) && chip.get_pixel(14, 23) && chip.get_pixel(12, 20) && !chip.get_pixel(12, 21));
        chip.fill(false);
        chip.draw_rect(10, 20, 3, 3, true, true);
        assert!(chip.get_pixel(11, 21) && chip.get_pixel(12, 22) && !chip.get_pixel(13, 22));
        chip.fill(false);
        chip.blit(4, 8, &[0xFF, 0x0F, 0x01, 0x80], 2).unwrap();
        assert!(chip.get_pixel(4, 8) && chip.get_pixel(4, 15) && chip.get_pixel(5, 8) && !chip.get_pixel(5, 12));
        assert!(chip.get_pixel(5, 11) && chip.get_pixel(4, 16) && chip.get_pixel(5, 23) && !chip.get_pixel(4, 17));
        assert!(matches!(chip.blit(4, 3, &[1, 2], 1), Err(Error::InvalidArgument)));
        finish(chip);
    }

    #[test]
    fn reads() {
        let extra = vec![
            Tx::write(A, vec![0x00]),
            Tx::read(A, vec![0x40]),
            Tx::write(A, addr(2, 5)),
            Tx::write(A, vec![0x40]),
            Tx::read(A, vec![0xEE, 0x11, 0x22, 0x33]),
        ];
        let mut chip = full(extra);
        assert_eq!(chip.read_status().unwrap(), 0x40);
        let mut ram = [0u8; 3];
        chip.read_page(2, 5, &mut ram).unwrap();
        assert_eq!(ram, [0x11, 0x22, 0x33]);
        finish(chip);
    }

    // ---- SPI (4-wire) via a display bus ---------------------------------------------------

    use crate::connection::display::{DisplayBus, DisplayError};

    #[derive(Debug, PartialEq)]
    enum Ev {
        Reset,
        Cmd(u8, Vec<u8>),
        Data(Vec<u8>),
    }

    struct MockBus {
        events: Vec<Ev>,
    }

    impl DisplayBus for MockBus {
        type Error = DisplayError<(), (), (), ()>;
        fn reset(&mut self) -> Result<(), Self::Error> {
            self.events.push(Ev::Reset);
            Ok(())
        }
        fn wait_busy(&mut self, _: bool, _: Option<u32>) -> Result<bool, Self::Error> {
            Ok(true)
        }
        fn send_command(&mut self, cmd: u8, data: &[u8]) -> Result<(), Self::Error> {
            self.events.push(Ev::Cmd(cmd, data.to_vec()));
            Ok(())
        }
        fn send_data(&mut self, data: &[u8]) -> Result<(), Self::Error> {
            self.events.push(Ev::Data(data.to_vec()));
            Ok(())
        }
        fn read_data(&mut self, _: &mut [u8]) -> Result<(), Self::Error> {
            Err(DisplayError::ReadUnavailable)
        }
    }

    #[test]
    fn spi_init_stream_and_clear() {
        let chip = Ssd1305SpiMinimal::new_spi(MockBus { events: vec![] }, 128, 64, 4).unwrap();
        let ev = &chip.bus.conn.events;
        assert_eq!(ev[0], Ev::Reset);
        let cmds: Vec<u8> =
            ev.iter().filter_map(|e| if let Ev::Cmd(c, d) = e { assert!(d.is_empty()); Some(*c) } else { None }).collect();
        assert_eq!(&cmds[..24], &setup(64)[1..]);
        assert_eq!(*cmds.last().unwrap(), 0xAF);
        let data: Vec<&Vec<u8>> = ev.iter().filter_map(|e| if let Ev::Data(d) = e { Some(d) } else { None }).collect();
        assert_eq!(data.len(), 8);
        assert!(data.iter().all(|d| d.len() == 132 && d.iter().all(|&b| b == 0)));
    }

    #[test]
    fn spi_show_sends_8_pages_of_128() {
        let mut chip = Ssd1305SpiMinimal::new_spi(MockBus { events: vec![] }, 128, 64, 4).unwrap();
        let n = chip.bus.conn.events.len();
        chip.set_pixel(5, 0, true);
        chip.show().unwrap();
        let ev = &chip.bus.conn.events[n..];
        assert_eq!(&ev[..3], &[Ev::Cmd(0xB0, vec![]), Ev::Cmd(0x04, vec![]), Ev::Cmd(0x10, vec![])]);
        let shown: Vec<&Vec<u8>> = ev.iter().filter_map(|e| if let Ev::Data(d) = e { Some(d) } else { None }).collect();
        assert_eq!(shown.len(), 8);
        assert!(shown.iter().all(|d| d.len() == 128));
        assert_eq!(shown[0][5], 1);
    }

    #[test]
    fn spi_reads_are_unsupported_and_commands_work() {
        let mut chip = Ssd1305SpiFull::new_spi(MockBus { events: vec![] }, 128, 64, 4).unwrap();
        assert!(matches!(chip.read_status(), Err(Error::Unsupported)));
        let mut buf = [0u8; 4];
        assert!(matches!(chip.read_page(0, 0, &mut buf), Err(Error::Unsupported)));
        let n = chip.bus.conn.events.len();
        chip.set_contrast(0x50).unwrap();
        assert_eq!(&chip.bus.conn.events[n..], &[Ev::Cmd(0x81, vec![]), Ev::Cmd(0x50, vec![])]);
    }
}
