//! SSD1331 — 96 x 64 RGB OLED controller (Waveshare 0.95inch RGB OLED).
//!
//! The driver keeps the picture in a caller-supplied RGB565 framebuffer (no
//! heap: [`BUFFER_SIZE`] = 12 288 bytes, high byte first) and sends it to the
//! display RAM on [`Ssd1331Minimal::show`].
//!
//! The driver is generic over [`DisplayBus`], implemented by
//! [`DisplayConnection`](crate::connection::display::DisplayConnection); the
//! SSD1331 has no BUSY pin (pass `None::<NoPin>`). The chip takes command
//! parameters with D/C# low, so every byte of a command is sent as its own
//! `send_command(byte, &[])`; only pixel data uses `send_data`.
//!
//! See `specs/display/ssd1331.md`.
//!
//! ## Constants
//!
//! Panel size: [`WIDTH`], [`HEIGHT`], [`BUFFER_SIZE`]. RGB565 colours:
//! [`BLACK`], [`WHITE`], [`RED`], [`GREEN`], [`BLUE`], [`YELLOW`], [`CYAN`],
//! [`MAGENTA`], and [`rgb`] to pack 8-bit components. Display modes:
//! [`DisplayMode`].

use crate::connection::display::DisplayBus;

pub const WIDTH: u16 = 96;
pub const HEIGHT: u16 = 64;
pub const BUFFER_SIZE: usize = WIDTH as usize * HEIGHT as usize * 2;

pub const BLACK: u16 = 0x0000;
pub const WHITE: u16 = 0xFFFF;
pub const RED: u16 = 0xF800;
pub const GREEN: u16 = 0x07E0;
pub const BLUE: u16 = 0x001F;
pub const YELLOW: u16 = 0xFFE0;
pub const CYAN: u16 = 0x07FF;
pub const MAGENTA: u16 = 0xF81F;

/// Pack 8-bit R, G, B into RGB565.
pub const fn rgb(r: u8, g: u8, b: u8) -> u16 {
    (((r & 0xF8) as u16) << 8) | (((g & 0xFC) as u16) << 3) | ((b >> 3) as u16)
}

/// Display mode for [`Ssd1331Full::set_display_mode`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DisplayMode {
    /// Show the display RAM (`0xA4`).
    Normal = 0xA4,
    /// All pixels on at full gray (`0xA5`).
    AllOn = 0xA5,
    /// All pixels off (`0xA6`).
    AllOff = 0xA6,
    /// Inverted display RAM (`0xA7`).
    Inverse = 0xA7,
}

const CMD_COLUMN: u8 = 0x15;
const CMD_ROW: u8 = 0x75;
const CMD_CONTRAST_A: u8 = 0x81;
const CMD_CONTRAST_B: u8 = 0x82;
const CMD_CONTRAST_C: u8 = 0x83;
const CMD_MASTER_CURRENT: u8 = 0x87;
const CMD_PRECHARGE_A: u8 = 0x8A;
const CMD_PRECHARGE_B: u8 = 0x8B;
const CMD_PRECHARGE_C: u8 = 0x8C;
const CMD_REMAP: u8 = 0xA0;
const CMD_START_LINE: u8 = 0xA1;
const CMD_OFFSET: u8 = 0xA2;
const CMD_MODE_NORMAL: u8 = 0xA4;
const CMD_MUX: u8 = 0xA8;
const CMD_DIM_SETTING: u8 = 0xAB;
const CMD_MASTER_CONFIG: u8 = 0xAD;
const CMD_DISPLAY_DIM: u8 = 0xAC;
const CMD_DISPLAY_OFF: u8 = 0xAE;
const CMD_DISPLAY_ON: u8 = 0xAF;
const CMD_POWER_SAVE: u8 = 0xB0;
const CMD_PHASE: u8 = 0xB1;
const CMD_CLOCK: u8 = 0xB3;
const CMD_GRAY_LINEAR: u8 = 0xB9;
const CMD_PRECHARGE_LEVEL: u8 = 0xBB;
const CMD_VCOMH: u8 = 0xBE;
const CMD_LINE: u8 = 0x21;
const CMD_RECT: u8 = 0x22;
const CMD_COPY: u8 = 0x23;
const CMD_DIM: u8 = 0x24;
const CMD_CLEAR: u8 = 0x25;
const CMD_FILL: u8 = 0x26;
const CMD_SCROLL_SETUP: u8 = 0x27;
const CMD_SCROLL_OFF: u8 = 0x2E;
const CMD_SCROLL_ON: u8 = 0x2F;

/// Error type of the SSD1331 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The display connection reported an error.
    Bus(E),
    /// The framebuffer is smaller than [`BUFFER_SIZE`].
    InvalidSize,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// RGB565 to the three 5/6/5-bit colour operands (C = red, B = green, A = blue).
fn color_ops(c: u16) -> [u8; 3] {
    [(((c >> 11) & 0x1F) << 1) as u8, ((c >> 5) & 0x3F) as u8, ((c & 0x1F) << 1) as u8]
}

/// SSD1331 96 x 64 RGB OLED controller — minimal interface.
///
/// See the [module documentation](self). `B` is the display connection
/// ([`DisplayBus`]).
pub struct Ssd1331Minimal<'a, B: DisplayBus> {
    bus: B,
    fb: &'a mut [u8],
    remap: u8,
}

impl<'a, B: DisplayBus> Ssd1331Minimal<'a, B> {
    /// Create the driver and run the vendor initialisation sequence.
    ///
    /// Hardware reset (100 ms settle), display off, contrast `FF FF FF`, master
    /// current 7/16, 65k colours with column remap and reversed COM scan, VCC
    /// external, power save off, 100 ms VCC wait, scroll off, display RAM
    /// cleared, display on. The framebuffer is cleared to black.
    ///
    /// # Arguments
    ///
    /// * `bus`    – Display connection (CS#, D/C#, RES#; no BUSY).
    /// * `buffer` – Framebuffer, at least [`BUFFER_SIZE`] bytes.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidSize`] for a too-small buffer, [`Error::Bus`] on a bus error.
    pub fn new(bus: B, buffer: &'a mut [u8]) -> Result<Self, Error<B::Error>> {
        if buffer.len() < BUFFER_SIZE {
            return Err(Error::InvalidSize);
        }
        let mut d = Self { bus, fb: buffer, remap: 0x72 };
        d.clear(BLACK);
        d.init_sequence()?;
        Ok(d)
    }

    /// Panel width in pixels (96).
    pub fn width(&self) -> u16 {
        WIDTH
    }

    /// Panel height in pixels (64).
    pub fn height(&self) -> u16 {
        HEIGHT
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.bus
    }

    /// Fill the framebuffer with one RGB565 colour. The display is unchanged
    /// until [`show`](Self::show).
    pub fn clear(&mut self, color: u16) {
        let (hi, lo) = ((color >> 8) as u8, color as u8);
        for px in self.fb[..BUFFER_SIZE].chunks_exact_mut(2) {
            px[0] = hi;
            px[1] = lo;
        }
    }

    /// Set one framebuffer pixel. Out-of-range coordinates are ignored.
    pub fn set_pixel(&mut self, x: i32, y: i32, color: u16) {
        if x < 0 || y < 0 || x >= WIDTH as i32 || y >= HEIGHT as i32 {
            return;
        }
        let i = (y as usize * WIDTH as usize + x as usize) * 2;
        self.fb[i] = (color >> 8) as u8;
        self.fb[i + 1] = color as u8;
    }

    /// Send the whole framebuffer to the display RAM: window 0..95 x 0..63 and
    /// 12 288 bytes in 192-byte (one row) transfers.
    pub fn show(&mut self) -> Result<(), Error<B::Error>> {
        self.window(0, 0, WIDTH as u8 - 1, HEIGHT as u8 - 1)?;
        for row in self.fb[..BUFFER_SIZE].chunks(2 * WIDTH as usize) {
            self.bus.send_data(row)?;
        }
        Ok(())
    }

    /// Switch the display on (`0xAF`); the RAM content is shown.
    pub fn display_on(&mut self) -> Result<(), Error<B::Error>> {
        self.cmd(&[CMD_DISPLAY_ON])
    }

    /// Switch the display off (`0xAE`); the RAM content is kept.
    pub fn display_off(&mut self) -> Result<(), Error<B::Error>> {
        self.cmd(&[CMD_DISPLAY_OFF])
    }

    /// Send every byte as its own command (all with D/C# low).
    fn cmd(&mut self, bytes: &[u8]) -> Result<(), Error<B::Error>> {
        for b in bytes {
            self.bus.send_command(*b, &[])?;
        }
        Ok(())
    }

    fn window(&mut self, x0: u8, y0: u8, x1: u8, y1: u8) -> Result<(), Error<B::Error>> {
        self.cmd(&[CMD_COLUMN, x0, x1, CMD_ROW, y0, y1])
    }

    fn init_sequence(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.reset()?;
        self.bus.delay_ms(100)?;
        self.cmd(&[CMD_DISPLAY_OFF])?;
        self.cmd(&[CMD_CONTRAST_A, 0xFF, CMD_CONTRAST_B, 0xFF, CMD_CONTRAST_C, 0xFF])?;
        self.cmd(&[CMD_MASTER_CURRENT, 0x06])?;
        self.cmd(&[CMD_PRECHARGE_A, 0x64, CMD_PRECHARGE_B, 0x78, CMD_PRECHARGE_C, 0x64])?;
        let remap = self.remap;
        self.cmd(&[CMD_REMAP, remap])?;
        self.cmd(&[CMD_START_LINE, 0x00, CMD_OFFSET, 0x00])?;
        self.cmd(&[CMD_MODE_NORMAL])?;
        self.cmd(&[CMD_MUX, 0x3F, CMD_MASTER_CONFIG, 0x8E])?;
        self.cmd(&[CMD_POWER_SAVE, 0x0B, CMD_PHASE, 0x31, CMD_CLOCK, 0xF0])?;
        self.cmd(&[CMD_PRECHARGE_LEVEL, 0x3A, CMD_VCOMH, 0x3E])?;
        self.cmd(&[CMD_SCROLL_OFF])?;
        self.cmd(&[CMD_CLEAR, 0x00, 0x00, 0x5F, 0x3F])?;
        self.bus.delay_ms(100)?;
        self.cmd(&[CMD_DISPLAY_ON])
    }
}

/// SSD1331 96 x 64 RGB OLED controller — full interface.
///
/// Extends [`Ssd1331Minimal`] (reachable through `Deref`) with software
/// drawing into the framebuffer, partial transfers, the controller's graphic
/// acceleration commands (line, rectangle with fill, copy, dim and clear
/// window, scrolling — these act on the display RAM only, not on the host
/// framebuffer), and the electrical settings.
pub struct Ssd1331Full<'a, B: DisplayBus> {
    inner: Ssd1331Minimal<'a, B>,
}

impl<'a, B: DisplayBus> core::ops::Deref for Ssd1331Full<'a, B> {
    type Target = Ssd1331Minimal<'a, B>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<'a, B: DisplayBus> core::ops::DerefMut for Ssd1331Full<'a, B> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl<'a, B: DisplayBus> Ssd1331Full<'a, B> {
    /// Create the driver; see [`Ssd1331Minimal::new`].
    pub fn new(bus: B, buffer: &'a mut [u8]) -> Result<Self, Error<B::Error>> {
        Ok(Self { inner: Ssd1331Minimal::new(bus, buffer)? })
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.inner.bus
    }

    // ---- framebuffer ---------------------------------------------------------------------

    /// RGB565 colour of one framebuffer pixel (0 when out of range).
    pub fn get_pixel(&self, x: i32, y: i32) -> u16 {
        if x < 0 || y < 0 || x >= WIDTH as i32 || y >= HEIGHT as i32 {
            return 0;
        }
        let i = (y as usize * WIDTH as usize + x as usize) * 2;
        ((self.inner.fb[i] as u16) << 8) | self.inner.fb[i + 1] as u16
    }

    /// Bresenham line into the framebuffer.
    pub fn draw_line(&mut self, mut x0: i32, mut y0: i32, x1: i32, y1: i32, color: u16) {
        let dx = (x1 - x0).abs();
        let dy = -(y1 - y0).abs();
        let sx = if x0 < x1 { 1 } else { -1 };
        let sy = if y0 < y1 { 1 } else { -1 };
        let mut err = dx + dy;
        loop {
            self.set_pixel(x0, y0, color);
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

    /// Rectangle outline or filled rectangle into the framebuffer.
    pub fn draw_rect(&mut self, x: i32, y: i32, w: i32, h: i32, color: u16, fill: bool) {
        if w <= 0 || h <= 0 {
            return;
        }
        for j in 0..h {
            for i in 0..w {
                if fill || i == 0 || j == 0 || i == w - 1 || j == h - 1 {
                    self.set_pixel(x + i, y + j, color);
                }
            }
        }
    }

    /// Copy a row-major `w` x `h` RGB565 bitmap into the framebuffer.
    pub fn blit(&mut self, x: i32, y: i32, pixels: &[u16], w: i32, h: i32) {
        for j in 0..h {
            for i in 0..w {
                if let Some(&c) = pixels.get((j * w + i) as usize) {
                    self.set_pixel(x + i, y + j, c);
                }
            }
        }
    }

    /// Send only a rectangle of the framebuffer (one window, row by row). The
    /// region is clipped to the panel; an empty region sends nothing.
    pub fn show_region(&mut self, x: i32, y: i32, w: i32, h: i32) -> Result<(), Error<B::Error>> {
        let (mut x, mut y, mut w, mut h) = (x, y, w, h);
        if x < 0 {
            w += x;
            x = 0;
        }
        if y < 0 {
            h += y;
            y = 0;
        }
        w = w.min(WIDTH as i32 - x);
        h = h.min(HEIGHT as i32 - y);
        if w <= 0 || h <= 0 {
            return Ok(());
        }
        self.inner.window(x as u8, y as u8, (x + w - 1) as u8, (y + h - 1) as u8)?;
        for j in 0..h {
            let start = ((y + j) as usize * WIDTH as usize + x as usize) * 2;
            let row = &self.inner.fb[start..start + w as usize * 2];
            self.inner.bus.send_data(row)?;
        }
        Ok(())
    }

    // ---- graphic acceleration (display RAM only) ----------------------------------------

    /// Hardware line (`0x21`) on the display RAM; coordinates 0..95 / 0..63.
    pub fn hw_line(&mut self, x0: u8, y0: u8, x1: u8, y1: u8, color: u16) -> Result<(), Error<B::Error>> {
        let c = color_ops(color);
        self.inner.cmd(&[CMD_LINE, x0, y0, x1, y1, c[0], c[1], c[2]])
    }

    /// Hardware rectangle (`0x26` fill enable + `0x22`) on the display RAM.
    ///
    /// `w` and `h` must be at least 1 (otherwise nothing is sent).
    pub fn hw_rect(
        &mut self,
        x: u8,
        y: u8,
        w: u8,
        h: u8,
        line_color: u16,
        fill_color: u16,
        fill: bool,
    ) -> Result<(), Error<B::Error>> {
        if w == 0 || h == 0 {
            return Ok(());
        }
        self.inner.cmd(&[CMD_FILL, fill as u8])?;
        let (l, f) = (color_ops(line_color), color_ops(fill_color));
        self.inner.cmd(&[
            CMD_RECT,
            x,
            y,
            x.wrapping_add(w - 1),
            y.wrapping_add(h - 1),
            l[0],
            l[1],
            l[2],
            f[0],
            f[1],
            f[2],
        ])
    }

    /// Clear a window of the display RAM (`0x25`).
    pub fn hw_clear(&mut self, x0: u8, y0: u8, x1: u8, y1: u8) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_CLEAR, x0, y0, x1, y1])
    }

    /// Dim a window of the display RAM (`0x24`).
    pub fn hw_dim(&mut self, x0: u8, y0: u8, x1: u8, y1: u8) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_DIM, x0, y0, x1, y1])
    }

    /// Copy a window of the display RAM to (`x`, `y`) (`0x23`).
    pub fn hw_copy(&mut self, x0: u8, y0: u8, x1: u8, y1: u8, x: u8, y: u8) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_COPY, x0, y0, x1, y1, x, y])
    }

    /// Configure and start continuous scrolling (`0x27`, `0x2F`).
    ///
    /// `horizontal` columns per step 0..95, `start_row`, `rows`, `vertical`
    /// rows per step 0..63, `interval` 0 = 6, 1 = 10, 2 = 100, 3 = 200 frames
    /// per step.
    pub fn scroll(
        &mut self,
        horizontal: u8,
        start_row: u8,
        rows: u8,
        vertical: u8,
        interval: u8,
    ) -> Result<(), Error<B::Error>> {
        self.inner
            .cmd(&[CMD_SCROLL_SETUP, horizontal, start_row, rows, vertical, interval & 3, CMD_SCROLL_ON])
    }

    /// Stop scrolling (`0x2E`). The display RAM must be rewritten afterwards.
    pub fn scroll_stop(&mut self) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_SCROLL_OFF])
    }

    // ---- settings ------------------------------------------------------------------------

    /// Contrast of colour channels A, B, C (`0x81..0x83`), 0..255.
    pub fn set_contrast(&mut self, a: u8, b: u8, c: u8) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_CONTRAST_A, a, CMD_CONTRAST_B, b, CMD_CONTRAST_C, c])
    }

    /// Master current (`0x87`), `n`/16 for `n` = 1..=16 (clamped).
    pub fn set_master_current(&mut self, n: u8) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_MASTER_CURRENT, n.clamp(1, 16) - 1])
    }

    /// Display mode (`0xA4..0xA7`).
    pub fn set_display_mode(&mut self, mode: DisplayMode) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[mode as u8])
    }

    /// Raw remap and colour depth byte (`0xA0`).
    pub fn set_remap(&mut self, flags: u8) -> Result<(), Error<B::Error>> {
        self.inner.remap = flags;
        self.inner.cmd(&[CMD_REMAP, flags])
    }

    /// Mirror horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4).
    pub fn set_flip(&mut self, horizontal: bool, vertical: bool) -> Result<(), Error<B::Error>> {
        let mut r = self.inner.remap & !0x12;
        if horizontal {
            r |= 0x02;
        }
        if vertical {
            r |= 0x10;
        }
        self.set_remap(r)
    }

    /// Power save mode (`0xB0`): `true` = `0x1A` enable, `false` = `0x0B` disable.
    pub fn set_power_save(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_POWER_SAVE, if on { 0x1A } else { 0x0B }])
    }

    /// Dim mode (`0xAB` + `0xAC`): contrasts A/B/C and pre-charge voltage 0..31.
    pub fn dim_mode(&mut self, a: u8, b: u8, c: u8, precharge: u8) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_DIM_SETTING, 0x00, a, b, c, precharge & 0x1F, CMD_DISPLAY_DIM])
    }

    /// Restore the linear gray scale table (`0xB9`).
    pub fn gray_linear(&mut self) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[CMD_GRAY_LINEAR])
    }

    /// Send any command with parameters (all with D/C# low).
    pub fn send_command(&mut self, command: u8, params: &[u8]) -> Result<(), Error<B::Error>> {
        self.inner.cmd(&[command])?;
        self.inner.cmd(params)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::connection::display::DisplayError;

    #[derive(Debug, PartialEq)]
    enum Ev {
        Reset,
        Delay(u32),
        Cmd(u8, Vec<u8>),
        Data(Vec<u8>),
    }

    struct MockBus {
        events: Vec<Ev>,
    }

    impl MockBus {
        fn new() -> Self {
            Self { events: Vec::new() }
        }
    }

    impl DisplayBus for MockBus {
        type Error = DisplayError<(), (), (), ()>;
        fn reset(&mut self) -> Result<(), Self::Error> {
            self.events.push(Ev::Reset);
            Ok(())
        }
        fn delay_ms(&mut self, ms: u32) -> Result<(), Self::Error> {
            self.events.push(Ev::Delay(ms));
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

    /// Command/parameter bytes (asserting none carries parameters) between `from` and the end.
    fn bytes(bus: &MockBus, from: usize) -> Vec<u8> {
        bus.events[from..]
            .iter()
            .filter_map(|e| match e {
                Ev::Cmd(c, d) => {
                    assert!(d.is_empty(), "parameters must be separate commands (D/C# low)");
                    Some(*c)
                }
                _ => None,
            })
            .collect()
    }

    fn data_bytes(bus: &MockBus, from: usize) -> usize {
        bus.events[from..]
            .iter()
            .map(|e| if let Ev::Data(d) = e { d.len() } else { 0 })
            .sum()
    }

    fn full(buf: &mut [u8]) -> Ssd1331Full<'_, MockBus> {
        Ssd1331Full::new(MockBus::new(), buf).unwrap()
    }

    #[test]
    fn init_sequence_vendor() {
        let mut buf = [0xAAu8; BUFFER_SIZE];
        let d = Ssd1331Minimal::new(MockBus::new(), &mut buf).unwrap();
        let bus = d.release();
        assert_eq!(bus.events[0], Ev::Reset);
        assert_eq!(bus.events[1], Ev::Delay(100));
        assert_eq!(
            bytes(&bus, 0),
            vec![
                0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06, 0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64,
                0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4, 0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31,
                0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E, 0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, 0xAF
            ]
        );
        assert_eq!(bus.events[bus.events.len() - 2], Ev::Delay(100));
        assert_eq!(data_bytes(&bus, 0), 0);
    }

    #[test]
    fn init_clears_framebuffer_and_checks_size() {
        let mut buf = [0xAAu8; BUFFER_SIZE];
        let d = Ssd1331Minimal::new(MockBus::new(), &mut buf).unwrap();
        drop(d);
        assert!(buf.iter().all(|&b| b == 0));
        let mut small = [0u8; 100];
        assert!(matches!(Ssd1331Minimal::new(MockBus::new(), &mut small), Err(Error::InvalidSize)));
    }

    #[test]
    fn framebuffer_mapping() {
        let mut buf = [0u8; BUFFER_SIZE];
        let mut d = full(&mut buf);
        assert_eq!((d.width(), d.height()), (96, 64));
        d.set_pixel(0, 0, RED);
        d.set_pixel(95, 63, BLUE);
        d.set_pixel(1, 2, 0x1234);
        assert_eq!(d.get_pixel(0, 0), RED);
        assert_eq!(d.get_pixel(1, 2), 0x1234);
        d.set_pixel(-1, 0, WHITE);
        d.set_pixel(0, -1, WHITE);
        d.set_pixel(96, 0, WHITE);
        d.set_pixel(0, 64, WHITE);
        assert_eq!(d.get_pixel(99, 99), 0);
        drop(d);
        assert_eq!(&buf[..2], &[0xF8, 0x00]);
        assert_eq!(&buf[12286..12288], &[0x00, 0x1F]);
        assert_eq!(&buf[(2 * 96 + 1) * 2..(2 * 96 + 1) * 2 + 2], &[0x12, 0x34]);
    }

    #[test]
    fn rgb_packing_and_clear() {
        assert_eq!(rgb(255, 0, 0), 0xF800);
        assert_eq!(rgb(0, 255, 0), 0x07E0);
        assert_eq!(rgb(0, 0, 255), 0x001F);
        assert_eq!(rgb(255, 255, 255), 0xFFFF);
        let mut buf = [0u8; BUFFER_SIZE];
        let mut d = full(&mut buf);
        d.clear(GREEN);
        assert_eq!(d.get_pixel(0, 0), GREEN);
        assert_eq!(d.get_pixel(95, 63), GREEN);
    }

    #[test]
    fn drawing_helpers() {
        let mut buf = [0u8; BUFFER_SIZE];
        let mut d = full(&mut buf);
        d.draw_line(0, 0, 4, 4, WHITE);
        assert!(d.get_pixel(0, 0) == WHITE && d.get_pixel(2, 2) == WHITE && d.get_pixel(4, 4) == WHITE);
        assert_eq!(d.get_pixel(4, 0), 0);
        d.clear(BLACK);
        d.draw_rect(2, 2, 4, 3, WHITE, false);
        assert!(d.get_pixel(2, 2) == WHITE && d.get_pixel(5, 4) == WHITE);
        assert!(d.get_pixel(3, 3) == 0 && d.get_pixel(6, 2) == 0);
        d.draw_rect(10, 10, 3, 3, RED, true);
        assert!(d.get_pixel(11, 11) == RED && d.get_pixel(13, 13) == 0);
        d.blit(20, 20, &[1, 2, 3, 4], 2, 2);
        assert_eq!([d.get_pixel(20, 20), d.get_pixel(21, 20), d.get_pixel(20, 21), d.get_pixel(21, 21)], [1, 2, 3, 4]);
    }

    #[test]
    fn show_and_show_region() {
        let mut buf = [0u8; BUFFER_SIZE];
        let mut d = full(&mut buf);
        d.clear(RED);
        let mut bus_events_before = d.inner.bus.events.len();
        d.show().unwrap();
        assert_eq!(bytes(&d.inner.bus, bus_events_before), vec![0x15, 0, 0x5F, 0x75, 0, 0x3F]);
        assert_eq!(data_bytes(&d.inner.bus, bus_events_before), 12288);
        assert!(matches!(&d.inner.bus.events[bus_events_before + 6], Ev::Data(v) if v.len() == 192 && v[0] == 0xF8));
        bus_events_before = d.inner.bus.events.len();
        d.show_region(10, 20, 4, 2).unwrap();
        assert_eq!(bytes(&d.inner.bus, bus_events_before), vec![0x15, 10, 13, 0x75, 20, 21]);
        assert_eq!(data_bytes(&d.inner.bus, bus_events_before), 16);
        bus_events_before = d.inner.bus.events.len();
        d.show_region(90, 60, 20, 20).unwrap();
        assert_eq!(bytes(&d.inner.bus, bus_events_before), vec![0x15, 90, 95, 0x75, 60, 63]);
        assert_eq!(data_bytes(&d.inner.bus, bus_events_before), 48);
        bus_events_before = d.inner.bus.events.len();
        d.show_region(200, 0, 5, 5).unwrap();
        assert_eq!(d.inner.bus.events.len(), bus_events_before);
    }

    #[test]
    fn hardware_primitives() {
        let mut buf = [0u8; BUFFER_SIZE];
        let mut d = full(&mut buf);
        let mut n = d.inner.bus.events.len();
        d.hw_line(1, 2, 3, 4, RED).unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0x21, 1, 2, 3, 4, 0x3E, 0, 0]);
        n = d.inner.bus.events.len();
        d.hw_rect(10, 20, 5, 6, GREEN, BLUE, true).unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E]);
        n = d.inner.bus.events.len();
        d.hw_rect(10, 20, 5, 6, WHITE, 0, false).unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0x26, 0, 0x22, 10, 20, 14, 25, 0x3E, 0x3F, 0x3E, 0, 0, 0]);
        n = d.inner.bus.events.len();
        d.hw_rect(10, 20, 0, 6, WHITE, 0, false).unwrap();
        assert_eq!(d.inner.bus.events.len(), n);
        n = d.inner.bus.events.len();
        d.hw_clear(0, 1, 2, 3).unwrap();
        d.hw_dim(0, 1, 2, 3).unwrap();
        d.hw_copy(1, 2, 3, 4, 5, 6).unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0x25, 0, 1, 2, 3, 0x24, 0, 1, 2, 3, 0x23, 1, 2, 3, 4, 5, 6]);
        n = d.inner.bus.events.len();
        d.scroll(1, 0, 64, 2, 7).unwrap();
        d.scroll_stop().unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0x27, 1, 0, 64, 2, 3, 0x2F, 0x2E]);
    }

    #[test]
    fn settings() {
        let mut buf = [0u8; BUFFER_SIZE];
        let mut d = full(&mut buf);
        let mut n = d.inner.bus.events.len();
        d.set_contrast(1, 2, 3).unwrap();
        d.set_master_current(16).unwrap();
        d.set_master_current(0).unwrap();
        d.set_display_mode(DisplayMode::Inverse).unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0x81, 1, 0x82, 2, 0x83, 3, 0x87, 0x0F, 0x87, 0x00, 0xA7]);
        n = d.inner.bus.events.len();
        d.set_flip(false, false).unwrap();
        d.set_flip(true, true).unwrap();
        assert_eq!(bytes(&d.inner.bus, n), vec![0xA0, 0x60, 0xA0, 0x72]);
        n = d.inner.bus.events.len();
        d.set_power_save(true).unwrap();
        d.set_power_save(false).unwrap();
        d.dim_mode(1, 2, 3, 0xFF).unwrap();
        d.display_off().unwrap();
        d.display_on().unwrap();
        d.gray_linear().unwrap();
        d.send_command(0xAA, &[0x12, 0x34]).unwrap();
        assert_eq!(
            bytes(&d.inner.bus, n),
            vec![0xB0, 0x1A, 0xB0, 0x0B, 0xAB, 0, 1, 2, 3, 0x1F, 0xAC, 0xAE, 0xAF, 0xB9, 0xAA, 0x12, 0x34]
        );
    }
}
