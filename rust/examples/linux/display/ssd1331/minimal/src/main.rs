//! SSD1331 minimal example: Waveshare 0.95inch RGB OLED on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::ssd1331::{Ssd1331Minimal, BUFFER_SIZE, BLACK, BLUE, CYAN, GREEN, MAGENTA, RED, WHITE, YELLOW};
use periph::connection::display::{DisplayConnection, NoPin, WireMode};
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "ssd1331").unwrap()).unwrap(); // Create D/C# pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "ssd1331").unwrap()).unwrap(); // Create RES# pin, (line=17) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy=None, delay, wire_mode)

    let mut fb = [0u8; BUFFER_SIZE];
    let mut oled = Ssd1331Minimal::new(connection, &mut fb).unwrap(); // Create SSD1331 driver, (connection, buffer) → Ssd1331Minimal

    let bars = [WHITE, YELLOW, CYAN, GREEN, MAGENTA, RED, BLUE, BLACK];
    for y in 0..oled.height() as i32 {
        for x in 0..oled.width() as i32 {
            oled.set_pixel(x, y, bars[(x / 12) as usize]);                              // Set one pixel, (x, y, color)
        }
    }
    for x in 0..oled.width() as i32 {
        oled.set_pixel(x, 0, WHITE);                                                    // Set one pixel, (x, y, color)
        oled.set_pixel(x, oled.height() as i32 - 1, WHITE);                             // Set one pixel, (x, y, color)
    }
    oled.show().unwrap();                                                               // Send framebuffer, () → Result
}
