//! UC8159 demo example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! Each refresh blocks for about 30 s.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc8159::{Uc8159Full, BLACK, WHITE};
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
    let mut epd = Uc8159Full::new(connection, &mut fb, Some(60_000)).unwrap(); // Create UC8159 driver, (connection, buffer, busy_timeout_ms) → Uc8159Full

    // --- Compose the colour chart in the framebuffer ---
    // White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
    // one-pixel black frame inset by 4 px (a frame on the very edge is hidden by the bezel).
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
    for c in 0..7 {
        epd.draw_rect(20 + c * 80, 40, 80, 368, c as u8, true);                      // Draw rectangle, (x, y, w, h, color, fill)
    }
    epd.draw_rect(4, 4, 592, 440, BLACK, false);                                     // Draw rectangle, (x, y, w, h, color, fill)

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
    let start = std::time::Instant::now();
    epd.show().unwrap();                                                             // Refresh panel, () → Result
    println!("refresh took {:?}", start.elapsed());

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
}
