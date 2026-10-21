//! SPI connection for chip drivers.
//!
//! Chip drivers in this library are generic over [`SpiDevice`] from
//! `embedded-hal` 1.0. CS management is handled by the `SpiDevice`
//! implementation — callers pass a fully configured device object and never
//! touch CS directly.
//!
//! ## Writing a chip driver
//!
//! Bound the SPI type parameter on [`SpiDevice`]:
//!
//! ```rust
//! use periph::connection::spi::SpiDevice;
//!
//! pub struct MyChipMinimal<SPI> {
//!     spi: SPI,
//! }
//!
//! impl<SPI: SpiDevice> MyChipMinimal<SPI> {
//!     pub fn new(spi: SPI) -> Self {
//!         Self { spi }
//!     }
//! }
//! ```
//!
//! ## Performing transactions
//!
//! Use [`SpiDevice::transaction`] with [`Operation`] slices to keep CS
//! asserted across a write-then-read sequence:
//!
//! ```rust,ignore
//! use periph::connection::spi::Operation;
//!
//! let mut buf = [0u8; 2];
//! self.spi.transaction(&mut [
//!     Operation::Write(&[reg_addr]),
//!     Operation::Read(&mut buf),
//! ])?;
//! ```
//!
//! ## Linux host (`linux-embedded-hal` + `spidev` crates)
//!
//! `spidev::Spidev` does not implement `SpiBus` directly. Use
//! `linux_embedded_hal::SpidevBus`, which wraps `Spidev` and implements
//! `SpiBus`. Wrap it with `embedded_hal_bus::spi::ExclusiveDevice` to obtain
//! a type that implements [`SpiDevice`]:
//!
//! ```rust,ignore
//! use linux_embedded_hal::SpidevBus;
//! use spidev::{Spidev, SpidevOptions, SpiModeFlags};
//! use embedded_hal_bus::spi::ExclusiveDevice;
//!
//! let mut spi = Spidev::open("/dev/spidev0.0")?;
//! spi.configure(
//!     &SpidevOptions::new()
//!         .max_speed_hz(1_000_000)
//!         .mode(SpiModeFlags::SPI_MODE_0)
//!         .build(),
//! )?;
//! let bus = SpidevBus(spi);  // SpidevBus implements SpiBus
//! let device = ExclusiveDevice::new_no_delay(bus, cs)?;
//! // `device` implements SpiDevice — pass it to chip driver constructors
//! ```
//!
//! `Cargo.toml` dependencies for Linux examples and tests:
//!
//! ```toml
//! linux-embedded-hal = "0.4"
//! spidev = "0.6"
//! embedded-hal = "1"
//! embedded-hal-bus = "0.2"
//! ```

/// The trait every SPI chip driver is generic over.
///
/// Re-exported from [`embedded_hal::spi::SpiDevice`]. Implementations include
/// CS management; chip drivers never assert or deassert CS themselves.
pub use embedded_hal::spi::SpiDevice;

/// Operation type for [`SpiDevice::transaction`] calls.
///
/// Re-exported from [`embedded_hal::spi::Operation`]. Use
/// `Operation::Write` followed by `Operation::Read` within a single
/// `transaction` call to keep CS asserted across both phases.
pub use embedded_hal::spi::Operation;

// ---------------------------------------------------------------------------
// Optional extensions (specs/transport_spi.md "Optional Extensions")
// ---------------------------------------------------------------------------

/// Optional SPI operations beyond the `SpiDevice` contract, needed by SD cards
/// in SPI mode (`idle_clocks`, `set_speed`, `select` / `deselect`).
///
/// `SpiDevice` cannot express them: the HAL owns CS, there is no way to clock
/// with CS high, and there is no run-time speed change. Drivers that need them
/// (SD, SD NAND) are generic over this trait instead; [`SpiManualCs`] builds
/// one from an `embedded-hal` [`SpiBus`](embedded_hal::spi::SpiBus) and an
/// [`OutputPin`](embedded_hal::digital::OutputPin) chip select.
pub trait SpiExtensions {
    /// Bus/pin error.
    type Error;

    /// Clock `n` bits of `0xFF` with CS deasserted (rounded up to whole bytes).
    fn idle_clocks(&mut self, n: u32) -> Result<(), Self::Error>;
    /// Change SCK to the highest supported frequency not above `hz`; false if unsupported.
    fn set_speed(&mut self, hz: u32) -> bool;
    /// Assert CS and hold it low across following `write`/`read` calls.
    fn select(&mut self) -> Result<(), Self::Error>;
    /// Release CS asserted by [`select`](Self::select).
    fn deselect(&mut self) -> Result<(), Self::Error>;
    /// Send bytes (CS stays low while selected).
    fn write(&mut self, data: &[u8]) -> Result<(), Self::Error>;
    /// Read `buf.len()` bytes, clocking `0xFF` on MOSI (CS stays low while selected).
    fn read(&mut self, buf: &mut [u8]) -> Result<(), Self::Error>;
}

/// Error of [`SpiManualCs`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ManualCsError<B, C> {
    /// SPI bus error.
    Bus(B),
    /// Chip-select pin error.
    Cs(C),
}

/// [`SpiExtensions`] over an `embedded-hal` `SpiBus` and a GPIO chip select.
///
/// CS idles high. Outside `select()`/`deselect()` every `write`/`read` asserts
/// CS for its own duration. `set_speed` calls the optional function given to
/// [`with_speed_setter`](Self::with_speed_setter), which reconfigures the
/// underlying bus (HAL specific); without it `set_speed` returns false.
pub struct SpiManualCs<BUS, CS> {
    bus: BUS,
    cs: CS,
    held: bool,
    speed: Option<fn(&mut BUS, u32) -> bool>,
}

impl<BUS, CS> SpiManualCs<BUS, CS>
where
    BUS: embedded_hal::spi::SpiBus,
    CS: embedded_hal::digital::OutputPin,
{
    /// Wrap `bus` and `cs` (driven high immediately).
    pub fn new(bus: BUS, mut cs: CS) -> Result<Self, ManualCsError<BUS::Error, CS::Error>> {
        cs.set_high().map_err(ManualCsError::Cs)?;
        Ok(Self { bus, cs, held: false, speed: None })
    }

    /// Provide the function that changes the bus clock (see the type docs).
    pub fn with_speed_setter(mut self, f: fn(&mut BUS, u32) -> bool) -> Self {
        self.speed = Some(f);
        self
    }

    /// Release the bus and CS pin.
    pub fn release(self) -> (BUS, CS) {
        (self.bus, self.cs)
    }

    fn cs_low(&mut self) -> Result<(), ManualCsError<BUS::Error, CS::Error>> {
        if self.held { return Ok(()); }
        self.cs.set_low().map_err(ManualCsError::Cs)
    }

    fn cs_high(&mut self) -> Result<(), ManualCsError<BUS::Error, CS::Error>> {
        if self.held { return Ok(()); }
        self.bus.flush().map_err(ManualCsError::Bus)?;
        self.cs.set_high().map_err(ManualCsError::Cs)
    }
}

impl<BUS, CS> SpiExtensions for SpiManualCs<BUS, CS>
where
    BUS: embedded_hal::spi::SpiBus,
    CS: embedded_hal::digital::OutputPin,
{
    type Error = ManualCsError<BUS::Error, CS::Error>;

    fn idle_clocks(&mut self, n: u32) -> Result<(), Self::Error> {
        self.cs.set_high().map_err(ManualCsError::Cs)?;
        let mut left = (n + 7) / 8;
        let ff = [0xFFu8; 16];
        while left > 0 {
            let chunk = left.min(ff.len() as u32) as usize;
            self.bus.write(&ff[..chunk]).map_err(ManualCsError::Bus)?;
            left -= chunk as u32;
        }
        self.bus.flush().map_err(ManualCsError::Bus)?;
        if self.held {
            self.cs.set_low().map_err(ManualCsError::Cs)?;
        }
        Ok(())
    }

    fn set_speed(&mut self, hz: u32) -> bool {
        match self.speed {
            Some(f) => f(&mut self.bus, hz),
            None => false,
        }
    }

    fn select(&mut self) -> Result<(), Self::Error> {
        self.cs.set_low().map_err(ManualCsError::Cs)?;
        self.held = true;
        Ok(())
    }

    fn deselect(&mut self) -> Result<(), Self::Error> {
        self.held = false;
        self.bus.flush().map_err(ManualCsError::Bus)?;
        self.cs.set_high().map_err(ManualCsError::Cs)
    }

    fn write(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        self.cs_low()?;
        self.bus.write(data).map_err(ManualCsError::Bus)?;
        self.cs_high()
    }

    fn read(&mut self, buf: &mut [u8]) -> Result<(), Self::Error> {
        self.cs_low()?;
        // MOSI idles high: SD cards read other levels as command traffic.
        for b in buf.iter_mut() {
            *b = 0xFF;
        }
        self.bus.transfer_in_place(buf).map_err(ManualCsError::Bus)?;
        self.cs_high()
    }
}

#[cfg(test)]
mod manual_cs_tests {
    use super::*;
    use embedded_hal_mock::eh1::digital::{Mock as PinMock, State, Transaction as PinTx};
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTx};

    #[test]
    fn read_clocks_ff_with_cs_framing() {
        let mut bus = SpiMock::new(&[SpiTx::transfer_in_place(vec![0xFF, 0xFF], vec![0x12, 0x34]), SpiTx::flush()]);
        let mut cs = PinMock::new(&[PinTx::set(State::High), PinTx::set(State::Low), PinTx::set(State::High)]);
        let mut dev = SpiManualCs::new(bus.clone(), cs.clone()).unwrap();
        let mut buf = [0u8; 2];
        dev.read(&mut buf).unwrap();
        assert_eq!(buf, [0x12, 0x34]);
        bus.done();
        cs.done();
    }

    #[test]
    fn select_holds_cs_across_calls_and_idle_clocks_release_it() {
        let mut bus = SpiMock::new(&[
            SpiTx::write_vec(vec![0x40]),
            SpiTx::transfer_in_place(vec![0xFF], vec![0x01]),
            SpiTx::write_vec(vec![0xFF; 10]),
            SpiTx::flush(),
            SpiTx::flush(),
        ]);
        // new(): high; select(): low (held, no toggles for write/read); idle_clocks(): high, then low again; deselect(): high
        let mut cs = PinMock::new(&[
            PinTx::set(State::High), PinTx::set(State::Low), PinTx::set(State::High),
            PinTx::set(State::Low), PinTx::set(State::High),
        ]);
        let mut dev = SpiManualCs::new(bus.clone(), cs.clone()).unwrap();
        dev.select().unwrap();
        dev.write(&[0x40]).unwrap();
        let mut b = [0u8; 1];
        dev.read(&mut b).unwrap();
        assert_eq!(b[0], 0x01);
        dev.idle_clocks(80).unwrap();
        dev.deselect().unwrap();
        assert!(!dev.set_speed(1_000_000)); // no speed setter provided
        bus.done();
        cs.done();
    }
}
