//! UC8159 minimal example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! Each refresh blocks for about 30 s.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc8159::{Uc8159Minimal, WHITE};
use periph::connection::display::{DisplayConnection, WireMode};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

// Dummy CS: spidev manages CS# in the kernel.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "uc8159").unwrap()).unwrap(); // Create D/C# pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "uc8159").unwrap()).unwrap(); // Create RES# pin, (line=17) → CdevPin
    let busy = CdevPin::new(chip.get_line(24).unwrap().request(LineRequestFlags::INPUT, 0, "uc8159").unwrap()).unwrap(); // Create BUSY pin, (line=24) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy, delay, wire_mode)

    let mut fb = vec![0u8; periph::chips::display::uc8159::FRAME_BYTES];
    let mut epd = Uc8159Minimal::new(connection, &mut fb, Some(60_000)).unwrap(); // Create UC8159 driver, (connection, buffer, busy_timeout_ms) → Uc8159Minimal
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
    for x in 0..600 {
        let colour = (x * 7 / 600) as u8;                                            // seven vertical bars: black .. orange
        for y in 0..448 {
            epd.set_pixel(x, y, colour);                                             // Set one pixel, (x, y, color)
        }
    }
    epd.show().unwrap();                                                             // Refresh panel (about 30 s), () → Result
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
}
