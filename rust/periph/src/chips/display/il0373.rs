//! IL0373 — black/white e-paper controller (104 x 212 panels such as the
//! Waveshare 2.13inch e-Paper (D)).
//!
//! The driver keeps the picture in a caller-supplied framebuffer (no heap:
//! `ceil(width / 8) * height` bytes, 2756 bytes at 104 x 212) and sends it on
//! [`Il0373Minimal::show`]. Every update follows the vendor reference flow:
//! `PON` (if the booster is off), `DTM1` + `DTM2`, `DRF` and wait on BUSY
//! (active low), `CDI` border floating, `POF` and wait; `show` blocks for the
//! whole refresh.
//!
//! The driver is generic over [`DisplayBus`], implemented by
//! [`DisplayConnection`](crate::connection::display::DisplayConnection), which
//! owns CS#, D/C#, RES# and BUSY (see `specs/transport_display.md`).
//!
//! Pixel polarity: bit 1 = white, bit 0 = black (`CDI.DDX[0] = 1`, KW mode). The
//! controller drives a pixel only when its NEW and OLD bits differ, so the
//! driver sends OLD = NOT NEW: every pixel is driven to its final state and a
//! refresh does not depend on the previous picture.
//!
//! See `specs/display/il0373.md`. The `read_*` methods of [`Il0373Full`] need a
//! half-duplex wire mode.
//!
//! ## Constants
//!
//! Colours: [`BLACK`], [`WHITE`]. Panel size: [`MAX_WIDTH`], [`MAX_HEIGHT`],
//! [`DEFAULT_WIDTH`], [`DEFAULT_HEIGHT`].

use crate::connection::display::DisplayBus;

pub const BLACK: u8 = 0;
pub const WHITE: u8 = 1;

pub const MAX_WIDTH: u16 = 160;
pub const MAX_HEIGHT: u16 = 296;
pub const DEFAULT_WIDTH: u16 = 104;
pub const DEFAULT_HEIGHT: u16 = 212;

const CMD_PSR: u8 = 0x00;
const CMD_PWR: u8 = 0x01;
const CMD_POF: u8 = 0x02;
const CMD_PON: u8 = 0x04;
const CMD_BTST: u8 = 0x06;
const CMD_DSLP: u8 = 0x07;
const CMD_DTM1: u8 = 0x10;
const CMD_DRF: u8 = 0x12;
const CMD_DTM2: u8 = 0x13;
const CMD_LUTC: u8 = 0x20;
const CMD_PLL: u8 = 0x30;
const CMD_TSC: u8 = 0x40;
const CMD_TSE: u8 = 0x41;
const CMD_CDI: u8 = 0x50;
const CMD_LPD: u8 = 0x51;
const CMD_TRES: u8 = 0x61;
const CMD_REV: u8 = 0x70;
const CMD_FLG: u8 = 0x71;
const CMD_VV: u8 = 0x81;
const CMD_VDCS: u8 = 0x82;
const CMD_PTL: u8 = 0x90;
const CMD_PTIN: u8 = 0x91;
const CMD_PTOUT: u8 = 0x92;
const CMD_ROTP: u8 = 0xA2;

/// Frame rate in Hz for PLL M (1..7) and N (1..7).
const PLL_HZ: [[u8; 7]; 7] = [
    [29, 14, 10, 7, 6, 5, 4],
    [57, 29, 19, 14, 11, 10, 8],
    [86, 43, 29, 21, 17, 14, 12],
    [114, 57, 38, 29, 23, 19, 16],
    [150, 72, 48, 36, 29, 24, 20],
    [171, 86, 57, 43, 34, 29, 24],
    [200, 100, 67, 50, 40, 33, 29],
];

/// Error type of the IL0373 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The display connection reported an error.
    Bus(E),
    /// BUSY did not go high within the configured timeout.
    BusyTimeout,
    /// Width/height out of range (width a multiple of 8, 8..=160; height 1..=296),
    /// or the framebuffer is smaller than `buffer_size(width, height)`.
    InvalidSize,
    /// The requested window is empty or lies outside the panel.
    InvalidWindow,
    /// LUT table index not in 0..=4.
    InvalidTable,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn code(value: f32, base: f32, step: f32, max_code: u8) -> u8 {
    let c = ((value - base) / step + 0.5) as i32;
    c.clamp(0, max_code as i32) as u8
}

/// Send `src` inverted (`!byte`) in 32-byte blocks.
fn send_inverted<B: DisplayBus>(bus: &mut B, src: &[u8]) -> Result<(), B::Error> {
    let mut tmp = [0u8; 32];
    for chunk in src.chunks(tmp.len()) {
        for (t, s) in tmp.iter_mut().zip(chunk) {
            *t = !*s;
        }
        bus.send_data(&tmp[..chunk.len()])?;
    }
    Ok(())
}

/// IL0373 black/white e-paper controller — minimal interface.
///
/// See the [module documentation](self) for the update sequence and buffer
/// ownership. `B` is the display connection ([`DisplayBus`]).
pub struct Il0373Minimal<'a, B: DisplayBus> {
    bus: B,
    fb: &'a mut [u8],
    width: u16,
    height: u16,
    timeout_ms: Option<u32>,
    psr: u8,
    powered: bool,
    asleep: bool,
}

impl<'a, B: DisplayBus> Il0373Minimal<'a, B> {
    /// Bytes the framebuffer needs for a panel of `width` x `height`.
    pub const fn buffer_size(width: u16, height: u16) -> usize {
        ((width as usize + 7) / 8) * height as usize
    }

    /// Create the driver and run the initialisation sequence.
    ///
    /// Hardware reset, wait for BUSY, `PWR` `03 00 2B 2B 03`, `BTST` `17 17 17`, `PON`, settle (200 ms), `PSR`
    /// `0x1F` (KW, LUT from OTP, scan up, shift right, booster on), `TRES` with
    /// the panel size and `CDI` `0x97`. The picture on the panel is left
    /// unchanged until [`show`](Self::show). The framebuffer is cleared to white.
    ///
    /// # Arguments
    ///
    /// * `bus`    – Display connection (CS#, D/C#, RES#, BUSY).
    /// * `buffer` – Framebuffer, at least `buffer_size(width, height)` bytes.
    /// * `width`  – Pixels per row, a multiple of 8, 8..=160 ([`DEFAULT_WIDTH`]).
    /// * `height` – Rows, 1..=296 ([`DEFAULT_HEIGHT`]).
    /// * `busy_timeout_ms` – Limit for every BUSY wait in milliseconds, `None` for
    ///   no limit (the panel datasheet gives no refresh time; 30 000 is a safe default).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidSize`] for a bad size or a too-small buffer,
    /// [`Error::BusyTimeout`] when BUSY never goes high.
    pub fn new(
        bus: B,
        buffer: &'a mut [u8],
        width: u16,
        height: u16,
        busy_timeout_ms: Option<u32>,
    ) -> Result<Self, Error<B::Error>> {
        if width < 8 || width > MAX_WIDTH || width % 8 != 0 || height < 1 || height > MAX_HEIGHT {
            return Err(Error::InvalidSize);
        }
        if buffer.len() < Self::buffer_size(width, height) {
            return Err(Error::InvalidSize);
        }
        let mut d = Self {
            bus,
            fb: buffer,
            width,
            height,
            timeout_ms: busy_timeout_ms,
            psr: 0x1F,
            powered: false,
            asleep: false,
        };
        d.clear(WHITE);
        d.init_sequence()?;
        Ok(d)
    }

    /// Panel width in pixels.
    pub fn width(&self) -> u16 {
        self.width
    }

    /// Panel height in pixels.
    pub fn height(&self) -> u16 {
        self.height
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.bus
    }

    fn size(&self) -> usize {
        Self::buffer_size(self.width, self.height)
    }

    /// Fill the framebuffer with one colour ([`WHITE`] or [`BLACK`]). The panel
    /// is unchanged until `show`.
    pub fn clear(&mut self, color: u8) {
        let size = self.size();
        let v = if color == WHITE { 0xFF } else { 0x00 };
        self.fb[..size].iter_mut().for_each(|b| *b = v);
    }

    /// Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
    ///
    /// `color` is [`BLACK`] or [`WHITE`].
    pub fn set_pixel(&mut self, x: i32, y: i32, color: u8) {
        if x < 0 || y < 0 || x >= self.width as i32 || y >= self.height as i32 {
            return;
        }
        let index = y as usize * (self.width as usize / 8) + (x as usize >> 3);
        let mask = 0x80u8 >> (x & 7);
        if color == WHITE {
            self.fb[index] |= mask;
        } else {
            self.fb[index] &= !mask;
        }
    }

    /// Write OLD and NEW data to the controller and run a full display update.
    ///
    /// `PON` (if the booster is off), `DTM1` (OLD = NOT NEW), `DTM2` (NEW),
    /// `DRF`, wait BUSY, `CDI` `0xF7` (border floating), `POF`, wait BUSY.
    /// Blocks for the whole refresh. A sleeping controller is woken (hardware
    /// reset + initialisation) first.
    pub fn show(&mut self) -> Result<(), Error<B::Error>> {
        if self.asleep {
            self.wake_internal()?;
        }
        self.ensure_power()?;
        self.write_planes()?;
        self.refresh_power_off()
    }

    /// Put the controller into deep sleep (border floating, `POF`, `DSLP` with
    /// check code `0xA5`).
    ///
    /// Only a hardware reset restores communication; `show` and
    /// [`Il0373Full::wake`] do that. The panel keeps its image.
    pub fn sleep(&mut self) -> Result<(), Error<B::Error>> {
        if self.powered {
            self.power_off()?;
        }
        self.bus.send_command(CMD_DSLP, &[0xA5])?;
        self.asleep = true;
        Ok(())
    }

    fn wait_ready(&mut self) -> Result<(), Error<B::Error>> {
        if self.bus.wait_busy(false, self.timeout_ms)? {
            Ok(())
        } else {
            Err(Error::BusyTimeout)
        }
    }

    /// Let `PON` settle: wait up to 200 ms for BUSY high, ignoring a timeout. On the
    /// Waveshare 2.13inch (D) module BUSY shows no clean ready after `PON` (it stays low
    /// or toggles until the first refresh; the vendor demo waits a fixed 200 ms).
    fn settle(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.wait_busy(false, Some(200))?;
        Ok(())
    }

    fn init_sequence(&mut self) -> Result<(), Error<B::Error>> {
        // Vendor OTP reference flow: reset, booster soft start, power on, panel
        // setting, resolution, VCOM and data interval.
        self.bus.reset()?;
        self.wait_ready()?;
        self.bus.send_command(CMD_PWR, &[0x03, 0x00, 0x2B, 0x2B, 0x03])?;
        self.bus.send_command(CMD_BTST, &[0x17, 0x17, 0x17])?;
        self.bus.send_command(CMD_PON, &[])?;
        self.settle()?;
        self.powered = true;
        self.bus.send_command(CMD_PSR, &[self.psr])?;
        let tres = [
            (self.width & 0xF8) as u8,
            ((self.height >> 8) & 1) as u8,
            (self.height & 0xFF) as u8,
        ];
        self.bus.send_command(CMD_TRES, &tres)?;
        self.bus.send_command(CMD_CDI, &[0x97])?;
        Ok(())
    }

    fn wake_internal(&mut self) -> Result<(), Error<B::Error>> {
        self.asleep = false;
        self.powered = false;
        self.init_sequence()
    }

    fn ensure_power(&mut self) -> Result<(), Error<B::Error>> {
        if self.powered {
            return Ok(());
        }
        self.bus.send_command(CMD_PON, &[])?;
        self.settle()?;
        self.powered = true;
        self.bus.send_command(CMD_CDI, &[0x97])?;
        Ok(())
    }

    fn power_off(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.send_command(CMD_CDI, &[0xF7])?;
        self.bus.send_command(CMD_POF, &[])?;
        self.powered = false;
        self.wait_ready()
    }

    fn refresh_power_off(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.send_command(CMD_DRF, &[])?;
        self.wait_ready()?;
        self.power_off()
    }

    fn write_planes(&mut self) -> Result<(), Error<B::Error>> {
        let size = self.size();
        self.bus.send_command(CMD_DTM1, &[])?;
        send_inverted(&mut self.bus, &self.fb[..size])?;
        self.bus.send_command(CMD_DTM2, &[])?;
        self.bus.send_data(&self.fb[..size])?;
        Ok(())
    }
}

/// IL0373 black/white e-paper controller — full interface.
///
/// Extends [`Il0373Minimal`] (reachable through `Deref`) with drawing helpers,
/// partial-window updates, scan direction, power and VCOM settings, frame rate,
/// user LUTs and the read commands.
pub struct Il0373Full<'a, B: DisplayBus> {
    inner: Il0373Minimal<'a, B>,
}

impl<'a, B: DisplayBus> core::ops::Deref for Il0373Full<'a, B> {
    type Target = Il0373Minimal<'a, B>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<'a, B: DisplayBus> core::ops::DerefMut for Il0373Full<'a, B> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl<'a, B: DisplayBus> Il0373Full<'a, B> {
    /// Create the driver and run the initialisation sequence; see
    /// [`Il0373Minimal::new`] for the arguments.
    pub fn new(
        bus: B,
        buffer: &'a mut [u8],
        width: u16,
        height: u16,
        busy_timeout_ms: Option<u32>,
    ) -> Result<Self, Error<B::Error>> {
        Ok(Self {
            inner: Il0373Minimal::new(bus, buffer, width, height, busy_timeout_ms)?,
        })
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.inner.release()
    }

    // ---- framebuffer -------------------------------------------------------------------

    /// Colour of one framebuffer pixel: [`BLACK`] or [`WHITE`] ([`BLACK`] when out of range).
    pub fn get_pixel(&self, x: i32, y: i32) -> u8 {
        let d = &self.inner;
        if x < 0 || y < 0 || x >= d.width as i32 || y >= d.height as i32 {
            return BLACK;
        }
        let index = y as usize * (d.width as usize / 8) + (x as usize >> 3);
        let mask = 0x80u8 >> (x & 7);
        if d.fb[index] & mask != 0 {
            WHITE
        } else {
            BLACK
        }
    }

    /// Bresenham line into the framebuffer.
    pub fn draw_line(&mut self, mut x0: i32, mut y0: i32, x1: i32, y1: i32, color: u8) {
        let dx = (x1 - x0).abs();
        let dy = -(y1 - y0).abs();
        let sx = if x0 < x1 { 1 } else { -1 };
        let sy = if y0 < y1 { 1 } else { -1 };
        let mut err = dx + dy;
        loop {
            self.inner.set_pixel(x0, y0, color);
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
    pub fn draw_rect(&mut self, x: i32, y: i32, w: i32, h: i32, color: u8, fill: bool) {
        if w <= 0 || h <= 0 {
            return;
        }
        if fill {
            for j in 0..h {
                for i in 0..w {
                    self.inner.set_pixel(x + i, y + j, color);
                }
            }
            return;
        }
        self.draw_line(x, y, x + w - 1, y, color);
        self.draw_line(x, y + h - 1, x + w - 1, y + h - 1, color);
        self.draw_line(x, y, x, y + h - 1, color);
        self.draw_line(x + w - 1, y, x + w - 1, y + h - 1, color);
    }

    /// Copy a 1-bit-per-pixel bitmap into the framebuffer.
    ///
    /// Row-major, MSB-first, rows padded to whole bytes. Set bits are drawn in
    /// `color`, clear bits leave the framebuffer unchanged.
    pub fn blit(&mut self, x: i32, y: i32, data: &[u8], w: i32, h: i32, color: u8) {
        if w <= 0 || h <= 0 {
            return;
        }
        let row_bytes = ((w + 7) / 8) as usize;
        for j in 0..h {
            for i in 0..w {
                let byte = data[j as usize * row_bytes + (i as usize >> 3)];
                if byte & (0x80 >> (i & 7)) != 0 {
                    self.inner.set_pixel(x + i, y + j, color);
                }
            }
        }
    }

    // ---- updates ------------------------------------------------------------------------

    /// Update only a window: write just its rows, then refresh.
    ///
    /// `PTIN`, `PTL` (window widened to whole 8-pixel columns), the window's
    /// bytes of both planes, `DRF`, power off, `PTOUT`. Saves SPI time; the
    /// waveform is the OTP full waveform, so the panel still flashes.
    pub fn show_region(
        &mut self,
        mut x: i32,
        mut y: i32,
        mut w: i32,
        mut h: i32,
    ) -> Result<(), Error<B::Error>> {
        if w <= 0 || h <= 0 {
            return Err(Error::InvalidWindow);
        }
        if x < 0 {
            w += x;
            x = 0;
        }
        if y < 0 {
            h += y;
            y = 0;
        }
        let d = &mut self.inner;
        if x >= d.width as i32 || y >= d.height as i32 || w <= 0 || h <= 0 {
            return Err(Error::InvalidWindow);
        }
        let x0 = x & !7;
        let x1 = ((x + w - 1) | 7).min(d.width as i32 - 1);
        let y0 = y;
        let y1 = (y + h - 1).min(d.height as i32 - 1);
        if d.asleep {
            d.wake_internal()?;
        }
        d.ensure_power()?;
        d.bus.send_command(CMD_PTIN, &[])?;
        let ptl = [
            (x0 & 0xF8) as u8,
            ((x1 & 0xF8) | 0x07) as u8,
            ((y0 >> 8) & 1) as u8,
            (y0 & 0xFF) as u8,
            ((y1 >> 8) & 1) as u8,
            (y1 & 0xFF) as u8,
            0x00, // PT_SCAN = 0: scan only inside the window
        ];
        d.bus.send_command(CMD_PTL, &ptl)?;
        let row_bytes = d.width as usize / 8;
        let win_bytes = ((x1 - x0 + 1) / 8) as usize;
        for plane in 0..2 {
            let cmd = if plane == 0 { CMD_DTM1 } else { CMD_DTM2 };
            d.bus.send_command(cmd, &[])?;
            for row in y0..=y1 {
                let start = row as usize * row_bytes + (x0 >> 3) as usize;
                let chunk = &d.fb[start..start + win_bytes];
                if plane == 0 {
                    send_inverted(&mut d.bus, chunk)?;
                } else {
                    d.bus.send_data(chunk)?;
                }
            }
        }
        let result = d.refresh_power_off();
        d.bus.send_command(CMD_PTOUT, &[])?;
        result
    }

    /// Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept.
    pub fn wake(&mut self) -> Result<(), Error<B::Error>> {
        self.inner.wake_internal()
    }

    /// Wait for BUSY to go high; `Ok(false)` on timeout. `None` waits without limit.
    pub fn wait_busy(&mut self, timeout_ms: Option<u32>) -> Result<bool, Error<B::Error>> {
        Ok(self.inner.bus.wait_busy(false, timeout_ms)?)
    }

    // ---- settings -----------------------------------------------------------------------

    fn write_psr(&mut self) -> Result<(), Error<B::Error>> {
        let psr = self.inner.psr;
        self.inner.bus.send_command(CMD_PSR, &[psr])?;
        Ok(())
    }

    /// Mirror the picture: `horizontal` clears SHL, `vertical` clears UD
    /// (framebuffer unchanged).
    pub fn set_flip(&mut self, horizontal: bool, vertical: bool) -> Result<(), Error<B::Error>> {
        if horizontal {
            self.inner.psr &= !0x04;
        } else {
            self.inner.psr |= 0x04;
        }
        if vertical {
            self.inner.psr &= !0x08;
        } else {
            self.inner.psr |= 0x08;
        }
        self.write_psr()
    }

    /// Source/gate power (`PWR`, internal DC-DC).
    ///
    /// Advanced: the values must match the panel vendor's; a wrong set can
    /// drive the panel outside its rating. The driver does not write PWR by
    /// default. `vdh`/`vdl` are 6.4..15.0 V in 0.2 V steps (`vdl` as a
    /// magnitude); `vgh` is 20, 19, 18 or 17 V (VGL = -VGH).
    pub fn set_power(&mut self, vdh: f32, vdl: f32, vgh: f32) -> Result<(), Error<B::Error>> {
        let l = if vdl < 0.0 { -vdl } else { vdl };
        let lv = ((20.0 - vgh + 0.5) as i32).clamp(0, 3) as u8;
        let p = [
            0x03, // VDS_EN | VDG_EN
            lv,   // VCOM_HV = 0, VGHL_LV[1:0]
            code(vdh, 6.4, 0.2, 0x2B),
            code(l, 6.4, 0.2, 0x2B),
            0x03, // VDHR (red): unused in KW mode
        ];
        self.inner.bus.send_command(CMD_PWR, &p)?;
        Ok(())
    }

    /// VCOM_DC (`VDCS`): -0.10 .. -3.00 V in 0.05 V steps.
    pub fn set_vcom_dc(&mut self, volts: f32) -> Result<(), Error<B::Error>> {
        let c = code(-0.10 - volts, 0.0, 0.05, 0x3A);
        self.inner.bus.send_command(CMD_VDCS, &[c])?;
        Ok(())
    }

    /// Frame rate (`PLL`, M/N layout): the nearest rate of the table, 4..200 Hz.
    pub fn set_frame_rate(&mut self, hz: u16) -> Result<(), Error<B::Error>> {
        let mut best = 0x3Cu8; // M = 7, N = 4 (50 Hz)
        let mut best_diff = i32::MAX;
        for m in 1..=7usize {
            for n in 1..=7usize {
                let diff = (PLL_HZ[m - 1][n - 1] as i32 - hz as i32).abs();
                if diff < best_diff {
                    best_diff = diff;
                    best = ((m << 3) | n) as u8;
                }
            }
        }
        self.inner.bus.send_command(CMD_PLL, &[best])?;
        Ok(())
    }

    /// Booster soft start (`BTST`): phase A, B, C bytes.
    pub fn set_soft_start(&mut self, a: u8, b: u8, c: u8) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(CMD_BTST, &[a, b, c])?;
        Ok(())
    }

    /// VCOM and data interval (`CDI`): interval `cdi` 0..15 (0b0111 = 10 hsync),
    /// border VBD 0..3 (2 as at initialisation), polarity DDX 0..3 (the
    /// framebuffer mapping assumes 1; changing it is the caller's responsibility).
    pub fn set_interval(
        &mut self,
        cdi: u8,
        border: u8,
        polarity: u8,
    ) -> Result<(), Error<B::Error>> {
        let v = ((border & 3) << 6) | ((polarity & 3) << 4) | (cdi & 0x0F);
        self.inner.bus.send_command(CMD_CDI, &[v])?;
        Ok(())
    }

    /// Temperature sensor source (`TSE`): internal (default) or external LM75,
    /// offset -8..+7 degrees Celsius.
    pub fn set_temperature_sensor(
        &mut self,
        external: bool,
        offset: i8,
    ) -> Result<(), Error<B::Error>> {
        let v = (if external { 0x80 } else { 0x00 }) | (offset as u8 & 0x0F);
        self.inner.bus.send_command(CMD_TSE, &[v])?;
        Ok(())
    }

    /// Load one user waveform table and select register LUTs (`PSR.REG = 1`).
    ///
    /// `table`: 0 = VCOM, 1 = W-W, 2 = B-W, 3 = W-B, 4 = B-B (commands
    /// `0x20..=0x24`). The chip datasheet gives 44 (VCOM) / 42 bytes per table,
    /// the panel datasheet 60 / 36 / 60 / 36 / 36; the bytes are sent as given.
    pub fn write_lut(&mut self, table: u8, data: &[u8]) -> Result<(), Error<B::Error>> {
        if table > 4 {
            return Err(Error::InvalidTable);
        }
        self.inner.bus.send_command(CMD_LUTC + table, data)?;
        self.inner.psr |= 0x20;
        self.write_psr()
    }

    /// Send a raw command and parameters (escape hatch).
    pub fn send_command(&mut self, command: u8, data: &[u8]) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(command, data)?;
        Ok(())
    }

    // ---- reads (half-duplex wiring only) ------------------------------------------------

    fn read(&mut self, cmd: u8, buf: &mut [u8]) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(cmd, &[])?;
        self.inner.bus.read_data(buf)?;
        Ok(())
    }

    /// Read `FLG`: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY.
    pub fn read_status(&mut self) -> Result<u8, Error<B::Error>> {
        let mut b = [0u8; 1];
        self.read(CMD_FLG, &mut b)?;
        Ok(b[0])
    }

    /// Read the internal temperature sensor (`TSC`), signed degrees Celsius.
    pub fn read_temperature(&mut self) -> Result<i8, Error<B::Error>> {
        let mut b = [0u8; 2];
        self.read(CMD_TSC, &mut b)?;
        Ok(b[0] as i8)
    }

    /// Read `REV` (`LUT_REV`, one byte).
    pub fn read_revision(&mut self) -> Result<u8, Error<B::Error>> {
        let mut b = [0u8; 1];
        self.read(CMD_REV, &mut b)?;
        Ok(b[0])
    }

    /// Read the measured VCOM (`VV`): -0.10 V - 0.05 V x VV[5:0].
    pub fn read_vcom(&mut self) -> Result<f32, Error<B::Error>> {
        let mut b = [0u8; 1];
        self.read(CMD_VV, &mut b)?;
        Ok(-0.10 - 0.05 * (b[0] & 0x3F) as f32)
    }

    /// Read low-power detection (`LPD`): `true` when the supply is low.
    pub fn read_low_power(&mut self) -> Result<bool, Error<B::Error>> {
        let mut b = [0u8; 1];
        self.read(CMD_LPD, &mut b)?;
        Ok(b[0] & 1 == 0)
    }

    /// Read `buf.len()` OTP bytes from address 0 (`ROTP`; the dummy byte is dropped).
    pub fn read_otp(&mut self, buf: &mut [u8]) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(CMD_ROTP, &[])?;
        let mut dummy = [0u8; 1];
        self.inner.bus.read_data(&mut dummy)?;
        self.inner.bus.read_data(buf)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::connection::display::DisplayError;

    #[derive(Debug, PartialEq)]
    enum Ev {
        Reset,
        Wait(bool, Option<u32>),
        Cmd(u8, Vec<u8>),
        Data(Vec<u8>),
        Read(usize),
    }

    struct MockBus {
        events: Vec<Ev>,
        ready: bool,
        simplex: bool,
        read_queue: Vec<u8>,
    }

    impl MockBus {
        fn new() -> Self {
            Self { events: Vec::new(), ready: true, simplex: false, read_queue: Vec::new() }
        }
        fn commands(&self) -> Vec<(u8, Vec<u8>)> {
            self.events
                .iter()
                .filter_map(|e| match e {
                    Ev::Cmd(c, d) => Some((*c, d.clone())),
                    _ => None,
                })
                .collect()
        }
    }

    impl DisplayBus for MockBus {
        type Error = DisplayError<(), (), (), ()>;
        fn reset(&mut self) -> Result<(), Self::Error> {
            self.events.push(Ev::Reset);
            Ok(())
        }
        fn wait_busy(&mut self, active_high: bool, timeout: Option<u32>) -> Result<bool, Self::Error> {
            self.events.push(Ev::Wait(active_high, timeout));
            Ok(self.ready)
        }
        fn send_command(&mut self, cmd: u8, data: &[u8]) -> Result<(), Self::Error> {
            self.events.push(Ev::Cmd(cmd, data.to_vec()));
            Ok(())
        }
        fn send_data(&mut self, data: &[u8]) -> Result<(), Self::Error> {
            self.events.push(Ev::Data(data.to_vec()));
            Ok(())
        }
        fn read_data(&mut self, buf: &mut [u8]) -> Result<(), Self::Error> {
            if self.simplex {
                return Err(DisplayError::ReadUnavailable);
            }
            self.events.push(Ev::Read(buf.len()));
            for b in buf.iter_mut() {
                *b = if self.read_queue.is_empty() { 0 } else { self.read_queue.remove(0) };
            }
            Ok(())
        }
    }

    fn full<'a>(buf: &'a mut [u8], w: u16, h: u16) -> Il0373Full<'a, MockBus> {
        Il0373Full::new(MockBus::new(), buf, w, h, Some(30000)).unwrap()
    }

    #[test]
    fn init_sequence_default_size() {
        let mut fb = vec![0u8; 2756];
        let d = Il0373Minimal::new(MockBus::new(), &mut fb, 104, 212, Some(30000)).unwrap();
        let bus = d.release();
        assert_eq!(bus.events[0], Ev::Reset);
        assert_eq!(bus.events[1], Ev::Wait(false, Some(30000)));
        assert_eq!(
            bus.commands(),
            vec![
                (0x01, vec![0x03, 0x00, 0x2B, 0x2B, 0x03]),
                (0x06, vec![0x17, 0x17, 0x17]),
                (0x04, vec![]),
                (0x00, vec![0x1F]),
                (0x61, vec![0x68, 0x00, 0xD4]),
                (0x50, vec![0x97]),
            ]
        );
        assert_eq!(bus.events[5], Ev::Wait(false, Some(200)));
    }

    #[test]
    fn init_tres_for_largest_panel() {
        let mut fb = vec![0u8; 160 / 8 * 296];
        let d = Il0373Minimal::new(MockBus::new(), &mut fb, 160, 296, None).unwrap();
        let bus = d.release();
        assert_eq!(bus.events[1], Ev::Wait(false, None));
        assert!(bus.commands().contains(&(0x61, vec![0xA0, 0x01, 0x28])));
    }

    #[test]
    fn invalid_sizes_are_rejected() {
        let mut fb = vec![0u8; 20000];
        assert!(matches!(
            Il0373Minimal::new(MockBus::new(), &mut fb, 101, 212, None),
            Err(Error::InvalidSize)
        ));
        assert!(matches!(
            Il0373Minimal::new(MockBus::new(), &mut fb, 168, 212, None),
            Err(Error::InvalidSize)
        ));
        assert!(matches!(
            Il0373Minimal::new(MockBus::new(), &mut fb, 104, 297, None),
            Err(Error::InvalidSize)
        ));
        let mut small = vec![0u8; 10];
        assert!(matches!(
            Il0373Minimal::new(MockBus::new(), &mut small, 104, 212, None),
            Err(Error::InvalidSize)
        ));
    }

    #[test]
    fn init_timeout() {
        let mut fb = vec![0u8; 4];
        let mut bus = MockBus::new();
        bus.ready = false;
        assert!(matches!(
            Il0373Minimal::new(bus, &mut fb, 16, 2, Some(10)),
            Err(Error::BusyTimeout)
        ));
    }

    #[test]
    fn framebuffer_mapping() {
        let mut fb = [0u8; 4];
        let mut d = full(&mut fb, 16, 2);
        assert_eq!(d.get_pixel(0, 0), WHITE);
        d.set_pixel(0, 0, BLACK);
        d.set_pixel(9, 1, BLACK);
        assert_eq!(d.get_pixel(0, 0), BLACK);
        assert_eq!(d.get_pixel(9, 1), BLACK);
        d.set_pixel(-1, 0, BLACK);
        d.set_pixel(16, 0, BLACK);
        d.set_pixel(0, 2, BLACK);
        d.set_pixel(0, 0, WHITE);
        assert_eq!(d.get_pixel(0, 0), WHITE);
        let _ = d.release();
        assert_eq!(fb[3], 0xBF);
    }

    #[test]
    fn show_sequence() {
        let mut fb = [0u8; 4];
        let mut d = Il0373Minimal::new(MockBus::new(), &mut fb, 16, 2, Some(1)).unwrap();
        d.set_pixel(0, 0, BLACK);
        let n = 9; // reset, wait, PWR, BTST, PON, settle, PSR, TRES, CDI
        d.show().unwrap();
        let bus = d.release();
        let tail = &bus.events[n..];
        assert_eq!(tail[0], Ev::Cmd(0x10, vec![]));
        assert_eq!(tail[1], Ev::Data(vec![0x80, 0, 0, 0]));
        assert_eq!(tail[2], Ev::Cmd(0x13, vec![]));
        assert_eq!(tail[3], Ev::Data(vec![0x7F, 0xFF, 0xFF, 0xFF]));
        assert_eq!(tail[4], Ev::Cmd(0x12, vec![]));
        assert_eq!(tail[5], Ev::Wait(false, Some(1)));
        assert_eq!(tail[6], Ev::Cmd(0x50, vec![0xF7]));
        assert_eq!(tail[7], Ev::Cmd(0x02, vec![]));
        assert_eq!(tail.len(), 9);
    }

    #[test]
    fn second_show_powers_on_first() {
        let mut fb = [0u8; 4];
        let mut d = Il0373Minimal::new(MockBus::new(), &mut fb, 16, 2, None).unwrap();
        d.show().unwrap();
        let n = d.bus.events.len();
        d.show().unwrap();
        let tail = &d.bus.events[n..];
        assert_eq!(tail[0], Ev::Cmd(0x04, vec![]));
        assert!(matches!(tail[1], Ev::Wait(false, _)));
        assert_eq!(tail[2], Ev::Cmd(0x50, vec![0x97]));
        assert_eq!(tail[3], Ev::Cmd(0x10, vec![]));
    }

    #[test]
    fn show_timeout_errors() {
        let mut fb = [0u8; 4];
        let mut d = Il0373Minimal::new(MockBus::new(), &mut fb, 16, 2, None).unwrap();
        d.bus.ready = false;
        assert!(matches!(d.show(), Err(Error::BusyTimeout)));
    }

    #[test]
    fn sleep_then_show_wakes() {
        let mut fb = [0u8; 4];
        let mut d = Il0373Minimal::new(MockBus::new(), &mut fb, 16, 2, None).unwrap();
        d.sleep().unwrap();
        let c = d.bus.commands();
        let l = c.len();
        assert_eq!(c[l - 3], (0x50, vec![0xF7]));
        assert_eq!(c[l - 2], (0x02, vec![]));
        assert_eq!(c[l - 1], (0x07, vec![0xA5]));
        d.show().unwrap();
        let bus = d.release();
        assert_eq!(bus.events.iter().filter(|e| **e == Ev::Reset).count(), 2);
    }

    #[test]
    fn settings_encodings() {
        let mut fb = [0u8; 4];
        let mut d = full(&mut fb, 16, 2);
        let base = d.bus.commands().len();
        d.set_flip(true, false).unwrap();
        d.set_power(14.0, 14.0, 20.0).unwrap();
        d.set_power(6.4, 15.0, 17.0).unwrap();
        d.set_vcom_dc(-1.0).unwrap();
        d.set_vcom_dc(-9.0).unwrap();
        d.set_frame_rate(50).unwrap();
        d.set_frame_rate(200).unwrap();
        d.set_frame_rate(30).unwrap();
        d.set_soft_start(0x17, 0x17, 0x17).unwrap();
        d.set_interval(7, 2, 1).unwrap();
        d.set_temperature_sensor(true, -3).unwrap();
        let c = d.bus.commands();
        let c = &c[base..];
        assert_eq!(c[0], (0x00, vec![0x1B]));
        assert_eq!(c[1], (0x01, vec![0x03, 0x00, 0x26, 0x26, 0x03]));
        assert_eq!(c[2], (0x01, vec![0x03, 0x03, 0x00, 0x2B, 0x03]));
        assert_eq!(c[3], (0x82, vec![0x12]));
        assert_eq!(c[4], (0x82, vec![0x3A]));
        assert_eq!(c[5], (0x30, vec![0x3C]));
        assert_eq!(c[6], (0x30, vec![0x39]));
        assert_eq!(c[7], (0x30, vec![0x09]));
        assert_eq!(c[8], (0x06, vec![0x17, 0x17, 0x17]));
        assert_eq!(c[9], (0x50, vec![0x97]));
        assert_eq!(c[10], (0x41, vec![0x8D]));
    }

    #[test]
    fn write_lut_sends_table_then_psr() {
        let mut fb = [0u8; 4];
        let mut d = full(&mut fb, 16, 2);
        let base = d.bus.commands().len();
        d.write_lut(0, &[0u8; 44]).unwrap();
        d.write_lut(4, &[0u8; 42]).unwrap();
        let c = d.bus.commands();
        assert_eq!(c[base].0, 0x20);
        assert_eq!(c[base].1.len(), 44);
        assert_eq!(c[base + 1], (0x00, vec![0x3F]));
        assert_eq!(c[base + 2].0, 0x24);
        assert!(matches!(d.write_lut(5, &[]), Err(Error::InvalidTable)));
    }

    #[test]
    fn drawing_primitives() {
        let mut fb = [0u8; 4];
        let mut d = full(&mut fb, 16, 2);
        d.draw_line(0, 0, 1, 1, BLACK);
        assert_eq!(d.get_pixel(0, 0), BLACK);
        assert_eq!(d.get_pixel(1, 1), BLACK);
        d.draw_rect(4, 0, 4, 2, BLACK, true);
        assert_eq!(d.get_pixel(4, 0), BLACK);
        assert_eq!(d.get_pixel(7, 1), BLACK);
        d.clear(WHITE);
        d.draw_rect(0, 0, 4, 2, BLACK, false);
        assert_eq!(d.get_pixel(3, 1), BLACK);
        assert_eq!(d.get_pixel(4, 0), WHITE);
        d.clear(WHITE);
        d.blit(8, 0, &[0xA0], 4, 1, BLACK);
        assert_eq!(
            [d.get_pixel(8, 0), d.get_pixel(9, 0), d.get_pixel(10, 0), d.get_pixel(11, 0)],
            [BLACK, WHITE, BLACK, WHITE]
        );
        d.clear(BLACK);
        assert_eq!(d.get_pixel(5, 1), BLACK);
    }

    #[test]
    fn partial_window() {
        let mut fb = [0u8; 16];
        let mut d = full(&mut fb, 32, 4);
        let n = d.bus.events.len();
        d.show_region(9, 1, 6, 2).unwrap();
        let tail = &d.bus.events[n..];
        assert_eq!(tail[0], Ev::Cmd(0x91, vec![]));
        assert_eq!(tail[1], Ev::Cmd(0x90, vec![8, 15, 0, 1, 0, 2, 0]));
        assert_eq!(tail[2], Ev::Cmd(0x10, vec![]));
        assert_eq!(tail[3], Ev::Data(vec![0x00]));
        assert_eq!(tail[4], Ev::Data(vec![0x00]));
        assert_eq!(tail[5], Ev::Cmd(0x13, vec![]));
        assert_eq!(tail[6], Ev::Data(vec![0xFF]));
        assert_eq!(tail[tail.len() - 1], Ev::Cmd(0x92, vec![]));
        assert!(matches!(d.show_region(0, 0, 0, 1), Err(Error::InvalidWindow)));
        assert!(matches!(d.show_region(40, 0, 4, 4), Err(Error::InvalidWindow)));
        assert!(d.wait_busy(Some(10)).unwrap());
        assert_eq!(d.bus.events.last().unwrap(), &Ev::Wait(false, Some(10)));
    }

    #[test]
    fn reads() {
        let mut fb = [0u8; 4];
        let mut d = full(&mut fb, 16, 2);
        d.bus.read_queue = vec![0x05];
        assert_eq!(d.read_status().unwrap(), 5);
        d.bus.read_queue = vec![0xE7, 0x00];
        assert_eq!(d.read_temperature().unwrap(), -25);
        d.bus.read_queue = vec![0x19];
        assert_eq!(d.read_revision().unwrap(), 0x19);
        d.bus.read_queue = vec![0x0A];
        assert!((d.read_vcom().unwrap() - (-0.60)).abs() < 1e-6);
        d.bus.read_queue = vec![0x00];
        assert!(d.read_low_power().unwrap());
        d.bus.read_queue = vec![0xFF, 0x11, 0x22];
        let mut otp = [0u8; 2];
        d.read_otp(&mut otp).unwrap();
        assert_eq!(otp, [0x11, 0x22]);
        d.bus.simplex = true;
        assert!(matches!(d.read_status(), Err(Error::Bus(DisplayError::ReadUnavailable))));
    }
}
