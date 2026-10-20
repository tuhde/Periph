//! UC1611s — 160 COM x 256 SEG graphic LCD controller-driver (UltraChip), as used on the EA DOGXL240-7.
//!
//! Drives a 240 x 128 black/white STN/FSTN module in 1 bit per pixel mode over I²C. The chip carries the
//! command/data bit in its I²C address, so it answers on two consecutive 7-bit addresses: `0x38 + 2n` for
//! commands and `0x39 + 2n` for display RAM data (n = A3 << 1 | A2, the two address pins). One bus is enough:
//! the driver takes the command address and uses address + 1 for data.
//!
//! All drawing goes into a framebuffer inside the driver (`width * height / 8` bytes, page-major, bit 0 = top
//! pixel of the page, 3840 bytes at 240 x 128, no heap); `show` sends it to the display RAM. The
//! initialisation follows the DOG module's bottom-view example: System Reset, temperature compensation
//! -0.10 %/degC, COM end 127, potentiometer (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.
//!
//! ## Error handling
//!
//! The chip occasionally NACKs a transfer (seen on long cables and for about 6 ms while the charge pump
//! restarts). Every transfer is retried up to [`MAX_ATTEMPTS`] times, [`RETRY_DELAY_MS`] apart: a command
//! stream is repeated and a RAM page is rewritten from its address command (a partial write cannot be
//! continued). Repeated attempts are counted ([`Uc1611sMinimal::retry_count`]); a transfer that still fails
//! returns its error and is counted in [`Uc1611sMinimal::error_count`].

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Columns of the EA DOGXL240-7.
pub const WIDTH: usize = 240;
/// Rows of the EA DOGXL240-7.
pub const HEIGHT: usize = 128;
/// Largest data write in bytes (the 32-byte limit of some I²C layers).
pub const I2C_CHUNK: usize = 32;
/// Attempts per transfer before it is counted as failed.
pub const MAX_ATTEMPTS: u8 = 8;
/// Wait between two attempts in milliseconds.
pub const RETRY_DELAY_MS: u32 = 2;

const FB_SIZE: usize = WIDTH * HEIGHT / 8;
const PAGES_RAM: u8 = 20; // RAM pages cleared at init (160 rows / 8)

const CMD_TEMP: u8 = 0x24; // | TC[1:0]
const CMD_PANEL: u8 = 0x28; // | PC[1:0]
const CMD_PUMP: u8 = 0x2C; // | PC[3:2]
const CMD_SCROLL_LO: u8 = 0x40; // | SL[3:0]
const CMD_SCROLL_HI: u8 = 0x50; // | SL[7:4]
const CMD_PAGE_LO: u8 = 0x60; // | PA[3:0]
const CMD_PAGE_HI: u8 = 0x70; // | PA[6:4]
const CMD_POTENTIOMETER: u8 = 0x81;
const CMD_ISOLATION: u8 = 0x82;
const CMD_PARTIAL: u8 = 0x84; // | LC[9:8]
const CMD_RAM_ADDR: u8 = 0x88; // | AC[2:0]
const CMD_FIXED: u8 = 0x90; // | FL[3:0]
const CMD_LINE_RATE: u8 = 0xA0; // | LC[5:4]
const CMD_ALL_ON: u8 = 0xA4; // | DC1
const CMD_INVERSE: u8 = 0xA6; // | DC0
const CMD_DISPLAY: u8 = 0xA8; // | DC[4:2]
const CMD_MAPPING: u8 = 0xC0;
const CMD_NLINE: u8 = 0xC8;
const CMD_PATTERN: u8 = 0xD0; // | DC[7:5]
const CMD_RESET: u8 = 0xE2;
const CMD_NOP: u8 = 0xE3;
const CMD_BIAS: u8 = 0xE8; // | BR[1:0]
const CMD_COM_END: u8 = 0xF1;
const CMD_PART_START: u8 = 0xF2;
const CMD_PART_END: u8 = 0xF3;

/// Error type of the UC1611s driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The I²C bus reported an error (after all attempts).
    Bus(E),
    /// A size, position or buffer argument was out of range.
    InvalidArgument,
}

/// UC1611s minimal driver — a 240 x 128 black/white framebuffer display.
///
/// See the [module documentation](self).
pub struct Uc1611sMinimal<I2C, D> {
    i2c: I2C,
    delay: D,
    cmd_addr: u8,
    data_addr: u8,
    width: usize,
    height: usize,
    pages: u8,
    mx: u8,
    my: u8,
    pattern: u8,
    contrast: u8,
    retries: u32,
    errors: u32,
    fb: [u8; FB_SIZE],
}

impl<I2C: I2c, D: DelayNs> Uc1611sMinimal<I2C, D> {
    /// Create the driver and run the initialisation sequence (about 400 ms): wait 150 ms, System Reset, wait
    /// 150 ms, configuration, RAM clear, display on, poll Status1.DE.
    ///
    /// # Arguments
    ///
    /// * `i2c`      – I²C bus.
    /// * `cmd_addr` – 7-bit command address (`0x38`, `0x3A`, `0x3C` or `0x3E`); the data address is `cmd_addr + 1`.
    /// * `delay`    – Delay provider.
    /// * `width`    – Columns, 1..=240 (default [`WIDTH`]).
    /// * `height`   – Rows, a multiple of 8, 8..=128 (default [`HEIGHT`]).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size, [`Error::Bus`] when the chip does not acknowledge the System Reset.
    pub fn new(i2c: I2C, cmd_addr: u8, delay: D, width: usize, height: usize) -> Result<Self, Error<I2C::Error>> {
        Self::with_options(i2c, cmd_addr, delay, width, height, false, 0x8F)
    }

    fn with_options(
        i2c: I2C,
        cmd_addr: u8,
        delay: D,
        width: usize,
        height: usize,
        top_view: bool,
        contrast: u8,
    ) -> Result<Self, Error<I2C::Error>> {
        if !(1..=WIDTH).contains(&width) || !(8..=HEIGHT).contains(&height) || height % 8 != 0 {
            return Err(Error::InvalidArgument);
        }
        let mut d = Self {
            i2c,
            delay,
            cmd_addr,
            data_addr: cmd_addr + 1,
            width,
            height,
            pages: (height / 8) as u8,
            mx: if top_view { 0 } else { 1 },
            my: if top_view { 1 } else { 0 },
            pattern: 0,
            contrast,
            retries: 0,
            errors: 0,
            fb: [0; FB_SIZE],
        };
        d.init_sequence()?;
        Ok(d)
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        (self.i2c, self.delay)
    }

    /// Columns of the framebuffer.
    pub fn width(&self) -> usize {
        self.width
    }

    /// Rows of the framebuffer.
    pub fn height(&self) -> usize {
        self.height
    }

    /// `true` if no transfer has failed after all retries since construction or [`Self::clear_errors`].
    pub fn ok(&self) -> bool {
        self.errors == 0
    }

    /// Number of transfer attempts that had to be repeated.
    pub fn retry_count(&self) -> u32 {
        self.retries
    }

    /// Number of transfers that still failed after [`MAX_ATTEMPTS`] attempts.
    pub fn error_count(&self) -> u32 {
        self.errors
    }

    /// Reset the retry and error counters.
    pub fn clear_errors(&mut self) {
        self.retries = 0;
        self.errors = 0;
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

    /// Write the whole framebuffer to the display RAM (page + column, then `width` data bytes per page).
    ///
    /// # Errors
    ///
    /// [`Error::Bus`] if a page still fails after all attempts.
    pub fn show(&mut self) -> Result<(), Error<I2C::Error>> {
        for page in 0..self.pages {
            self.show_page_inner(page)?;
        }
        Ok(())
    }

    // ---- internals ------------------------------------------------------------------------

    fn command(&mut self, bytes: &[u8]) -> Result<(), Error<I2C::Error>> {
        let mut last = None;
        for _ in 0..MAX_ATTEMPTS {
            match self.i2c.write(self.cmd_addr, bytes) {
                Ok(()) => return Ok(()),
                Err(e) => {
                    last = Some(e);
                    self.retries += 1;
                    self.delay.delay_ms(RETRY_DELAY_MS);
                }
            }
        }
        self.errors += 1;
        Err(Error::Bus(last.unwrap()))
    }

    /// One pass over the data in chunks of at most [`I2C_CHUNK`] bytes.
    fn write_chunks(&mut self, data: &[u8]) -> Result<(), I2C::Error> {
        for chunk in data.chunks(I2C_CHUNK) {
            self.i2c.write(self.data_addr, chunk)?;
        }
        Ok(())
    }

    fn address_bytes(&self, page: u8, column: u8) -> [u8; 4] {
        let pa = (self.pattern << 5) | (page & 0x1F);
        [CMD_PAGE_LO | (pa & 0x0F), CMD_PAGE_HI | ((pa >> 4) & 0x07), column & 0x0F, 0x10 | (column >> 4)]
    }

    /// Write one page: address command, then the data; a failure repeats both.
    fn write_page(&mut self, page: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        let mut last = None;
        for _ in 0..MAX_ATTEMPTS {
            let addr = self.address_bytes(page, 0);
            let r = match self.i2c.write(self.cmd_addr, &addr) {
                Ok(()) => self.write_chunks(data),
                Err(e) => Err(e),
            };
            match r {
                Ok(()) => return Ok(()),
                Err(e) => {
                    last = Some(e);
                    self.retries += 1;
                    self.delay.delay_ms(RETRY_DELAY_MS);
                }
            }
        }
        self.errors += 1;
        Err(Error::Bus(last.unwrap()))
    }

    fn show_page_inner(&mut self, page: u8) -> Result<(), Error<I2C::Error>> {
        let start = page as usize * self.width;
        let mut buf = [0u8; WIDTH];
        buf[..self.width].copy_from_slice(&self.fb[start..start + self.width]);
        let w = self.width;
        self.write_page(page, &buf[..w])
    }

    /// Read Status1..3; a NOP write first restarts the chip's status cycle (it advances by n + 1 per read).
    fn read_status_inner(&mut self) -> Result<[u8; 3], Error<I2C::Error>> {
        let mut last = None;
        for _ in 0..MAX_ATTEMPTS {
            let mut s = [0u8; 3];
            let r = match self.i2c.write(self.cmd_addr, &[CMD_NOP]) {
                Ok(()) => self.i2c.read(self.cmd_addr, &mut s),
                Err(e) => Err(e),
            };
            match r {
                Ok(()) => return Ok(s),
                Err(e) => {
                    last = Some(e);
                    self.retries += 1;
                    self.delay.delay_ms(RETRY_DELAY_MS);
                }
            }
        }
        self.errors += 1;
        Err(Error::Bus(last.unwrap()))
    }

    /// Poll Status1.DE until the pump is up; NACKs while it starts are expected and not counted.
    fn wait_display_enabled(&mut self, timeout_ms: u32) {
        let mut waited = 0;
        while waited <= timeout_ms {
            let mut s = [0u8; 3];
            if self.i2c.write(self.cmd_addr, &[CMD_NOP]).is_ok()
                && self.i2c.read(self.cmd_addr, &mut s).is_ok()
                && s[0] & 0x08 != 0
            {
                return;
            }
            self.delay.delay_ms(RETRY_DELAY_MS);
            waited += RETRY_DELAY_MS;
        }
    }

    fn mapping(&self) -> u8 {
        (self.my << 2) | (self.mx << 1)
    }

    /// System Reset in its own transaction, then the conservative 150 ms wait.
    fn reset_chip(&mut self) -> Result<(), Error<I2C::Error>> {
        self.command(&[CMD_RESET])?;
        self.delay.delay_ms(150);
        Ok(())
    }

    /// Configuration stream of the DOG bottom-view example (the display stays off).
    fn configure(&mut self) -> Result<(), Error<I2C::Error>> {
        let last = (self.height - 1) as u8;
        let stream = [
            CMD_TEMP | 1,
            CMD_MAPPING,
            self.mapping(),
            CMD_LINE_RATE | 3,
            CMD_COM_END,
            last,
            CMD_PART_START,
            0,
            CMD_PART_END,
            last,
            CMD_POTENTIOMETER,
            self.contrast,
            CMD_RAM_ADDR | 1,
            CMD_PATTERN | 1,
        ];
        self.command(&stream)
    }

    fn enable(&mut self) -> Result<(), Error<I2C::Error>> {
        self.command(&[CMD_DISPLAY | 1])
    }

    fn init_sequence(&mut self) -> Result<(), Error<I2C::Error>> {
        self.delay.delay_ms(150);
        self.reset_chip()?;
        self.configure()?;
        let zeros = [0u8; WIDTH];
        let w = self.width;
        for page in 0..PAGES_RAM {
            self.write_page(page, &zeros[..w])?;
        }
        self.enable()?;
        self.wait_display_enabled(200);
        Ok(())
    }
}

/// UC1611s full driver — extends [`Uc1611sMinimal`] (reachable through `Deref`) with drawing primitives,
/// contrast, sleep, invert, flip / top view, hardware scroll with fixed lines, bias ratio, temperature
/// compensation, line rate, charge pump / external VLCD, panel loading, partial display, N-line inversion,
/// isolation clock, the four RAM patterns, status and RAM reads, and raw commands.
pub struct Uc1611sFull<I2C, D> {
    inner: Uc1611sMinimal<I2C, D>,
}

impl<I2C, D> core::ops::Deref for Uc1611sFull<I2C, D> {
    type Target = Uc1611sMinimal<I2C, D>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<I2C, D> core::ops::DerefMut for Uc1611sFull<I2C, D> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl<I2C: I2c, D: DelayNs> Uc1611sFull<I2C, D> {
    /// Create the driver and run the initialisation sequence.
    ///
    /// # Arguments
    ///
    /// * `i2c`      – I²C bus.
    /// * `cmd_addr` – 7-bit command address (`0x38`, `0x3A`, `0x3C` or `0x3E`); the data address is `cmd_addr + 1`.
    /// * `delay`    – Delay provider.
    /// * `width`    – Columns, 1..=240.
    /// * `height`   – Rows, a multiple of 8, 8..=128.
    /// * `top_view` – `true` for the 12:00 viewing direction (MY = 1, MX = 0).
    /// * `contrast` – Potentiometer PM 0..=255 (the DOG example value is `0x8F`).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] for a bad size, [`Error::Bus`] when the chip does not acknowledge the System Reset.
    pub fn new(
        i2c: I2C,
        cmd_addr: u8,
        delay: D,
        width: usize,
        height: usize,
        top_view: bool,
        contrast: u8,
    ) -> Result<Self, Error<I2C::Error>> {
        Ok(Self { inner: Uc1611sMinimal::with_options(i2c, cmd_addr, delay, width, height, top_view, contrast)? })
    }

    // ---- framebuffer ----------------------------------------------------------------------

    /// Return a framebuffer pixel; out-of-range coordinates read as `false`.
    pub fn get_pixel(&self, x: i32, y: i32) -> bool {
        let d = &self.inner;
        if x < 0 || y < 0 || x as usize >= d.width || y as usize >= d.height {
            return false;
        }
        d.fb[(y as usize >> 3) * d.width + x as usize] & (1 << (y & 7)) != 0
    }

    /// Set (or clear) the whole framebuffer.
    pub fn fill(&mut self, on: bool) {
        self.inner.fb.fill(if on { 0xFF } else { 0 });
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
            self.inner.set_pixel(x0, y0, on);
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
                    self.inner.set_pixel(xx, yy, on);
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
    /// `columns` holds `height_pages` groups of columns, page by page: byte `p * n + c` is column `c` of page
    /// row `p` (`n = len / height_pages`). The bitmap is clipped to the framebuffer.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidArgument`] if `y` is not a multiple of 8 or `height_pages` is 0.
    pub fn blit(&mut self, x: i32, y: i32, columns: &[u8], height_pages: u8) -> Result<(), Error<I2C::Error>> {
        if y % 8 != 0 || height_pages == 0 {
            return Err(Error::InvalidArgument);
        }
        let n = columns.len() / height_pages as usize;
        for p in 0..height_pages as i32 {
            let page = y / 8 + p;
            if page < 0 || page >= self.inner.pages as i32 {
                continue;
            }
            for c in 0..n {
                let xx = x + c as i32;
                if xx >= 0 && (xx as usize) < self.inner.width {
                    self.inner.fb[page as usize * self.inner.width + xx as usize] = columns[p as usize * n + c];
                }
            }
        }
        Ok(())
    }

    /// Write one framebuffer page to the display RAM (cheaper partial update). Out-of-range pages are ignored.
    pub fn show_page(&mut self, page: u8) -> Result<(), Error<I2C::Error>> {
        if page < self.inner.pages {
            self.inner.show_page_inner(page)?;
        }
        Ok(())
    }

    // ---- display control ------------------------------------------------------------------

    /// Set the potentiometer PM that adjusts VLCD (contrast), 0..=255.
    pub fn set_contrast(&mut self, value: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.contrast = value;
        self.inner.command(&[CMD_POTENTIOMETER, value])
    }

    /// Switch the display on, or into sleep (registers and RAM kept, below 1 uA).
    pub fn set_display(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_DISPLAY | on as u8])
    }

    /// Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active.
    pub fn sleep(&mut self) -> Result<(), Error<I2C::Error>> {
        self.set_display(false)
    }

    /// Leave sleep mode. The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump restarts,
    /// so the driver polls DE (at most 50 ms) instead of sleeping.
    pub fn wake(&mut self) -> Result<(), Error<I2C::Error>> {
        self.set_display(true)?;
        self.inner.wait_display_enabled(50);
        Ok(())
    }

    /// Invert the displayed image without changing RAM.
    pub fn set_invert(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_INVERSE | on as u8])
    }

    /// Force all pixels on (test mode, RAM unchanged).
    pub fn set_all_on(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_ALL_ON | on as u8])
    }

    /// Mirror columns (MX) and/or rows (MY). MY acts at once; MX is applied to RAM writes, so call
    /// [`Uc1611sMinimal::show`] afterwards to re-send the framebuffer.
    pub fn set_flip(&mut self, mirror_x: bool, mirror_y: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.mx = mirror_x as u8;
        self.inner.my = mirror_y as u8;
        let m = self.inner.mapping();
        self.inner.command(&[CMD_MAPPING, m])
    }

    /// Scroll the image up by `line` rows (hardware scroll, wraps at 160 rows); 0 ..= 159 - 2 * fixed lines.
    pub fn set_scroll_line(&mut self, line: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_SCROLL_LO | (line & 0x0F), CMD_SCROLL_HI | ((line >> 4) & 0x0F)])
    }

    /// Keep the first `2 * lines` rows out of the scroll (FL, 0..=15).
    pub fn set_fixed_lines(&mut self, lines: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_FIXED | (lines & 0x0F)])
    }

    /// Bias ratio: 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12.
    pub fn set_bias_ratio(&mut self, ratio: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_BIAS | (ratio & 3)])
    }

    /// Temperature compensation: 0 = -0.05, 1 = -0.10, 2 = -0.15, 3 = 0.00 %/degC.
    pub fn set_temperature_compensation(&mut self, curve: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_TEMP | (curve & 3)])
    }

    /// Line rate (On/Off mode): 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second.
    pub fn set_line_rate(&mut self, rate: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_LINE_RATE | (rate & 3)])
    }

    /// Internal 11x charge pump (`true`, reset) or an external VLCD supply (`false`).
    pub fn set_pump(&mut self, internal: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_PUMP | if internal { 3 } else { 0 }])
    }

    /// Panel capacitance: `true` = 33..55 nF (reset), `false` = up to 33 nF.
    pub fn set_panel_loading(&mut self, high: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_PANEL | if high { 3 } else { 0 }])
    }

    /// Scan only rows `start..=end` (the rest sleeps), or return to full scan. `end = None` selects `height - 1`.
    pub fn set_partial_display(&mut self, on: bool, start: u8, end: Option<u8>) -> Result<(), Error<I2C::Error>> {
        if !on {
            return self.inner.command(&[CMD_PARTIAL]);
        }
        let last = end.unwrap_or((self.inner.height - 1) as u8);
        self.inner.command(&[CMD_PART_START, start, CMD_PART_END, last, CMD_PARTIAL | 3])
    }

    /// N-line inversion (cross-talk tuning): `lines` 0 (off) ..= 63, optional XOR mode.
    pub fn set_nline_inversion(&mut self, lines: u8, xor: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_NLINE, if xor { 0x40 } else { 0 } | (lines & 0x3F)])
    }

    /// Isolation clock before (`front`) and after (`back`) the COM pulse, 0..=15 each.
    pub fn set_isolation_clock(&mut self, front: u8, back: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_ISOLATION, 0x13, front & 0x0F])?;
        self.inner.command(&[CMD_ISOLATION, 0x14, back & 0x0F])
    }

    /// Select which of the four RAM screens (patterns, 0..=3) is displayed.
    pub fn set_display_pattern(&mut self, pattern: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.command(&[CMD_PATTERN | ((pattern & 3) << 1) | 1])
    }

    /// Select the RAM screen (0..=3) that `show` / `show_page` write to (PA[6:5]).
    pub fn set_write_pattern(&mut self, pattern: u8) {
        self.inner.pattern = pattern & 3;
    }

    // ---- reads and raw access --------------------------------------------------------------

    /// Read the three status bytes (a NOP is sent first to restart the chip's status cycle).
    ///
    /// Returns Status1 (Ver, MX, MY, WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]) and Status3 (product code
    /// 1 in bits 7..4, ESD flag in bit 0).
    pub fn read_status(&mut self) -> Result<[u8; 3], Error<I2C::Error>> {
        self.inner.read_status_inner()
    }

    /// Read `buf.len()` (at most 255) bytes of display RAM; the dummy byte after the address change is discarded.
    pub fn read_ram(&mut self, page: u8, column: u8, buf: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        if buf.len() > 255 {
            return Err(Error::InvalidArgument);
        }
        let mut raw = [0u8; 256];
        let n = buf.len();
        let mut last = None;
        for _ in 0..MAX_ATTEMPTS {
            let addr = self.inner.address_bytes(page, column);
            let r = match self.inner.i2c.write(self.inner.cmd_addr, &addr) {
                Ok(()) => self.inner.i2c.read(self.inner.data_addr, &mut raw[..n + 1]),
                Err(e) => Err(e),
            };
            match r {
                Ok(()) => {
                    buf.copy_from_slice(&raw[1..n + 1]);
                    return Ok(());
                }
                Err(e) => {
                    last = Some(e);
                    self.inner.retries += 1;
                    self.inner.delay.delay_ms(RETRY_DELAY_MS);
                }
            }
        }
        self.inner.errors += 1;
        Err(Error::Bus(last.unwrap()))
    }

    /// System Reset and reconfigure with the cached contrast and mapping (RAM is kept).
    pub fn reset(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.reset_chip()?;
        self.inner.configure()?;
        self.inner.enable()?;
        self.inner.wait_display_enabled(50);
        Ok(())
    }

    /// Send raw bytes as one command transaction (retried on NACK).
    pub fn send_command(&mut self, bytes: &[u8]) -> Result<(), Error<I2C::Error>> {
        self.inner.command(bytes)
    }

    /// Send raw bytes to the display RAM (chunked) at the current RAM address. A failed chunk is not retried
    /// (the partial write cannot be continued); the failure is counted.
    pub fn send_data(&mut self, bytes: &[u8]) -> Result<(), Error<I2C::Error>> {
        match self.inner.write_chunks(bytes) {
            Ok(()) => Ok(()),
            Err(e) => {
                self.inner.errors += 1;
                Err(Error::Bus(e))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorKind, NoAcknowledgeSource};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as Tx};

    const C: u8 = 0x3C;
    const D: u8 = 0x3D;
    const DE: [u8; 3] = [0xDA, 0x80, 0x10];

    fn nack() -> ErrorKind {
        ErrorKind::NoAcknowledge(NoAcknowledgeSource::Data)
    }

    fn page_addr(page: u8) -> Vec<u8> {
        vec![0x60 | (page & 0x0F), 0x70 | ((page >> 4) & 0x07), 0x00, 0x10]
    }

    fn config(contrast: u8, mapping: u8, last: u8) -> Vec<u8> {
        vec![0x25, 0xC0, mapping, 0xA3, 0xF1, last, 0xF2, 0x00, 0xF3, last, 0x81, contrast, 0x89, 0xD1]
    }

    /// Data transactions of one page: 7 chunks of 32 and one of 16 bytes (width 240).
    fn page_data(data: &[u8]) -> Vec<Tx> {
        data.chunks(I2C_CHUNK).map(|c| Tx::write(D, c.to_vec())).collect()
    }

    /// Transactions of a complete initialisation (240 x 128, bottom view, DE already set).
    fn init_tx() -> Vec<Tx> {
        let mut v = vec![Tx::write(C, vec![0xE2]), Tx::write(C, config(0x8F, 0x02, 0x7F))];
        for p in 0..20 {
            v.push(Tx::write(C, page_addr(p)));
            v.extend(page_data(&[0u8; 240]));
        }
        v.push(Tx::write(C, vec![0xA9]));
        v.push(Tx::write(C, vec![0xE3]));
        v.push(Tx::read(C, DE.to_vec()));
        v
    }

    fn full(extra: Vec<Tx>) -> (Uc1611sFull<I2cMock, NoopDelay>, ()) {
        let mut tx = init_tx();
        tx.extend(extra);
        (Uc1611sFull::new(I2cMock::new(&tx), C, NoopDelay::new(), 240, 128, false, 0x8F).unwrap(), ())
    }

    fn finish(chip: Uc1611sFull<I2cMock, NoopDelay>) {
        let (mut i2c, _) = chip.inner.release();
        i2c.done();
    }

    #[test]
    fn init_sequence_and_geometry() {
        let tx = init_tx();
        let chip = Uc1611sMinimal::new(I2cMock::new(&tx), C, NoopDelay::new(), 240, 128).unwrap();
        assert_eq!((chip.width(), chip.height()), (240, 128));
        assert!(chip.ok() && chip.retry_count() == 0 && chip.error_count() == 0);
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn invalid_sizes_are_rejected_without_bus_traffic() {
        for (w, h) in [(241, 128), (0, 128), (240, 130), (240, 136), (240, 0)] {
            let mut i2c = I2cMock::new(&[]);
            let r = Uc1611sMinimal::new(i2c.clone(), C, NoopDelay::new(), w, h);
            assert!(matches!(r, Err(Error::InvalidArgument)), "{w}x{h}");
            i2c.done();
        }
    }

    #[test]
    fn pixels_and_show() {
        let mut fb_page1 = vec![0u8; 240];
        fb_page1[3] = 0x04; // pixel (3, 10)
        let mut fb_page15 = vec![0u8; 240];
        fb_page15[239] = 0x80; // pixel (239, 127)
        let mut extra = Vec::new();
        for p in 0..16u8 {
            extra.push(Tx::write(C, page_addr(p)));
            let page = match p {
                1 => fb_page1.clone(),
                15 => fb_page15.clone(),
                _ => vec![0u8; 240],
            };
            extra.extend(page_data(&page));
        }
        let mut tx = init_tx();
        tx.extend(extra);
        let mut chip = Uc1611sMinimal::new(I2cMock::new(&tx), C, NoopDelay::new(), 240, 128).unwrap();
        chip.set_pixel(3, 10, true);
        chip.set_pixel(239, 127, true);
        chip.set_pixel(240, 0, true); // out of range, ignored
        chip.set_pixel(-1, 5, true);
        chip.show().unwrap();
        let (mut i2c, _) = chip.release();
        i2c.done();
    }

    #[test]
    fn commands() {
        let extra = vec![
            Tx::write(C, vec![0x81, 0x55]),
            Tx::write(C, vec![0xA8]),
            Tx::write(C, vec![0xA7]),
            Tx::write(C, vec![0xA6]),
            Tx::write(C, vec![0xA5]),
            Tx::write(C, vec![0xA4]),
            Tx::write(C, vec![0xC0, 0x06]),
            Tx::write(C, vec![0xC0, 0x04]),
            Tx::write(C, vec![0x48, 0x52]),
            Tx::write(C, vec![0x93]),
            Tx::write(C, vec![0xEB]),
            Tx::write(C, vec![0x26]),
            Tx::write(C, vec![0xA1]),
            Tx::write(C, vec![0x2C]),
            Tx::write(C, vec![0x2F]),
            Tx::write(C, vec![0x2B]),
            Tx::write(C, vec![0x28]),
            Tx::write(C, vec![0xF2, 10, 0xF3, 100, 0x87]),
            Tx::write(C, vec![0xF2, 4, 0xF3, 127, 0x87]),
            Tx::write(C, vec![0x84]),
            Tx::write(C, vec![0xC8, 0x45]),
            Tx::write(C, vec![0x82, 0x13, 0x02]),
            Tx::write(C, vec![0x82, 0x14, 0x03]),
            Tx::write(C, vec![0xD5]),
            Tx::write(C, vec![0xD1]),
            Tx::write(C, vec![0xE3, 0xE3]),
        ];
        let (mut chip, _) = full(extra);
        chip.set_contrast(0x55).unwrap();
        chip.set_display(false).unwrap();
        chip.set_invert(true).unwrap();
        chip.set_invert(false).unwrap();
        chip.set_all_on(true).unwrap();
        chip.set_all_on(false).unwrap();
        chip.set_flip(true, true).unwrap();
        chip.set_flip(false, true).unwrap();
        chip.set_scroll_line(40).unwrap();
        chip.set_fixed_lines(3).unwrap();
        chip.set_bias_ratio(3).unwrap();
        chip.set_temperature_compensation(2).unwrap();
        chip.set_line_rate(1).unwrap();
        chip.set_pump(false).unwrap();
        chip.set_pump(true).unwrap();
        chip.set_panel_loading(true).unwrap();
        chip.set_panel_loading(false).unwrap();
        chip.set_partial_display(true, 10, Some(100)).unwrap();
        chip.set_partial_display(true, 4, None).unwrap();
        chip.set_partial_display(false, 0, None).unwrap();
        chip.set_nline_inversion(5, true).unwrap();
        chip.set_isolation_clock(2, 3).unwrap();
        chip.set_display_pattern(2).unwrap();
        chip.set_display_pattern(0).unwrap();
        chip.send_command(&[0xE3, 0xE3]).unwrap();
        finish(chip);
    }

    #[test]
    fn write_pattern_goes_into_the_page_address_and_wake_polls_de() {
        let mut extra = vec![Tx::write(C, vec![0x60, 0x72, 0x00, 0x10])];
        extra.extend(page_data(&[0u8; 240]));
        extra.extend([
            Tx::write(C, vec![0xA8]), // sleep
            Tx::write(C, vec![0xA9]), // wake
            Tx::write(C, vec![0xE3]),
            Tx::read(C, vec![0xD2, 0x80, 0x10]).with_error(nack()), // pump still starting
            Tx::write(C, vec![0xE3]),
            Tx::read(C, DE.to_vec()),
        ]);
        let (mut chip, _) = full(extra);
        chip.set_write_pattern(1);
        chip.show_page(0).unwrap();
        chip.show_page(16).unwrap(); // out of range: no traffic
        chip.sleep().unwrap();
        chip.wake().unwrap();
        assert!(chip.ok() && chip.retry_count() == 0); // NACKs while polling are not counted
        finish(chip);
    }

    #[test]
    fn framebuffer_drawing() {
        let (mut chip, _) = full(vec![]);
        chip.fill(true);
        assert!(chip.get_pixel(0, 0) && chip.get_pixel(239, 127));
        chip.fill(false);
        assert!(!chip.get_pixel(0, 0) && !chip.get_pixel(239, 127));
        assert!(!chip.get_pixel(-1, 0) && !chip.get_pixel(0, 128));
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
    fn reads_discard_the_dummy_byte_and_status_sends_nop_first() {
        let extra = vec![
            Tx::write(C, vec![0xE3]),
            Tx::read(C, vec![0xDA, 0x80, 0x10]),
            Tx::write(C, vec![0x62, 0x70, 0x05, 0x10]),
            Tx::read(D, vec![0xEE, 0x11, 0x22, 0x33]),
        ];
        let (mut chip, _) = full(extra);
        assert_eq!(chip.read_status().unwrap(), [0xDA, 0x80, 0x10]);
        let mut ram = [0u8; 3];
        chip.read_ram(2, 5, &mut ram).unwrap();
        assert_eq!(ram, [0x11, 0x22, 0x33]);
        finish(chip);
    }

    #[test]
    fn reset_reconfigures_with_cached_contrast_and_mapping() {
        let extra = vec![
            Tx::write(C, vec![0x81, 0x55]),
            Tx::write(C, vec![0xC0, 0x04]),
            Tx::write(C, vec![0xE2]),
            Tx::write(C, config(0x55, 0x04, 0x7F)),
            Tx::write(C, vec![0xA9]),
            Tx::write(C, vec![0xE3]),
            Tx::read(C, DE.to_vec()),
        ];
        let (mut chip, _) = full(extra);
        chip.set_contrast(0x55).unwrap();
        chip.set_flip(false, true).unwrap();
        chip.reset().unwrap();
        finish(chip);
    }

    #[test]
    fn top_view_and_custom_height() {
        let mut tx = vec![Tx::write(C, vec![0xE2]), Tx::write(C, config(0x60, 0x04, 0x3F))];
        for p in 0..20 {
            tx.push(Tx::write(C, page_addr(p)));
            tx.extend(page_data(&[0u8; 100]));
        }
        tx.push(Tx::write(C, vec![0xA9]));
        tx.push(Tx::write(C, vec![0xE3]));
        tx.push(Tx::read(C, DE.to_vec()));
        let chip = Uc1611sFull::new(I2cMock::new(&tx), C, NoopDelay::new(), 100, 64, true, 0x60).unwrap();
        finish(chip);
    }

    // ---- error handling ----------------------------------------------------------------

    #[test]
    fn command_is_retried_until_acknowledged() {
        let extra = vec![
            Tx::write(C, vec![0x81, 0x66]).with_error(nack()),
            Tx::write(C, vec![0x81, 0x66]).with_error(nack()),
            Tx::write(C, vec![0x81, 0x66]),
        ];
        let (mut chip, _) = full(extra);
        chip.set_contrast(0x66).unwrap();
        assert_eq!(chip.retry_count(), 2);
        assert!(chip.ok());
        finish(chip);
    }

    #[test]
    fn command_gives_up_after_max_attempts() {
        let extra: Vec<Tx> = (0..MAX_ATTEMPTS).map(|_| Tx::write(C, vec![0xA7]).with_error(nack())).collect();
        let (mut chip, _) = full(extra);
        assert!(matches!(chip.set_invert(true), Err(Error::Bus(_))));
        assert!(!chip.ok());
        assert_eq!((chip.error_count(), chip.retry_count()), (1, MAX_ATTEMPTS as u32));
        chip.clear_errors();
        assert!(chip.ok() && chip.retry_count() == 0);
        finish(chip);
    }

    #[test]
    fn a_failed_data_chunk_repeats_the_whole_page() {
        let mut extra = vec![Tx::write(C, page_addr(1)), Tx::write(D, vec![0u8; 32]).with_error(nack())];
        extra.push(Tx::write(C, page_addr(1)));
        extra.extend(page_data(&[0u8; 240]));
        // failure in the middle of a page (third chunk) restarts from the address command as well
        extra.push(Tx::write(C, page_addr(2)));
        extra.push(Tx::write(D, vec![0u8; 32]));
        extra.push(Tx::write(D, vec![0u8; 32]));
        extra.push(Tx::write(D, vec![0u8; 32]).with_error(nack()));
        extra.push(Tx::write(C, page_addr(2)));
        extra.extend(page_data(&[0u8; 240]));
        let (mut chip, _) = full(extra);
        chip.show_page(1).unwrap();
        assert_eq!(chip.retry_count(), 1);
        chip.show_page(2).unwrap();
        assert_eq!(chip.retry_count(), 2);
        assert!(chip.ok());
        finish(chip);
    }

    #[test]
    fn dead_chip_fails_at_init_without_further_traffic() {
        let tx: Vec<Tx> = (0..MAX_ATTEMPTS).map(|_| Tx::write(C, vec![0xE2]).with_error(nack())).collect();
        let mut i2c = I2cMock::new(&tx);
        let r = Uc1611sMinimal::new(i2c.clone(), C, NoopDelay::new(), 240, 128);
        assert!(matches!(r, Err(Error::Bus(_))));
        i2c.done();
    }

    #[test]
    fn status_and_ram_reads_are_retried() {
        let extra = vec![
            Tx::write(C, vec![0xE3]),
            Tx::read(C, vec![0, 0, 0]).with_error(nack()),
            Tx::write(C, vec![0xE3]),
            Tx::read(C, vec![0xDA, 0x80, 0x10]),
            Tx::write(C, vec![0x61, 0x70, 0x04, 0x10]),
            Tx::read(D, vec![0, 0, 0]).with_error(nack()),
            Tx::write(C, vec![0x61, 0x70, 0x04, 0x10]),
            Tx::read(D, vec![0xEE, 0x0A, 0x0B]),
        ];
        let (mut chip, _) = full(extra);
        assert_eq!(chip.read_status().unwrap(), [0xDA, 0x80, 0x10]);
        let mut ram = [0u8; 2];
        chip.read_ram(1, 4, &mut ram).unwrap();
        assert_eq!(ram, [0x0A, 0x0B]);
        assert_eq!(chip.retry_count(), 2);
        finish(chip);
    }
}
