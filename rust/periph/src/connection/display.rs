//! Display connection (SPI + D/C#, RES#, BUSY) for chip drivers.
//!
//! Bundles an SPI link to a display controller (OLED: SH1106; e-paper: SSD1683,
//! UC8276C; ...) with its side-band lines: D/C# (command/data), RES# (hardware
//! reset) and BUSY. RES# and BUSY are optional: an OLED has no BUSY line and
//! may tie RES# high. Chip drivers call [`DisplayConnection::send_command`],
//! [`DisplayConnection::send_data`], [`DisplayConnection::read_data`],
//! [`DisplayConnection::reset`] and [`DisplayConnection::wait_busy`] and never
//! touch D/C, RES, BUSY or CS themselves. See `specs/transport_display.md`.
//!
//! The connection is generic over `embedded-hal` 1.0: an [`SpiDevice`] (which
//! owns CS and frames every transfer with it, hardware or bit-banged), an
//! [`OutputPin`] for D/C# and RES#, an [`InputPin`] for BUSY and a [`DelayNs`].
//! A bit-banged bus is any `SpiBus` impl wrapped in an `SpiDevice` (for
//! example `embedded_hal_bus::spi::ExclusiveDevice`); no extra software SPI is
//! needed in this crate.
//!
//! The BUSY polarity differs between controllers, so it is chip-level
//! knowledge: [`DisplayConnection::wait_busy`] takes it as an argument and
//! [`DisplayConnection::busy_level`] returns the raw level.
//!
//! Every `send_*`/`read_data` call is its own CS# transfer (hardware CS only):
//! a command and its parameters are separate CS# windows and `read_data` uses
//! one window per byte, as the controllers' read timing diagrams show.
//!
//! ## Usage
//!
//! ```rust,ignore
//! use periph::connection::display::{DisplayConnection, NoPin, WireMode};
//!
//! // UC8276C: RES# and BUSY_N wired, DIN only (simplex).
//! let mut display = DisplayConnection::new(spi, dc, Some(res), Some(busy), delay, WireMode::Simplex)?;
//! display.reset()?;
//! display.wait_busy(false, Some(1000))?;           // BUSY_N is active low
//! display.send_command(0x00, &[0x0F])?;
//!
//! // OLED: no RES#, no BUSY.
//! let mut oled = DisplayConnection::new(spi, dc, None::<NoPin>, None::<NoPin>, delay, WireMode::Simplex)?;
//! ```
//!
//! ## Linux host (`linux-embedded-hal` crate)
//!
//! `linux_embedded_hal::SpidevDevice` is an `SpiDevice` (kernel-owned CS#);
//! `CdevPin` provides D/C#, RES# and BUSY; `Delay` is the `DelayNs`.
//!
//! ```toml
//! linux-embedded-hal = "0.4"
//! embedded-hal = "1"
//! ```

use core::convert::Infallible;
use embedded_hal::delay::DelayNs;
use embedded_hal::digital::{ErrorType, InputPin, OutputPin};
use embedded_hal::spi::SpiDevice;

/// How the controller's data pin is wired to the host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WireMode {
    /// MOSI -> DIN, write only; [`DisplayConnection::read_data`] is not available.
    Simplex,
    /// DIN/DOUT shared with MOSI, read through the host SPI peripheral's hardware
    /// 3-wire (half-duplex) mode. The supplied `SpiDevice` must be configured for it.
    HalfDuplexHw,
    /// MOSI -- resistor -- MISO joined at DIN/DOUT, read through a full-duplex SPI
    /// master (the `SpiDevice` clocks dummy bytes out while sampling MISO).
    HalfDuplexPin,
}

/// Placeholder pin for an unused RES# or BUSY line: `None::<NoPin>`.
///
/// Never driven or read (the connection only touches pins that are `Some`);
/// it exists so the optional pin type parameters can be named.
#[derive(Debug, Clone, Copy, Default)]
pub struct NoPin;

impl ErrorType for NoPin {
    type Error = Infallible;
}

impl OutputPin for NoPin {
    fn set_low(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
    fn set_high(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

impl InputPin for NoPin {
    fn is_high(&mut self) -> Result<bool, Self::Error> {
        Ok(false)
    }
    fn is_low(&mut self) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

/// Error type for [`DisplayConnection`] operations.
#[derive(Debug)]
pub enum DisplayError<SE, DE, RE, BE> {
    /// Error transferring data over the SPI bus.
    Spi(SE),
    /// Error driving the D/C# pin.
    Dc(DE),
    /// Error driving the RES# pin.
    Res(RE),
    /// Error reading the BUSY pin.
    Busy(BE),
    /// [`DisplayConnection::read_data`] was called in [`WireMode::Simplex`].
    ReadUnavailable,
}

/// Display connection: SPI + D/C#, optional RES# and optional BUSY.
pub struct DisplayConnection<SPI, DC, RES, BUSY, DELAY> {
    spi: SPI,
    dc: DC,
    res: Option<RES>,
    busy: Option<BUSY>,
    delay: DELAY,
    wire_mode: WireMode,
    reset_pulse_us: u32,
    reset_settle_ms: u32,
    enabled: bool,
}

type Err<SPI, DC, RES, BUSY> = DisplayError<
    <SPI as embedded_hal::spi::ErrorType>::Error,
    <DC as ErrorType>::Error,
    <RES as ErrorType>::Error,
    <BUSY as ErrorType>::Error,
>;

impl<SPI, DC, RES, BUSY, DELAY> DisplayConnection<SPI, DC, RES, BUSY, DELAY>
where
    SPI: SpiDevice,
    DC: OutputPin,
    RES: OutputPin,
    BUSY: InputPin,
    DELAY: DelayNs,
{
    /// Create a connection: D/C# low, RES# high (if wired).
    ///
    /// Reset timing defaults to a 200 us RES# pulse (meets the UC8276C 50 us and
    /// SSD1683 200 us minimums) and a 10 ms wait after release; change it with
    /// [`DisplayConnection::with_reset_timing`].
    ///
    /// # Arguments
    ///
    /// * `spi`       – `SpiDevice` for the controller; owns CS# and frames each transfer.
    /// * `dc`        – Pin connected to D/C# (low = command, high = data).
    /// * `res`       – Pin connected to RES#, or `None` (use `None::<NoPin>`).
    /// * `busy`      – Pin connected to BUSY, or `None` for a controller without one.
    /// * `delay`     – Delay provider.
    /// * `wire_mode` – How DIN/DOUT is wired.
    pub fn new(
        spi: SPI,
        mut dc: DC,
        mut res: Option<RES>,
        busy: Option<BUSY>,
        delay: DELAY,
        wire_mode: WireMode,
    ) -> Result<Self, Err<SPI, DC, RES, BUSY>> {
        dc.set_low().map_err(DisplayError::Dc)?;
        if let Some(ref mut res) = res {
            res.set_high().map_err(DisplayError::Res)?;
        }
        Ok(Self {
            spi,
            dc,
            res,
            busy,
            delay,
            wire_mode,
            reset_pulse_us: 200,
            reset_settle_ms: 10,
            enabled: true,
        })
    }

    /// Set the RES# low time in microseconds and the wait after release in milliseconds.
    pub fn with_reset_timing(mut self, pulse_us: u32, settle_ms: u32) -> Self {
        self.reset_pulse_us = pulse_us;
        self.reset_settle_ms = settle_ms;
        self
    }

    /// Resume operation.
    pub fn enable(&mut self) {
        self.enabled = true;
    }

    /// Gate all bus operations: they become no-ops while disabled.
    pub fn disable(&mut self) {
        self.enabled = false;
    }

    /// Return the current software-gate state.
    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    /// The configured wire mode.
    pub fn wire_mode(&self) -> WireMode {
        self.wire_mode
    }

    /// Hardware reset: RES# low for the pulse time, then high and wait the settle time.
    ///
    /// Does not wait for BUSY; chip drivers call [`DisplayConnection::wait_busy`]
    /// afterwards with their polarity. No-op if disabled or no RES# pin was given.
    pub fn reset(&mut self) -> Result<(), Err<SPI, DC, RES, BUSY>> {
        if !self.enabled {
            return Ok(());
        }
        if let Some(ref mut res) = self.res {
            res.set_low().map_err(DisplayError::Res)?;
            self.delay.delay_us(self.reset_pulse_us);
            res.set_high().map_err(DisplayError::Res)?;
            self.delay.delay_ms(self.reset_settle_ms);
        }
        Ok(())
    }

    /// Raw level of the BUSY line; `false` when no BUSY pin was given.
    pub fn busy_level(&mut self) -> Result<bool, Err<SPI, DC, RES, BUSY>> {
        match self.busy {
            Some(ref mut busy) => busy.is_high().map_err(DisplayError::Busy),
            None => Ok(false),
        }
    }

    /// Block for `ms` milliseconds (for chips without a BUSY pin, e.g. a power-up wait).
    pub fn delay_ms(&mut self, ms: u32) {
        self.delay.delay_ms(ms);
    }

    /// Block for at least `us` microseconds (for chips that need a gap between
    /// bytes, e.g. the ST7036's 30 us). Where the delay provider cannot be that
    /// precise the wait is longer, which is harmless.
    pub fn delay_us(&mut self, us: u32) {
        self.delay.delay_us(us);
    }

    /// Poll BUSY (every millisecond) until it is no longer active.
    ///
    /// # Arguments
    ///
    /// * `busy_active_high` – `true` if the controller drives BUSY high while busy
    ///   (SSD1683), `false` if it drives it low (UC8276C BUSY_N).
    /// * `timeout_ms`       – Give up after this many milliseconds; `None` waits forever.
    ///
    /// Returns `Ok(true)` when the controller became ready (or has no BUSY pin),
    /// `Ok(false)` on timeout.
    pub fn wait_busy(
        &mut self,
        busy_active_high: bool,
        timeout_ms: Option<u32>,
    ) -> Result<bool, Err<SPI, DC, RES, BUSY>> {
        let busy = match self.busy {
            Some(ref mut busy) => busy,
            None => return Ok(true),
        };
        let mut waited: u32 = 0;
        while busy.is_high().map_err(DisplayError::Busy)? == busy_active_high {
            if let Some(limit) = timeout_ms {
                if waited >= limit {
                    return Ok(false);
                }
            }
            self.delay.delay_ms(1);
            waited = waited.saturating_add(1);
        }
        Ok(true)
    }

    /// Send a command byte (D/C# low), then optional parameter bytes (D/C# high).
    ///
    /// The command byte and the parameters are separate SPI transfers (separate
    /// CS# windows). No-op if disabled.
    pub fn send_command(
        &mut self,
        cmd: u8,
        data: &[u8],
    ) -> Result<(), Err<SPI, DC, RES, BUSY>> {
        if !self.enabled {
            return Ok(());
        }
        self.dc.set_low().map_err(DisplayError::Dc)?;
        self.spi.write(&[cmd]).map_err(DisplayError::Spi)?;
        if !data.is_empty() {
            self.dc.set_high().map_err(DisplayError::Dc)?;
            self.spi.write(data).map_err(DisplayError::Spi)?;
        }
        Ok(())
    }

    /// Send data bytes (D/C# high) as one SPI transfer. No-op if disabled.
    pub fn send_data(&mut self, data: &[u8]) -> Result<(), Err<SPI, DC, RES, BUSY>> {
        if !self.enabled || data.is_empty() {
            return Ok(());
        }
        self.dc.set_high().map_err(DisplayError::Dc)?;
        self.spi.write(data).map_err(DisplayError::Spi)?;
        Ok(())
    }

    /// Read `buf.len()` bytes the controller drives after a read command (D/C# high).
    ///
    /// One CS# window per byte. A disabled connection fills `buf` with zeros.
    ///
    /// # Errors
    ///
    /// Returns [`DisplayError::ReadUnavailable`] in [`WireMode::Simplex`].
    pub fn read_data(&mut self, buf: &mut [u8]) -> Result<(), Err<SPI, DC, RES, BUSY>> {
        if self.wire_mode == WireMode::Simplex {
            return Err(DisplayError::ReadUnavailable);
        }
        if !self.enabled {
            buf.iter_mut().for_each(|b| *b = 0);
            return Ok(());
        }
        self.dc.set_high().map_err(DisplayError::Dc)?;
        for i in 0..buf.len() {
            self.spi.read(&mut buf[i..=i]).map_err(DisplayError::Spi)?;
        }
        Ok(())
    }

    /// Consume the connection and return the SPI device, pins and delay.
    pub fn release(self) -> (SPI, DC, Option<RES>, Option<BUSY>, DELAY) {
        (self.spi, self.dc, self.res, self.busy, self.delay)
    }
}

/// The operations a display chip driver needs from its connection.
///
/// Implemented by [`DisplayConnection`]; chip drivers are generic over this
/// trait so they can be unit-tested against a recording mock without wiring
/// up `embedded-hal-mock` transactions for every byte of a frame.
pub trait DisplayBus {
    /// Error type of the bus operations.
    type Error;

    /// Hardware reset (RES# low pulse, then settle). Does not wait for BUSY.
    fn reset(&mut self) -> Result<(), Self::Error>;

    /// Block for `ms` milliseconds (power-up waits of chips without a BUSY pin).
    ///
    /// The default does nothing; [`DisplayConnection`] waits for real.
    fn delay_ms(&mut self, _ms: u32) -> Result<(), Self::Error> {
        Ok(())
    }

    /// Block for `us` microseconds (gaps between bytes of chips that need one).
    ///
    /// The default does nothing; [`DisplayConnection`] waits for real.
    fn delay_us(&mut self, _us: u32) -> Result<(), Self::Error> {
        Ok(())
    }

    /// Poll BUSY until it is no longer active; `Ok(false)` on timeout.
    fn wait_busy(
        &mut self,
        busy_active_high: bool,
        timeout_ms: Option<u32>,
    ) -> Result<bool, Self::Error>;

    /// Send a command byte, then optional parameter bytes.
    fn send_command(&mut self, cmd: u8, data: &[u8]) -> Result<(), Self::Error>;

    /// Send data bytes (D/C# high) as one transfer.
    fn send_data(&mut self, data: &[u8]) -> Result<(), Self::Error>;

    /// Read `buf.len()` bytes the controller drives (D/C# high).
    fn read_data(&mut self, buf: &mut [u8]) -> Result<(), Self::Error>;
}

impl<SPI, DC, RES, BUSY, DELAY> DisplayBus for DisplayConnection<SPI, DC, RES, BUSY, DELAY>
where
    SPI: SpiDevice,
    DC: OutputPin,
    RES: OutputPin,
    BUSY: InputPin,
    DELAY: DelayNs,
{
    type Error = Err<SPI, DC, RES, BUSY>;

    fn reset(&mut self) -> Result<(), Self::Error> {
        DisplayConnection::reset(self)
    }

    fn delay_ms(&mut self, ms: u32) -> Result<(), Self::Error> {
        DisplayConnection::delay_ms(self, ms);
        Ok(())
    }

    fn delay_us(&mut self, us: u32) -> Result<(), Self::Error> {
        DisplayConnection::delay_us(self, us);
        Ok(())
    }

    fn wait_busy(
        &mut self,
        busy_active_high: bool,
        timeout_ms: Option<u32>,
    ) -> Result<bool, Self::Error> {
        DisplayConnection::wait_busy(self, busy_active_high, timeout_ms)
    }

    fn send_command(&mut self, cmd: u8, data: &[u8]) -> Result<(), Self::Error> {
        DisplayConnection::send_command(self, cmd, data)
    }

    fn send_data(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        DisplayConnection::send_data(self, data)
    }

    fn read_data(&mut self, buf: &mut [u8]) -> Result<(), Self::Error> {
        DisplayConnection::read_data(self, buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::digital::{
        Mock as PinMock, State, Transaction as PinTransaction,
    };
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    fn w(buf: &[u8]) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(buf.to_vec()),
            SpiTransaction::transaction_end(),
        ]
    }

    fn r(buf: &[u8]) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::read_vec(buf.to_vec()),
            SpiTransaction::transaction_end(),
        ]
    }

    #[test]
    fn construction_idles_dc_low_res_high() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let res = PinMock::new(&[PinTransaction::set(State::High)]);
        let c = DisplayConnection::new(
            spi, dc, Some(res), None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        let (mut spi, mut dc, res, _busy, _d) = c.release();
        spi.done();
        dc.done();
        res.unwrap().done();
    }

    #[test]
    fn send_command_frames_command_and_parameters_separately() {
        let mut t = w(&[0x12]);
        t.extend(w(&[0x01, 0x02]));
        let spi = SpiMock::new(&t);
        let dc = PinMock::new(&[
            PinTransaction::set(State::Low),  // new()
            PinTransaction::set(State::Low),  // command byte
            PinTransaction::set(State::High), // parameters
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        c.send_command(0x12, &[0x01, 0x02]).unwrap();
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }

    #[test]
    fn send_command_without_data_is_one_transfer_and_send_data_goes_high() {
        let mut t = w(&[0x04]);
        t.extend(w(&[0xFF, 0xFF, 0xFF]));
        let spi = SpiMock::new(&t);
        let dc = PinMock::new(&[
            PinTransaction::set(State::Low),  // new()
            PinTransaction::set(State::Low),  // command
            PinTransaction::set(State::High), // data
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        c.send_command(0x04, &[]).unwrap();
        c.send_data(&[0xFF, 0xFF, 0xFF]).unwrap();
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }

    #[test]
    fn reset_pulses_res_low_then_high() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let res = PinMock::new(&[
            PinTransaction::set(State::High), // new()
            PinTransaction::set(State::Low),
            PinTransaction::set(State::High),
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, Some(res), None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap()
        .with_reset_timing(500, 2);
        c.reset().unwrap();
        let (mut spi, mut dc, res, _b, _d) = c.release();
        spi.done();
        dc.done();
        res.unwrap().done();
    }

    #[test]
    fn reset_without_res_pin_is_a_no_op() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        c.reset().unwrap();
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }

    #[test]
    fn wait_busy_active_low_returns_once_busy_goes_high() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let busy = PinMock::new(&[
            PinTransaction::get(State::Low),
            PinTransaction::get(State::Low),
            PinTransaction::get(State::High),
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, Some(busy), NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        assert!(c.wait_busy(false, Some(100)).unwrap());
        let (mut spi, mut dc, _r, busy, _d) = c.release();
        spi.done();
        dc.done();
        busy.unwrap().done();
    }

    #[test]
    fn wait_busy_active_high_returns_once_busy_goes_low() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let busy = PinMock::new(&[
            PinTransaction::get(State::High),
            PinTransaction::get(State::Low),
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, Some(busy), NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        assert!(c.wait_busy(true, None).unwrap());
        let (mut spi, mut dc, _r, busy, _d) = c.release();
        spi.done();
        dc.done();
        busy.unwrap().done();
    }

    #[test]
    fn wait_busy_times_out_after_the_limit() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        // limit 3 ms: polls at waited = 0, 1, 2, 3 -> 4 reads, then gives up
        let busy = PinMock::new(&[
            PinTransaction::get(State::Low),
            PinTransaction::get(State::Low),
            PinTransaction::get(State::Low),
            PinTransaction::get(State::Low),
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, Some(busy), NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        assert!(!c.wait_busy(false, Some(3)).unwrap());
        let (mut spi, mut dc, _r, busy, _d) = c.release();
        spi.done();
        dc.done();
        busy.unwrap().done();
    }

    #[test]
    fn wait_busy_without_busy_pin_returns_true_and_level_is_false() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        assert!(c.wait_busy(true, Some(10)).unwrap());
        assert!(!c.busy_level().unwrap());
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }

    #[test]
    fn read_data_is_one_cs_window_per_byte() {
        let mut t = r(&[0x13]);
        t.extend(r(&[0x07]));
        let spi = SpiMock::new(&t);
        let dc = PinMock::new(&[
            PinTransaction::set(State::Low),  // new()
            PinTransaction::set(State::High), // read
        ]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::HalfDuplexPin,
        )
        .unwrap();
        let mut buf = [0u8; 2];
        c.read_data(&mut buf).unwrap();
        assert_eq!(buf, [0x13, 0x07]);
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }

    #[test]
    fn read_data_in_simplex_is_an_error() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::Simplex,
        )
        .unwrap();
        let mut buf = [0u8; 1];
        assert!(matches!(c.read_data(&mut buf), Err(DisplayError::ReadUnavailable)));
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }

    #[test]
    fn disabled_connection_is_a_no_op_and_reads_zeros() {
        let spi = SpiMock::new(&[]);
        let dc = PinMock::new(&[PinTransaction::set(State::Low)]);
        let mut c = DisplayConnection::new(
            spi, dc, None::<PinMock>, None::<PinMock>, NoopDelay::new(), WireMode::HalfDuplexHw,
        )
        .unwrap();
        c.disable();
        assert!(!c.is_enabled());
        c.send_command(0x12, &[1]).unwrap();
        c.send_data(&[1, 2]).unwrap();
        c.reset().unwrap();
        let mut buf = [9u8; 3];
        c.read_data(&mut buf).unwrap();
        assert_eq!(buf, [0, 0, 0]);
        c.enable();
        assert!(c.is_enabled());
        let (mut spi, mut dc, _r, _b, _d) = c.release();
        spi.done();
        dc.done();
    }
}
