//! UC1611s minimal example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (Linux host).
//!
//! Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
//! Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Minimal use: a border and a diagonal on the 240 x 128 panel.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc1611s::Uc1611sSpiMinimal;
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "uc1611s").unwrap()).unwrap(); // Create CD (D/C) pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "uc1611s").unwrap()).unwrap(); // Create RST pin, (line=17) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy=None, delay, wire_mode)

    let mut lcd = Uc1611sSpiMinimal::new_spi(connection, 240, 128).expect("init UC1611s"); // Create UC1611s driver, (connection, width=240, height=128)

    lcd.clear();                                                              // Clear framebuffer, ()
    for x in 0..240 {
        lcd.set_pixel(x, 0, true);                                            // Set pixel, (x 0..239, y 0..127, on)
        lcd.set_pixel(x, 127, true);                                          // Set pixel, (x 0..239, y 0..127, on)
        lcd.set_pixel(x, x * 127 / 239, true);                                // Set pixel, (x 0..239, y 0..127, on)
    }
    for y in 0..128 {
        lcd.set_pixel(0, y, true);                                            // Set pixel, (x 0..239, y 0..127, on)
        lcd.set_pixel(239, y, true);                                          // Set pixel, (x 0..239, y 0..127, on)
    }
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
}
