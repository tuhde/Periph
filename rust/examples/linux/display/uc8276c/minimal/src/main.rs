//! UC8276C minimal example: Waveshare 4.2inch e-Paper (B) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! Each refresh blocks for about 17 s.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc8276c::{Uc8276cMinimal, BLACK, WHITE, RED};
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "uc8276c").unwrap()).unwrap(); // Create D/C# pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "uc8276c").unwrap()).unwrap(); // Create RES# pin, (line=17) → CdevPin
    let busy = CdevPin::new(chip.get_line(24).unwrap().request(LineRequestFlags::INPUT, 0, "uc8276c").unwrap()).unwrap(); // Create BUSY_N pin, (line=24) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy, delay, wire_mode)

    let mut bw = [0u8; 15000];
    let mut red = [0u8; 15000];
    let mut epd = Uc8276cMinimal::new(connection, &mut bw, Some(&mut red), 400, 300, Some(30_000)).unwrap(); // Create UC8276C driver, (connection, bw, red, width, height, busy_timeout_ms) → Uc8276cMinimal
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
    epd.set_pixel(10, 10, BLACK);                                                    // Set one pixel, (x, y, color)
    for x in 20..120 {
        epd.set_pixel(x, 40, RED);                                                   // Set one pixel, (x, y, color)
    }
    epd.show().unwrap();                                                             // Refresh panel (about 17 s), () → Result
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
}
