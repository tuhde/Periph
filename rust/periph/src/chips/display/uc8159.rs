//! UC8159 — seven-colour e-paper controller (600 x 448 panels such as the
//! Waveshare 5.65inch e-Paper (F)).
//!
//! The driver keeps the picture in a caller-supplied framebuffer in wire format
//! (4 bits per pixel, two pixels per byte, [`FRAME_BYTES`] = 134 400 bytes; no
//! heap) and sends it on [`Uc8159Minimal::show`]. Every update is
//! `DTM1 + frame`, `PON`, wait BUSY (active low), `DRF`, wait, `POF`, wait;
//! `show` blocks for the whole refresh (about 30 s).
//!
//! The driver is generic over [`DisplayBus`], implemented by
//! [`DisplayConnection`](crate::connection::display::DisplayConnection), which
//! owns CS#, D/C#, RES# and BUSY (see `specs/transport_display.md`).
//!
//! See `specs/display/uc8159.md`. The `read_*` methods of [`Uc8159Full`] need a
//! half-duplex wire mode.
//!
//! ## Constants
//!
//! Colours: [`BLACK`], [`WHITE`], [`GREEN`], [`BLUE`], [`RED`], [`YELLOW`],
//! [`ORANGE`], [`CLEAN`]. Panel: [`WIDTH`], [`HEIGHT`], [`FRAME_BYTES`].

use crate::connection::display::DisplayBus;

pub const BLACK: u8 = 0;
pub const WHITE: u8 = 1;
pub const GREEN: u8 = 2;
pub const BLUE: u8 = 3;
pub const RED: u8 = 4;
pub const YELLOW: u8 = 5;
pub const ORANGE: u8 = 6;
pub const CLEAN: u8 = 7;

pub const WIDTH: u16 = 600;
pub const HEIGHT: u16 = 448;
/// Bytes of one frame in wire format (two pixels per byte).
pub const FRAME_BYTES: usize = WIDTH as usize * HEIGHT as usize / 2;

const CMD_PSR: u8 = 0x00;
const CMD_PWR: u8 = 0x01;
const CMD_POF: u8 = 0x02;
const CMD_PFS: u8 = 0x03;
const CMD_PON: u8 = 0x04;
const CMD_BTST: u8 = 0x06;
const CMD_DSLP: u8 = 0x07;
const CMD_DTM1: u8 = 0x10;
const CMD_DRF: u8 = 0x12;
const CMD_PLL: u8 = 0x30;
const CMD_TSC: u8 = 0x40;
const CMD_TSE: u8 = 0x41;
const CMD_CDI: u8 = 0x50;
const CMD_LPD: u8 = 0x51;
const CMD_TCON: u8 = 0x60;
const CMD_TRES: u8 = 0x61;
const CMD_PWS: u8 = 0xE3;
const CMD_FLG: u8 = 0x71;
const CMD_VV: u8 = 0x81;
const CMD_VDCS: u8 = 0x82;

/// Error type of the UC8159 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The display connection reported an error.
    Bus(E),
    /// BUSY did not go high within the configured timeout.
    BusyTimeout,
    /// The framebuffer is smaller than [`FRAME_BYTES`].
    InvalidSize,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// UC8159 seven-colour e-paper controller — minimal interface.
///
/// See the [module documentation](self) for the update sequence and buffer
/// ownership. `B` is the display connection ([`DisplayBus`]).
pub struct Uc8159Minimal<'a, B: DisplayBus> {
    bus: B,
    fb: &'a mut [u8],
    timeout_ms: Option<u32>,
    psr: u8,
    cdi: u8,
    asleep: bool,
}

impl<'a, B: DisplayBus> Uc8159Minimal<'a, B> {
    /// Create the driver and run the initialisation sequence.
    ///
    /// Hardware reset, wait for BUSY, then the vendor demo's commands: `PSR EF 08`,
    /// `PWR 37 00 23 23`, `PFS 00`, `BTST C7 C7 1D`, `PLL 3C`, `TSE 00`, `CDI 37`,
    /// `TCON 22`, `TRES 600 x 448`, `PWS AA`. The picture on the panel is left
    /// unchanged until [`show`](Self::show). The framebuffer is cleared to white.
    ///
    /// # Arguments
    ///
    /// * `bus`    – Display connection (CS#, D/C#, RES#, BUSY).
    /// * `buffer` – Framebuffer, at least [`FRAME_BYTES`] bytes.
    /// * `busy_timeout_ms` – Limit for every BUSY wait in milliseconds, `None` for no
    ///   limit (a refresh takes about 30 s; 60 000 is a good default).
    ///
    /// # Errors
    ///
    /// [`Error::InvalidSize`] for a too-small buffer, [`Error::BusyTimeout`] when
    /// BUSY never goes high.
    pub fn new(
        bus: B,
        buffer: &'a mut [u8],
        busy_timeout_ms: Option<u32>,
    ) -> Result<Self, Error<B::Error>> {
        if buffer.len() < FRAME_BYTES {
            return Err(Error::InvalidSize);
        }
        let mut d = Self {
            bus,
            fb: buffer,
            timeout_ms: busy_timeout_ms,
            psr: 0xEF,
            cdi: 0x37,
            asleep: false,
        };
        d.clear(WHITE);
        d.init_sequence()?;
        Ok(d)
    }

    /// Panel width in pixels (600).
    pub fn width(&self) -> u16 {
        WIDTH
    }

    /// Panel height in pixels (448).
    pub fn height(&self) -> u16 {
        HEIGHT
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.bus
    }

    /// Fill the framebuffer with one colour code 0..=7. The panel is unchanged until `show`.
    pub fn clear(&mut self, color: u8) {
        let c = color & 7;
        let v = (c << 4) | c;
        self.fb[..FRAME_BYTES].iter_mut().for_each(|b| *b = v);
    }

    /// Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
    pub fn set_pixel(&mut self, x: i32, y: i32, color: u8) {
        if x < 0 || y < 0 || x >= WIDTH as i32 || y >= HEIGHT as i32 {
            return;
        }
        let i = (y as usize * WIDTH as usize + x as usize) >> 1;
        let c = color & 7;
        if x & 1 == 1 {
            self.fb[i] = (self.fb[i] & 0xF0) | c;
        } else {
            self.fb[i] = (self.fb[i] & 0x0F) | (c << 4);
        }
    }

    /// Send the frame and run a full display update (about 30 s): `DTM1` +
    /// 134 400 bytes, `PON`, wait BUSY, `DRF`, wait, `POF`, wait. A sleeping
    /// controller is woken (hardware reset + initialisation) first.
    pub fn show(&mut self) -> Result<(), Error<B::Error>> {
        if self.asleep {
            self.wake_internal()?;
        }
        self.bus.send_command(CMD_DTM1, &[])?;
        for chunk in self.fb[..FRAME_BYTES].chunks(4096) {
            self.bus.send_data(chunk)?;
        }
        self.bus.send_command(CMD_PON, &[])?;
        self.wait_ready()?;
        self.bus.send_command(CMD_DRF, &[])?;
        self.wait_ready()?;
        self.bus.send_command(CMD_POF, &[])?;
        self.wait_ready()
    }

    /// Put the controller into deep sleep (`DSLP` with check code `0xA5`).
    ///
    /// Only a hardware reset restores communication; `show` and
    /// [`Uc8159Full::wake`] do that. The panel keeps its image.
    pub fn sleep(&mut self) -> Result<(), Error<B::Error>> {
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

    fn init_sequence(&mut self) -> Result<(), Error<B::Error>> {
        self.bus.reset()?;
        self.wait_ready()?;
        self.bus.send_command(CMD_PSR, &[self.psr, 0x08])?;
        self.bus.send_command(CMD_PWR, &[0x37, 0x00, 0x23, 0x23])?;
        self.bus.send_command(CMD_PFS, &[0x00])?;
        self.bus.send_command(CMD_BTST, &[0xC7, 0xC7, 0x1D])?;
        self.bus.send_command(CMD_PLL, &[0x3C])?;
        self.bus.send_command(CMD_TSE, &[0x00])?;
        self.bus.send_command(CMD_CDI, &[self.cdi])?;
        self.bus.send_command(CMD_TCON, &[0x22])?;
        self.bus.send_command(CMD_TRES, &[0x02, 0x58, 0x01, 0xC0])?;
        self.bus.send_command(CMD_PWS, &[0xAA])?;
        Ok(())
    }

    fn wake_internal(&mut self) -> Result<(), Error<B::Error>> {
        self.asleep = false;
        self.init_sequence()
    }
}

/// UC8159 seven-colour e-paper controller — full interface.
///
/// Extends [`Uc8159Minimal`] (reachable through `Deref`) with drawing helpers,
/// scan direction, frame rate, border/data interval, temperature-sensor source,
/// VCOM_DC and the read commands.
pub struct Uc8159Full<'a, B: DisplayBus> {
    inner: Uc8159Minimal<'a, B>,
}

impl<'a, B: DisplayBus> core::ops::Deref for Uc8159Full<'a, B> {
    type Target = Uc8159Minimal<'a, B>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<'a, B: DisplayBus> core::ops::DerefMut for Uc8159Full<'a, B> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl<'a, B: DisplayBus> Uc8159Full<'a, B> {
    /// Create the driver and run the initialisation sequence; see
    /// [`Uc8159Minimal::new`] for the arguments.
    pub fn new(
        bus: B,
        buffer: &'a mut [u8],
        busy_timeout_ms: Option<u32>,
    ) -> Result<Self, Error<B::Error>> {
        Ok(Self { inner: Uc8159Minimal::new(bus, buffer, busy_timeout_ms)? })
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.inner.release()
    }

    /// Colour code (0..=7) of one framebuffer pixel ([`BLACK`] when out of range).
    pub fn get_pixel(&self, x: i32, y: i32) -> u8 {
        if x < 0 || y < 0 || x >= WIDTH as i32 || y >= HEIGHT as i32 {
            return BLACK;
        }
        let b = self.inner.fb[(y as usize * WIDTH as usize + x as usize) >> 1];
        if x & 1 == 1 {
            b & 7
        } else {
            (b >> 4) & 7
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

    /// Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept.
    pub fn wake(&mut self) -> Result<(), Error<B::Error>> {
        self.inner.wake_internal()
    }

    /// Wait for BUSY to go high; `Ok(false)` on timeout. `None` waits without limit.
    pub fn wait_busy(&mut self, timeout_ms: Option<u32>) -> Result<bool, Error<B::Error>> {
        Ok(self.inner.bus.wait_busy(false, timeout_ms)?)
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
        let psr = self.inner.psr;
        self.inner.bus.send_command(CMD_PSR, &[psr, 0x08])?;
        Ok(())
    }

    /// Frame rate (`PLL` raw code): `0x3C` = 50 Hz (default), `0x3A` = 100 Hz,
    /// `0x39` = 200 Hz, `0x00..=0x0F` = 12.5 .. 200 Hz.
    pub fn set_frame_rate(&mut self, code: u8) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(CMD_PLL, &[code])?;
        Ok(())
    }

    /// VCOM and data interval (`CDI`): interval `cdi` 0..=15 (0b0111 = 10 hsync),
    /// border colour 0..=7, polarity DDX (1 = default; the colour codes assume 1).
    pub fn set_interval(
        &mut self,
        cdi: u8,
        border: u8,
        polarity: u8,
    ) -> Result<(), Error<B::Error>> {
        self.inner.cdi = ((border & 7) << 5) | ((polarity & 1) << 4) | (cdi & 0x0F);
        let v = self.inner.cdi;
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

    /// VCOM_DC (`VDCS`): -0.10 .. -4.0 V in 0.05 V steps.
    pub fn set_vcom_dc(&mut self, volts: f32) -> Result<(), Error<B::Error>> {
        let c = (((-0.10 - volts) / 0.05 + 2.5) as i32).clamp(2, 0x50) as u8;
        self.inner.bus.send_command(CMD_VDCS, &[c])?;
        Ok(())
    }

    /// Send a raw command and parameters (escape hatch).
    pub fn send_command(&mut self, command: u8, data: &[u8]) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(command, data)?;
        Ok(())
    }

    fn read(&mut self, cmd: u8, buf: &mut [u8]) -> Result<(), Error<B::Error>> {
        self.inner.bus.send_command(cmd, &[])?;
        self.inner.bus.read_data(buf)?;
        Ok(())
    }

    /// Read `FLG`: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.
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

    /// Read low-power detection (`LPD`): `true` when VDD is below 2.5 V.
    pub fn read_low_power(&mut self) -> Result<bool, Error<B::Error>> {
        let mut b = [0u8; 1];
        self.read(CMD_LPD, &mut b)?;
        Ok(b[0] & 1 == 0)
    }

    /// Read the measured VCOM (`VV`): -0.10 V - 0.05 V x (VV - 2).
    pub fn read_vcom(&mut self) -> Result<f32, Error<B::Error>> {
        let mut b = [0u8; 1];
        self.read(CMD_VV, &mut b)?;
        let c = (b[0] & 0x7F) as i32;
        Ok(-0.10 - 0.05 * (if c > 2 { c - 2 } else { 0 }) as f32)
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

    #[test]
    fn init_sequence() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let d = Uc8159Minimal::new(MockBus::new(), &mut fb, Some(60000)).unwrap();
        let bus = d.release();
        assert_eq!(bus.events[0], Ev::Reset);
        assert_eq!(bus.events[1], Ev::Wait(false, Some(60000)));
        assert_eq!(
            bus.commands(),
            vec![
                (0x00, vec![0xEF, 0x08]),
                (0x01, vec![0x37, 0x00, 0x23, 0x23]),
                (0x03, vec![0x00]),
                (0x06, vec![0xC7, 0xC7, 0x1D]),
                (0x30, vec![0x3C]),
                (0x41, vec![0x00]),
                (0x50, vec![0x37]),
                (0x60, vec![0x22]),
                (0x61, vec![0x02, 0x58, 0x01, 0xC0]),
                (0xE3, vec![0xAA]),
            ]
        );
    }

    #[test]
    fn small_buffer_rejected_and_init_timeout() {
        let mut small = vec![0u8; 100];
        assert!(matches!(
            Uc8159Minimal::new(MockBus::new(), &mut small, None),
            Err(Error::InvalidSize)
        ));
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut bus = MockBus::new();
        bus.ready = false;
        assert!(matches!(
            Uc8159Minimal::new(bus, &mut fb, Some(10)),
            Err(Error::BusyTimeout)
        ));
    }

    #[test]
    fn framebuffer_mapping() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut d = Uc8159Full::new(MockBus::new(), &mut fb, None).unwrap();
        assert_eq!(d.get_pixel(0, 0), WHITE);
        for c in 0..8u8 {
            d.set_pixel(7 + c as i32, 3, c);
        }
        for c in 0..8u8 {
            assert_eq!(d.get_pixel(7 + c as i32, 3), c);
        }
        assert_eq!(d.get_pixel(6, 3), WHITE);
        d.set_pixel(599, 447, ORANGE);
        assert_eq!(d.get_pixel(599, 447), ORANGE);
        assert_eq!(d.get_pixel(598, 447), WHITE);
        d.set_pixel(-1, 0, RED);
        d.set_pixel(600, 0, RED);
        d.set_pixel(0, 448, RED);
        assert_eq!(d.get_pixel(0, 0), WHITE);
        assert_eq!(d.get_pixel(700, 9), BLACK);
        d.clear(GREEN);
        assert_eq!(d.get_pixel(333, 222), GREEN);
    }

    #[test]
    fn show_sequence() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut d = Uc8159Minimal::new(MockBus::new(), &mut fb, Some(1)).unwrap();
        d.set_pixel(0, 0, BLACK);
        d.set_pixel(1, 0, ORANGE);
        d.set_pixel(599, 447, RED);
        let n = d.bus.events.len();
        d.show().unwrap();
        let bus = d.release();
        let tail = &bus.events[n..];
        assert_eq!(tail[0], Ev::Cmd(0x10, vec![]));
        let data: Vec<u8> = tail
            .iter()
            .filter_map(|e| if let Ev::Data(d) = e { Some(d.clone()) } else { None })
            .flatten()
            .collect();
        assert_eq!(data.len(), FRAME_BYTES);
        assert_eq!(&data[..2], &[0x06, 0x11]);
        assert_eq!(data[FRAME_BYTES - 1], 0x14);
        let t = &tail[tail.len() - 6..];
        assert_eq!(t[0], Ev::Cmd(0x04, vec![]));
        assert_eq!(t[1], Ev::Wait(false, Some(1)));
        assert_eq!(t[2], Ev::Cmd(0x12, vec![]));
        assert_eq!(t[4], Ev::Cmd(0x02, vec![]));
    }

    #[test]
    fn show_timeout_and_sleep_wake() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut d = Uc8159Minimal::new(MockBus::new(), &mut fb, None).unwrap();
        d.sleep().unwrap();
        d.show().unwrap();
        assert_eq!(d.bus.events.iter().filter(|e| **e == Ev::Reset).count(), 2);
        assert!(d.bus.commands().contains(&(0x07, vec![0xA5])));
        d.bus.ready = false;
        assert!(matches!(d.show(), Err(Error::BusyTimeout)));
    }

    #[test]
    fn settings_encodings() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut d = Uc8159Full::new(MockBus::new(), &mut fb, None).unwrap();
        let base = d.bus.commands().len();
        d.set_flip(true, false).unwrap();
        d.set_flip(false, true).unwrap();
        d.set_flip(false, false).unwrap();
        d.set_frame_rate(0x3A).unwrap();
        d.set_interval(7, WHITE, 1).unwrap();
        d.set_interval(3, BLACK, 0).unwrap();
        d.set_temperature_sensor(true, -3).unwrap();
        d.set_vcom_dc(-0.10).unwrap();
        d.set_vcom_dc(-2.0).unwrap();
        d.set_vcom_dc(-9.0).unwrap();
        let c = d.bus.commands();
        let c = &c[base..];
        assert_eq!(c[0], (0x00, vec![0xEB, 0x08]));
        assert_eq!(c[1], (0x00, vec![0xE7, 0x08]));
        assert_eq!(c[2], (0x00, vec![0xEF, 0x08]));
        assert_eq!(c[3], (0x30, vec![0x3A]));
        assert_eq!(c[4], (0x50, vec![0x37]));
        assert_eq!(c[5], (0x50, vec![0x03]));
        assert_eq!(c[6], (0x41, vec![0x8D]));
        assert_eq!(c[7], (0x82, vec![0x02]));
        assert_eq!(c[8], (0x82, vec![0x28]));
        assert_eq!(c[9], (0x82, vec![0x50]));
    }

    #[test]
    fn drawing_primitives() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut d = Uc8159Full::new(MockBus::new(), &mut fb, None).unwrap();
        d.draw_line(0, 0, 9, 9, RED);
        assert_eq!(d.get_pixel(0, 0), RED);
        assert_eq!(d.get_pixel(9, 9), RED);
        assert_eq!(d.get_pixel(5, 4), WHITE);
        d.clear(WHITE);
        d.draw_rect(1, 1, 5, 4, BLUE, false);
        assert_eq!(d.get_pixel(1, 1), BLUE);
        assert_eq!(d.get_pixel(3, 2), WHITE);
        d.draw_rect(10, 1, 3, 3, YELLOW, true);
        assert_eq!(d.get_pixel(11, 2), YELLOW);
        d.clear(WHITE);
        d.blit(4, 2, &[0xA0, 0x40], 4, 2, GREEN);
        assert_eq!(
            [d.get_pixel(4, 2), d.get_pixel(5, 2), d.get_pixel(6, 2), d.get_pixel(5, 3)],
            [GREEN, WHITE, GREEN, GREEN]
        );
    }

    #[test]
    fn reads() {
        let mut fb = vec![0u8; FRAME_BYTES];
        let mut d = Uc8159Full::new(MockBus::new(), &mut fb, None).unwrap();
        d.bus.read_queue = vec![0x0F];
        assert_eq!(d.read_status().unwrap(), 0x0F);
        d.bus.read_queue = vec![0xE7, 0x00];
        assert_eq!(d.read_temperature().unwrap(), -25);
        d.bus.read_queue = vec![0x01];
        assert!(!d.read_low_power().unwrap());
        d.bus.read_queue = vec![0x0C];
        assert!((d.read_vcom().unwrap() - (-0.60)).abs() < 1e-6);
        d.bus.simplex = true;
        assert!(matches!(d.read_status(), Err(Error::Bus(DisplayError::ReadUnavailable))));
    }
}
