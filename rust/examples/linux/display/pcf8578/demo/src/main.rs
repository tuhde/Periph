use std::thread::sleep;
use std::time::Duration;

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::pcf8578::{ACCESS_CHARACTER, DISPLAY_INVERSE, DISPLAY_NORMAL};
use periph::chips::display::Pcf8578Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Initialise a 16 x 24 dot-matrix panel ---
    // 1:16 multiplex gives 16 rows and 24 columns; the constructor clears the RAM
    // before switching the display on so no random start-up pattern is shown.
    let mut lcd = Pcf8578Full::new(dev, addr, 16, &mut Delay).expect("init PCF8578"); // Create PCF8578 driver, (i2c, addr, rows=16, delay)

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    lcd.draw_rect(0, 0, 24, 16, true, false).expect("rect"); // Draw rectangle outline, (x, y, w, h, on, fill) → ()
    lcd.draw_line(0, 0, 23, 15, true).expect("line"); // Draw line, (x0, y0, x1, y1, on) → ()
    lcd.draw_line(0, 15, 23, 0, true).expect("line"); // Draw line, (x0, y0, x1, y1, on) → ()

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for _ in 0..2 {
        lcd.set_display(DISPLAY_INVERSE).expect("display"); // Set display status, (status 0–3) → ()
        sleep(Duration::from_millis(400));
        lcd.set_display(DISPLAY_NORMAL).expect("display"); // Set display status, (status 0–3) → ()
        sleep(Duration::from_millis(400));
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    let patterns = [0x55u8, 0x33, 0x0F, 0xFF];
    for (bank, p) in patterns.iter().enumerate() {
        lcd.write_ram(16, bank, &[*p; 24], ACCESS_CHARACTER).expect("write ram"); // Write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → ()
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for bank in 0..4u8 {
        lcd.set_start_bank(bank).expect("start bank"); // Set start bank, (bank 0–3) → ()
        let mut first = [0u8; 1];
        lcd.read_ram(16, bank as usize, &mut first, ACCESS_CHARACTER).expect("read ram"); // Read raw RAM, (x 0–39, bank 0–3, buf, mode) → ()
        println!("start bank {} first column byte 0x{:02X}", bank, first[0]);
        sleep(Duration::from_secs(1));
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // RAM columns 0-7 are never displayed at 1:16, so they serve as scratch
    // storage; the chip supports reads in mixed mode, so the bytes can be verified.
    lcd.set_start_bank(0).expect("start bank"); // Set start bank, (bank 0–3) → ()
    lcd.write_ram(0, 0, &[1, 2, 3, 4, 5, 6, 7, 8], ACCESS_CHARACTER).expect("write ram"); // Write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → ()
    let mut back = [0u8; 8];
    lcd.read_ram(0, 0, &mut back, ACCESS_CHARACTER).expect("read ram"); // Read raw RAM, (x 0–39, bank 0–3, buf, mode) → ()
    println!("{:?}", back);
}
