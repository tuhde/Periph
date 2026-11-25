//! UC1611s demo example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (Linux host).
//!
//! Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
//! Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc1611s::Uc1611sSpiFull;
use periph::connection::display::{DisplayConnection, NoPin, WireMode};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use std::{thread::sleep, time::Duration};
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

    let mut lcd = Uc1611sSpiFull::new_spi(connection, 240, 128, false, 0x8F).expect("init UC1611s"); // Create UC1611s driver, (connection, width=240, height=128, top_view=false, contrast=0x8F)

    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                                              // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 240, 128, true, false);                               // Draw rectangle, (x, y, w, h, on, fill)
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second, instead of the 8 of a full-frame show().
    let (mut bx, mut by, mut dx, mut dy) = (20i32, 12i32, 3i32, 2i32);
    for _frame in 0..200 {
        let oldy = by;
        lcd.draw_rect(bx, by, 8, 8, false, true);                             // Draw rectangle, (x, y, w, h, on, fill)
        bx += dx;
        by += dy;
        if bx < 2 || bx > 229 { dx = -dx; bx += 2 * dx; }
        if by < 2 || by > 118 { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 8, 8, true, true);                              // Draw rectangle, (x, y, w, h, on, fill)
        for page in (oldy.min(by) / 8)..=((oldy.max(by) + 7) / 8) {
            lcd.show_page(page as u8).expect("show_page");                    // Send one framebuffer page, (page 0..15) → ()
        }
        sleep(Duration::from_millis(20));
    }
    
    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for pm in (0x70..=0x8Fu8).rev().step_by(2).chain((0x70..=0x8Fu8).step_by(2)) {
        lcd.set_contrast(pm).expect("set_contrast");                          // Set contrast, (value 0..255) → ()
        sleep(Duration::from_millis(40));
    }
    
    // --- Flash the inverse display twice ---
    for _ in 0..2 {
        lcd.set_invert(true).expect("set_invert");                            // Invert display, (on) → ()
        sleep(Duration::from_millis(400));
        lcd.set_invert(false).expect("set_invert");                           // Invert display, (on) → ()
        sleep(Duration::from_millis(400));
    }
    
    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for line in (0..=32u8).chain((0..=32u8).rev()) {
        lcd.set_scroll_line(line).expect("set_scroll_line");                  // Scroll the image up, (line 0..159 - 2 * fixed) → ()
        sleep(Duration::from_millis(40));
    }
    
    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.set_write_pattern(1);                                                 // Select RAM screen to write, (pattern 0..3)
    lcd.fill(false);                                                          // Fill framebuffer, (on)
    lcd.draw_line(0, 0, 239, 127, true);                                      // Draw line, (x0, y0, x1, y1, on)
    lcd.draw_line(0, 127, 239, 0, true);                                      // Draw line, (x0, y0, x1, y1, on)
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
    lcd.set_display_pattern(1).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
    sleep(Duration::from_millis(2000));
    lcd.set_display_pattern(0).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
    lcd.set_write_pattern(0);                                                 // Select RAM screen to write, (pattern 0..3)
    sleep(Duration::from_millis(1500));
    
    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    lcd.sleep().expect("sleep");                                              // Enter sleep, () → ()
    println!("done");
}
