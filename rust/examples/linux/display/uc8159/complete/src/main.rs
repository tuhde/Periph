//! UC8159 complete example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9).
//! Each refresh blocks for about 30 s.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc8159::{Uc8159Full, BLACK, BLUE, GREEN, ORANGE, RED, WHITE};
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
    spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::HalfDuplexPin).unwrap(); // Create display connection, (spi, dc, res, busy, delay, wire_mode)

    let mut fb = vec![0u8; periph::chips::display::uc8159::FRAME_BYTES];
    let mut epd = Uc8159Full::new(connection, &mut fb, Some(60_000)).unwrap(); // Create UC8159 driver, (connection, buffer, busy_timeout_ms) → Uc8159Full
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
                                                                                         // every pixel set to white
    epd.set_pixel(5, 5, BLACK);                                                      // Set one pixel, (x, y, color)
                                                                                         // colour code 0..7
    epd.draw_rect(20, 20, 200, 120, RED, true);                                      // Draw rectangle, (x, y, w, h, color, fill)
                                                                                         // filled red block
    epd.draw_rect(240, 20, 200, 120, BLUE, false);                                   // Draw rectangle, (x, y, w, h, color, fill)
                                                                                         // blue outline
    epd.draw_line(0, 0, 599, 447, GREEN);                                            // Draw line, (x0, y0, x1, y1, color)
                                                                                         // Bresenham diagonal
    epd.blit(460, 20, &[0xFF, 0x81, 0x81, 0xFF], 8, 4, ORANGE);                      // Copy 1-bpp bitmap, (x, y, data, w, h, color)
                                                                                         // 8 x 4 box, MSB first
    let colour = epd.get_pixel(30, 30);                                              // Read framebuffer pixel, (x, y) → u8 colour code
                                                                                         // RED here
    epd.set_flip(false, false).unwrap();                                             // Mirror output, (horizontal, vertical)
                                                                                         // PSR.SHL / UD at power-on values
    epd.set_frame_rate(0x3C).unwrap();                                               // Set PLL frame rate code, (code)
                                                                                         // 50 Hz, the vendor value
    epd.set_interval(7, WHITE, 1).unwrap();                                          // Set VCOM/data interval, (cdi 0–15, border, polarity)
                                                                                         // white border, 10 hsync
    epd.set_temperature_sensor(false, 0).unwrap();                                   // Select temperature sensor, (external, offset -8..7 °C)
                                                                                         // internal sensor, no offset
    let status = epd.read_status().unwrap();                                         // Read FLG, () → u8 bitfield
                                                                                         // needs DOUT wired (half-duplex)
    let temperature = epd.read_temperature().unwrap();                               // Read sensor, () → i8 °C
                                                                                         // signed internal temperature
    epd.show().unwrap();                                                             // Refresh panel, () → Result
                                                                                         // DTM1, PON, DRF, POF; takes about 30 s
    let ready = epd.wait_busy(Some(1000)).unwrap();                                  // Wait for BUSY high, (timeout_ms) → bool
                                                                                         // true when the controller is idle
    epd.send_command(0x50, &[0x37]).unwrap();                                        // Send raw command, (command, data)
                                                                                         // rewrites CDI with the vendor value
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
    epd.wake().unwrap();                                                             // Reset and re-initialise, () → Result
                                                                                         // framebuffer is kept
    println!("colour={} status={} temperature={} ready={}", colour, status, temperature, ready);
    println!("===DONE: 0 passed, 0 failed===");
}
