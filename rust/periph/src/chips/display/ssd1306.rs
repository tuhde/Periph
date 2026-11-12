//! SSD1306 — 128 x 64 OLED/PLED segment/common driver with controller (Solomon Systech), the chip behind the
//! common 0.96" 128 x 64 and 128 x 32 monochrome OLED modules.
//!
//! Drives a monochrome SSD1306 panel over I²C or 4-wire SPI. The framebuffer, drawing primitives and the two
//! transports come from [`mono_oled`](super::mono_oled).
//!
//! ## I²C
//!
//! 7-bit address `0x3C` (SA0 = low) or `0x3D` (SA0 = high). Every write starts with a control byte (`0x00` = the
//! rest are commands, `0x40` = the rest is display RAM data); data is sent in writes of at most [`I2C_CHUNK`]
//! bytes including the control byte ([`Ssd1306Minimal::new`]).
//!
//! ## 4-wire SPI
//!
//! [`Ssd1306Minimal::new_spi`] / [`Ssd1306Full::new_spi`] drive the chip through a
//! [`DisplayBus`](crate::connection::display::DisplayBus) (CS, D/C, RES optional): SPI mode 0, up to 10 MHz
//! (4 MHz recommended), BS0 = BS1 = BS2 = 0. Every command byte, including arguments, is sent with D/C low; RAM
//! data with D/C high. [`Ssd1306SpiMinimal`] and [`Ssd1306SpiFull`] are the aliases for these types.
//!
//! ## Framebuffer
//!
//! The chip has an exact 128-column RAM (no column offset). All drawing goes into a framebuffer inside the driver
//! (`width * height / 8` bytes, page-major, bit 0 = top pixel of the page, no heap); `show` sends it page by page
//! (page addressing, the reset default) or as one stream in horizontal / vertical addressing
//! ([`Ssd1306Full::set_addressing_mode`]). The chip cannot be read over its serial interfaces. The
//! initialisation follows the datasheet and the charge-pump application note: setup stream with the display off
//! (charge pump, page addressing), RAM cleared, display on + 100 ms. RAM access is refused with
//! [`Error::ScrollActive`] while a hardware scroll runs.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use super::mono_oled::{MonoFramebuffer, FB_SIZE};
pub use super::mono_oled::{Error, I2cBus, SpiBus, Transport, I2C_CHUNK, SPI_CHUNK};
use crate::connection::display::DisplayBus;

/// Maximum number of RAM columns.
pub const WIDTH: usize = 128;
/// Maximum number of rows.
pub const HEIGHT: usize = 64;

const CMD_ADDR_MODE: u8 = 0x20;
const CMD_COL_RANGE: u8 = 0x21;
const CMD_PAGE_RANGE: u8 = 0x22;
const CMD_START_LINE: u8 = 0x40; // | A[5:0]
const CMD_CONTRAST: u8 = 0x81;
const CMD_CHARGE_PUMP: u8 = 0x8D;
const CMD_SEG_REMAP: u8 = 0xA0; // | ADC
const CMD_SCROLL_AREA: u8 = 0xA3;
const CMD_ALL_ON: u8 = 0xA4; // | 1
const CMD_INVERT: u8 = 0xA6; // | 1
const CMD_MULTIPLEX: u8 = 0xA8;
const CMD_DISPLAY: u8 = 0xAE; // | 1
const CMD_PAGE: u8 = 0xB0; // | A[2:0]
const CMD_SCAN: u8 = 0xC0; // | (scan << 3)
const CMD_OFFSET: u8 = 0xD3;
const CMD_CLOCK: u8 = 0xD5;
const CMD_PRECHARGE: u8 = 0xD9;
const CMD_COM: u8 = 0xDA;
const CMD_VCOMH: u8 = 0xDB;

/// RAM addressing mode used by `show` (see [`Ssd1306Full::set_addressing_mode`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AddressingMode {
    /// Column pointer advances first, then the page (`0x20 0x00`).
    Horizontal,
    /// Page pointer advances first, then the column (`0x20 0x01`).
    Vertical,
    /// One page at a time with explicit page + column commands (`0x20 0x02`, reset default).
    Page,
}

/// SSD1306 minimal driver: a monochrome framebuffer shown on the panel.
///
/// See the [module documentation](self).
pub struct Ssd1306Minimal<B> {
    bus: B,
    canvas: MonoFramebuffer,
    external_vcc: bool,
    mode: AddressingMode,
    scrolling: bool,
    adc: u8,
    scan: u8,
}

impl<I2C: I2c, D: DelayNs> Ssd1306Minimal<I2cBus<I2C, D>> {
    /// Create the driver on an I²C bus and run the initialisation sequence (about 100 ms).
    ///
    /// # Arguments
    ///
    /// * `i2c`          – I²C bus.
    /// * `addr`         – 7-bit address, `0x3C` (SA0 = low) or `0x3D` (SA0 = high).
    /// * `delay`        – Delay provider.
    /// * `width`        – Visible columns, 1..=128.
    /// * `height`       – Rows, a multiple of 8, 16..=64 (up to 32 uses sequential COM wiring).
    /// * `external_vcc` – `true` when the board supplies VCC (the charge pump stays off).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size, [`Error::Bus`] when a transfer fails.
    pub fn new(
        i2c: I2C,
        addr: u8,
        delay: D,
        width: usize,
        height: usize,
        external_vcc: bool,
    ) -> Result<Self, Error<I2C::Error>> {
        Self::with_bus(I2cBus { i2c, delay, addr }, width, height, external_vcc)
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        (self.bus.i2c, self.bus.delay)
    }
}

impl<DB: DisplayBus> Ssd1306Minimal<SpiBus<DB>> {
    /// Create the driver on a 4-wire SPI display connection and run the initialisation sequence (about 100 ms).
    ///
    /// # Arguments
    ///
    /// * `connection`   – [`DisplayBus`] (CS, D/C, RES optional, no BUSY).
    /// * `width`        – Visible columns, 1..=128.
    /// * `height`       – Rows, a multiple of 8, 16..=64.
    /// * `external_vcc` – `true` when the board supplies VCC (the charge pump stays off).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size, [`Error::Bus`] when a transfer fails.
    pub fn new_spi(
        connection: DB,
        width: usize,
        height: usize,
        external_vcc: bool,
    ) -> Result<Self, Error<DB::Error>> {
        Self::with_bus(SpiBus { conn: connection }, width, height, external_vcc)
    }

    /// Release the display connection.
    pub fn release_spi(self) -> DB {
        self.bus.conn
    }
}

impl<B: Transport> Ssd1306Minimal<B> {
    fn with_bus(bus: B, width: usize, height: usize, external_vcc: bool) -> Result<Self, Error<B::Error>> {
        if !(1..=WIDTH).contains(&width) || !(16..=HEIGHT).contains(&height) || height % 8 != 0 {
            return Err(Error::InvalidArgument);
        }
        let mut d = Self {
            bus,
            canvas: MonoFramebuffer::new(width, height),
            external_vcc,
            mode: AddressingMode::Page,
            scrolling: false,
            adc: 0,
            scan: 0,
        };
        d.init_sequence()?;
        Ok(d)
    }

    /// Visible columns of the framebuffer.
    pub fn width(&self) -> usize {
        self.canvas.width()
    }

    /// Rows of the framebuffer.
    pub fn height(&self) -> usize {
        self.canvas.height()
    }

    /// Zero the framebuffer. Call [`show`](Self::show) to update the panel.
    pub fn clear(&mut self) {
        self.canvas.clear();
    }

    /// Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
    pub fn set_pixel(&mut self, x: i32, y: i32, on: bool) {
        self.canvas.set_pixel(x, y, on);
    }

    /// Write the whole framebuffer to the display RAM: per page a page + column command stream and `width` data
    /// bytes (page addressing), or range commands and one data stream (horizontal / vertical addressing).
    ///
    /// # Errors
    ///
    /// [`Error::ScrollActive`] while a hardware scroll runs, [`Error::Bus`] if a transfer fails.
    pub fn show(&mut self) -> Result<(), Error<B::Error>> {
        self.show_all()
    }

    // ---- internals ------------------------------------------------------------------------

    fn command(&mut self, bytes: &[u8]) -> Result<(), Error<B::Error>> {
        self.bus.write_commands(bytes).map_err(Error::Bus)
    }

    fn address_bytes(page: u8) -> [u8; 3] {
        [CMD_PAGE | (page & 7), 0x00, 0x10]
    }

    fn show_page_inner(&mut self, page: u8) -> Result<(), Error<B::Error>> {
        self.command(&Self::address_bytes(page))?;
        self.bus.write_data(self.canvas.page(page as usize)).map_err(Error::Bus)
    }

    fn set_ranges(&mut self, first_page: u8, last_page: u8) -> Result<(), Error<B::Error>> {
        let last_col = (self.canvas.width() - 1) as u8;
        self.command(&[CMD_COL_RANGE, 0, last_col, CMD_PAGE_RANGE, first_page, last_page])
    }

    fn show_all(&mut self) -> Result<(), Error<B::Error>> {
        if self.scrolling {
            return Err(Error::ScrollActive);
        }
        let pages = self.canvas.pages() as u8;
        if self.mode == AddressingMode::Page {
            for page in 0..pages {
                self.show_page_inner(page)?;
            }
            return Ok(());
        }
        self.set_ranges(0, pages - 1)?;
        if self.mode == AddressingMode::Horizontal {
            self.bus.write_data(self.canvas.bytes()).map_err(Error::Bus)
        } else {
            let mut buf = [0u8; FB_SIZE];
            self.canvas.transposed(&mut buf);
            let n = self.canvas.bytes().len();
            self.bus.write_data(&buf[..n]).map_err(Error::Bus)
        }
    }

    /// Datasheet power-on flow: reset, setup stream, RAM clear, display on.
    fn init_sequence(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.hardware_reset().map_err(Error::Bus)?;
        self.bus.delay_ms(1);
        let stream = [
            CMD_DISPLAY,
            CMD_CLOCK,
            0x80,
            CMD_MULTIPLEX,
            (self.canvas.height() - 1) as u8,
            CMD_OFFSET,
            0x00,
            CMD_START_LINE,
            CMD_CHARGE_PUMP,
            if self.external_vcc { 0x10 } else { 0x14 },
            CMD_ADDR_MODE,
            0x02,
            CMD_SEG_REMAP | self.adc,
            CMD_SCAN | (self.scan << 3),
            CMD_COM,
            if self.canvas.height() > 32 { 0x12 } else { 0x02 },
            CMD_CONTRAST,
            0x7F,
            CMD_PRECHARGE,
            0x22,
            CMD_VCOMH,
            0x20,
            CMD_ALL_ON,
            CMD_INVERT,
        ];
        self.command(&stream)?;
        let zeros = [0u8; WIDTH];
        for page in 0..8u8 {
            self.command(&Self::address_bytes(page))?;
            self.bus.write_data(&zeros).map_err(Error::Bus)?;
        }
        self.command(&[CMD_DISPLAY | 1])?;
        self.bus.delay_ms(100);
        Ok(())
    }
}

/// SSD1306 full driver — extends [`Ssd1306Minimal`] (reachable through `Deref`) with drawing primitives, contrast,
/// sleep, invert, all-on test, flip, start line, display offset, multiplex ratio, clock, pre-charge, COM
/// configuration, VCOMH level, charge pump, addressing mode, hardware scrolling and raw commands.
pub struct Ssd1306Full<B> {
    inner: Ssd1306Minimal<B>,
}

impl<B> core::ops::Deref for Ssd1306Full<B> {
    type Target = Ssd1306Minimal<B>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<B> core::ops::DerefMut for Ssd1306Full<B> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

/// SSD1306 over 4-wire SPI — minimal driver ([`Ssd1306Minimal`] on a [`SpiBus`]; create it with `new_spi`).
pub type Ssd1306SpiMinimal<DB> = Ssd1306Minimal<SpiBus<DB>>;
/// SSD1306 over 4-wire SPI — full driver ([`Ssd1306Full`] on a [`SpiBus`]; create it with `new_spi`).
pub type Ssd1306SpiFull<DB> = Ssd1306Full<SpiBus<DB>>;

impl<I2C: I2c, D: DelayNs> Ssd1306Full<I2cBus<I2C, D>> {
    /// Create the driver on an I²C bus and run the initialisation sequence (arguments as for
    /// [`Ssd1306Minimal::new`]).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size, [`Error::Bus`] when a transfer fails.
    pub fn new(
        i2c: I2C,
        addr: u8,
        delay: D,
        width: usize,
        height: usize,
        external_vcc: bool,
    ) -> Result<Self, Error<I2C::Error>> {
        Ok(Self { inner: Ssd1306Minimal::new(i2c, addr, delay, width, height, external_vcc)? })
    }
}

impl<DB: DisplayBus> Ssd1306Full<SpiBus<DB>> {
    /// Create the driver on a 4-wire SPI display connection and run the initialisation sequence (arguments as for
    /// [`Ssd1306Minimal::new_spi`]).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size, [`Error::Bus`] when a transfer fails.
    pub fn new_spi(
        connection: DB,
        width: usize,
        height: usize,
        external_vcc: bool,
    ) -> Result<Self, Error<DB::Error>> {
        Ok(Self { inner: Ssd1306Minimal::new_spi(connection, width, height, external_vcc)? })
    }
}

impl<B: Transport> Ssd1306Full<B> {
    // ---- framebuffer ----------------------------------------------------------------------

    /// Return a framebuffer pixel; out-of-range coordinates read as `false`.
    pub fn get_pixel(&self, x: i32, y: i32) -> bool {
        self.canvas.get_pixel(x, y)
    }

    /// Set (or clear) the whole framebuffer.
    pub fn fill(&mut self, on: bool) {
        self.canvas.fill(on);
    }

    /// Draw a Bresenham line into the framebuffer.
    pub fn draw_line(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, on: bool) {
        self.canvas.draw_line(x0, y0, x1, y1, on);
    }

    /// Draw a rectangle outline or a filled rectangle into the framebuffer.
    pub fn draw_rect(&mut self, x: i32, y: i32, w: i32, h: i32, on: bool, fill: bool) {
        self.canvas.draw_rect(x, y, w, h, on, fill);
    }

    /// Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer; `columns` holds
    /// `height_pages` groups of columns, page by page. Clipped to the framebuffer.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] if `y` is not a multiple of 8 or `height_pages` is 0.
    pub fn blit(&mut self, x: i32, y: i32, columns: &[u8], height_pages: usize) -> Result<(), Error<B::Error>> {
        if self.canvas.blit(x, y, columns, height_pages) {
            Ok(())
        } else {
            Err(Error::InvalidArgument)
        }
    }

    /// Write one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are ignored.
    ///
    /// # Errors
    ///
    /// [`Error::ScrollActive`] while a hardware scroll runs, [`Error::Bus`] if a transfer fails.
    pub fn show_page(&mut self, page: u8) -> Result<(), Error<B::Error>> {
        if self.scrolling {
            return Err(Error::ScrollActive);
        }
        if page as usize >= self.canvas.pages() {
            return Ok(());
        }
        if self.mode == AddressingMode::Page {
            return self.show_page_inner(page);
        }
        let inner = &mut self.inner;
        inner.set_ranges(page, page)?;
        inner.bus.write_data(inner.canvas.page(page as usize)).map_err(Error::Bus)
    }

    // ---- display control ------------------------------------------------------------------

    /// Set the segment output current (contrast), 0..=255 (reset `0x7F`).
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

    /// Set the clock divide ratio (1..=16) and oscillator setting (0..=15, 8 = reset value).
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

    /// Set the VCOMH deselect level (only bits 6:4 used; reset `0x20`).
    pub fn set_vcomh(&mut self, value: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_VCOMH, value & 0x70])
    }

    /// Enable (`0x14`) or disable (`0x10`) the internal charge pump; takes effect with the display on.
    pub fn set_charge_pump(&mut self, enabled: bool) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_CHARGE_PUMP, if enabled { 0x14 } else { 0x10 }])
    }

    /// Select the RAM addressing mode used by [`Ssd1306Minimal::show`] (reset: [`AddressingMode::Page`]).
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

    /// Set up a horizontal scroll, one column per step (start with [`Self::scroll_start`]); `interval` is a table
    /// index 0..=7 = 5, 64, 128, 256, 3, 4, 25, 2 frames. Do not call while scrolling is active.
    pub fn set_scroll_horizontal(
        &mut self,
        right: bool,
        start_page: u8,
        end_page: u8,
        interval: u8,
    ) -> Result<(), Error<B::Error>> {
        self.command(&[if right { 0x26 } else { 0x27 }, 0x00, start_page & 7, interval & 7, end_page & 7, 0x00, 0xFF])
    }

    /// Set up a vertical + horizontal (diagonal) scroll (`rows` 1..=63 per step).
    pub fn set_scroll_vertical(
        &mut self,
        right: bool,
        start_page: u8,
        end_page: u8,
        interval: u8,
        rows: u8,
    ) -> Result<(), Error<B::Error>> {
        self.command(&[if right { 0x29 } else { 0x2A }, 0x00, start_page & 7, interval & 7, end_page & 7, rows & 0x3F])
    }

    /// Set the vertical scroll area: `top_fixed` rows (0..=63) fixed, `scroll_rows` (1..=64) scrolling.
    pub fn set_scroll_area(&mut self, top_fixed: u8, scroll_rows: u8) -> Result<(), Error<B::Error>> {
        self.command(&[CMD_SCROLL_AREA, top_fixed & 0x3F, scroll_rows & 0x7F])
    }

    /// Activate the configured scroll (`0x2F`); `show` / `show_page` are refused until [`Self::scroll_stop`].
    pub fn scroll_start(&mut self) -> Result<(), Error<B::Error>> {
        self.command(&[0x2F])?;
        self.scrolling = true;
        Ok(())
    }

    /// Deactivate scrolling (`0x2E`) and rewrite the RAM from the framebuffer, as the datasheet requires.
    pub fn scroll_stop(&mut self) -> Result<(), Error<B::Error>> {
        self.command(&[0x2E])?;
        self.scrolling = false;
        self.show_all()
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

    fn setup(height: u8, pump: u8) -> Vec<u8> {
        vec![
            0x00, 0xAE, 0xD5, 0x80, 0xA8, height - 1, 0xD3, 0x00, 0x40, 0x8D, pump, 0x20, 0x02, 0xA0, 0xC0, 0xDA,
            if height > 32 { 0x12 } else { 0x02 }, 0x81, 0x7F, 0xD9, 0x22, 0xDB, 0x20, 0xA4, 0xA6,
        ]
    }

    fn addr(page: u8) -> Vec<u8> {
        vec![0x00, 0xB0 | page, 0x00, 0x10]
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

    /// Transactions of a complete initialisation (128 x 64, charge pump).
    fn init_tx() -> Vec<Tx> {
        let mut v = vec![Tx::write(A, setup(64, 0x14))];
        for p in 0..8 {
            v.push(Tx::write(A, addr(p)));
            v.extend(data_tx(&[0u8; 128]));
        }
        v.push(Tx::write(A, vec![0x00, 0xAF]));
        v
    }

    fn full(extra: Vec<Tx>) -> Ssd1306Full<I2cBus<I2cMock, NoopDelay>> {
        let mut tx = init_tx();
        tx.extend(extra);
        Ssd1306Full::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 64, false).unwrap()
    }

    fn finish(chip: Ssd1306Full<I2cBus<I2cMock, NoopDelay>>) {
        let (mut i2c, _) = chip.inner.release();
        i2c.done();
    }

    #[test]
    fn init_sequence_and_geometry() {
        let tx = init_tx();
        let chip = Ssd1306Minimal::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 64, false).unwrap();
        assert_eq!((chip.width(), chip.height()), (128, 64));
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn external_vcc_and_small_height() {
        let mut tx = vec![Tx::write(A, setup(32, 0x10))];
        for p in 0..8 {
            tx.push(Tx::write(A, addr(p)));
            tx.extend(data_tx(&[0u8; 128]));
        }
        tx.push(Tx::write(A, vec![0x00, 0xAF]));
        let chip = Ssd1306Minimal::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 32, true).unwrap();
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn invalid_arguments_are_rejected_without_bus_traffic() {
        for (w, h) in [(129, 64), (0, 64), (128, 60), (128, 72), (128, 0), (128, 8)] {
            let mut i2c = I2cMock::new(&[]);
            let r = Ssd1306Minimal::new(i2c.clone(), A, NoopDelay::new(), w, h, false);
            assert!(matches!(r, Err(Error::InvalidArgument)), "{w}x{h}");
            i2c.done();
        }
    }

    #[test]
    fn pixels_and_show() {
        let mut p1 = vec![0u8; 128];
        p1[3] = 0x04; // pixel (3, 10)
        let mut p7 = vec![0u8; 128];
        p7[127] = 0x80; // pixel (127, 63)
        let mut tx = init_tx();
        for p in 0..8u8 {
            tx.push(Tx::write(A, addr(p)));
            let page = match p {
                1 => p1.clone(),
                7 => p7.clone(),
                _ => vec![0u8; 128],
            };
            tx.extend(data_tx(&page));
        }
        let mut chip = Ssd1306Minimal::new(I2cMock::new(&tx), A, NoopDelay::new(), 128, 64, false).unwrap();
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
            c(&[0xDB, 0x70]),
            c(&[0x8D, 0x14]),
            c(&[0x8D, 0x10]),
            c(&[0x26, 0x00, 0, 7, 7, 0x00, 0xFF]),
            c(&[0x27, 0x00, 1, 4, 3, 0x00, 0xFF]),
            c(&[0x29, 0x00, 0, 6, 7, 5]),
            c(&[0xA3, 8, 40]),
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
        chip.set_charge_pump(true).unwrap();
        chip.set_charge_pump(false).unwrap();
        chip.set_scroll_horizontal(true, 0, 7, 7).unwrap();
        chip.set_scroll_horizontal(false, 1, 3, 4).unwrap();
        chip.set_scroll_vertical(true, 0, 7, 6, 5).unwrap();
        chip.set_scroll_area(8, 40).unwrap();
        chip.send_command(&[0xE3, 0xE3]).unwrap();
        finish(chip);
    }

    #[test]
    fn scrolling_blocks_ram_access_and_stop_rewrites() {
        let mut extra = vec![Tx::write(A, vec![0x00, 0x2F]), Tx::write(A, vec![0x00, 0x2E])];
        for p in 0..8u8 {
            extra.push(Tx::write(A, addr(p)));
            extra.extend(data_tx(&[0u8; 128]));
        }
        let mut chip = full(extra);
        chip.scroll_start().unwrap();
        assert!(matches!(chip.show(), Err(Error::ScrollActive)));
        assert!(matches!(chip.show_page(0), Err(Error::ScrollActive)));
        chip.scroll_stop().unwrap();
        finish(chip);
    }

    #[test]
    fn addressing_modes() {
        let mut extra = vec![Tx::write(A, vec![0x00, 0x20, 0x00]), Tx::write(A, vec![0x00, 0x21, 0, 127, 0x22, 0, 7])];
        extra.extend(data_tx(&[0u8; 1024]));
        extra.push(Tx::write(A, vec![0x00, 0x20, 0x01]));
        extra.push(Tx::write(A, vec![0x00, 0x21, 0, 127, 0x22, 0, 7]));
        let mut vert = vec![0u8; 1024];
        vert[1] = 0x02; // pixel (0, 9): column 0, page 1 -> second byte of the column-major stream
        extra.extend(data_tx(&vert));
        extra.push(Tx::write(A, vec![0x00, 0x21, 0, 127, 0x22, 2, 2]));
        extra.extend(data_tx(&[0u8; 128]));
        let mut chip = full(extra);
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
        let mut extra = vec![Tx::write(A, addr(2))];
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
        let chip = Ssd1306SpiMinimal::new_spi(MockBus { events: vec![] }, 128, 64, false).unwrap();
        let ev = &chip.bus.conn.events;
        assert_eq!(ev[0], Ev::Reset);
        let cmds: Vec<u8> =
            ev.iter().filter_map(|e| if let Ev::Cmd(c, d) = e { assert!(d.is_empty()); Some(*c) } else { None }).collect();
        assert_eq!(&cmds[..24], &setup(64, 0x14)[1..]);
        assert_eq!(*cmds.last().unwrap(), 0xAF);
        let data: Vec<&Vec<u8>> = ev.iter().filter_map(|e| if let Ev::Data(d) = e { Some(d) } else { None }).collect();
        assert_eq!(data.len(), 8);
        assert!(data.iter().all(|d| d.len() == 128 && d.iter().all(|&b| b == 0)));
    }

    #[test]
    fn spi_show_sends_8_pages_of_128() {
        let mut chip = Ssd1306SpiMinimal::new_spi(MockBus { events: vec![] }, 128, 64, false).unwrap();
        let n = chip.bus.conn.events.len();
        chip.set_pixel(5, 0, true);
        chip.show().unwrap();
        let ev = &chip.bus.conn.events[n..];
        assert_eq!(&ev[..3], &[Ev::Cmd(0xB0, vec![]), Ev::Cmd(0x00, vec![]), Ev::Cmd(0x10, vec![])]);
        let shown: Vec<&Vec<u8>> = ev.iter().filter_map(|e| if let Ev::Data(d) = e { Some(d) } else { None }).collect();
        assert_eq!(shown.len(), 8);
        assert!(shown.iter().all(|d| d.len() == 128));
        assert_eq!(shown[0][5], 1);
    }

    #[test]
    fn spi_commands_work() {
        let mut chip = Ssd1306SpiFull::new_spi(MockBus { events: vec![] }, 128, 64, false).unwrap();
        let n = chip.bus.conn.events.len();
        chip.set_contrast(0x50).unwrap();
        assert_eq!(&chip.bus.conn.events[n..], &[Ev::Cmd(0x81, vec![]), Ev::Cmd(0x50, vec![])]);
    }
}
