use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::pcf8579::{ACCESS_CHARACTER, ACCESS_FULL_GRAPHIC, DISPLAY_INVERSE, DISPLAY_NORMAL};
use periph::chips::display::Pcf8579Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Pcf8579Full::new(dev, addr, 0, 16, false, &mut Delay).expect("init PCF8579"); // Create PCF8579 driver, (i2c, addr=0x3C, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false, delay)
    // clears this device's RAM and switches the display on

    lcd.clear().expect("clear"); // Clear display RAM, () → ()
    // zeroes all 4 banks x 40 columns and the shadow
    lcd.set_pixel(1, 1, true).expect("pixel"); // Set one pixel, (x 0–39, y 0–15, on) → ()
    // writes a single RAM byte from the shadow
    lcd.write_columns(4, 0, &[0xFF, 0x81, 0xFF]).expect("columns"); // Write column bytes, (x 0–39, bank 0–1, data bytes) → ()
    // character access mode, bit 0 = top row of the bank
    let _on = lcd.get_pixel(1, 1); // Read pixel from shadow, (x, y) → bool
    // no bus traffic
    lcd.fill(true).expect("fill"); // Fill display, (on) → ()
    // every displayed pixel on
    lcd.fill(false).expect("fill"); // Fill display, (on) → ()
    // every displayed pixel off
    lcd.draw_line(0, 0, 39, 15, true).expect("line"); // Draw line, (x0, y0, x1, y1, on) → ()
    // Bresenham, written as one batch of changed bytes
    lcd.draw_rect(0, 0, 40, 16, true, false).expect("rect"); // Draw rectangle outline, (x, y, w, h, on, fill) → ()
    // border around the whole panel
    lcd.draw_rect(8, 4, 8, 8, true, true).expect("rect"); // Draw filled rectangle, (x, y, w, h, on, fill) → ()
    // solid 8x8 block in the middle
    lcd.show_bitmap(2, 8, &[0x18, 0x24, 0x42, 0x81]).expect("bitmap"); // Blit bitmap, (x, y multiple of 8, column bytes) → ()
    // one 8-pixel-high band at the lower bank
    lcd.set_display(DISPLAY_INVERSE).expect("display"); // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → ()
    // inverse video; RAM is preserved
    lcd.set_display(DISPLAY_NORMAL).expect("display"); // Set display status, (status 0–3) → ()
    // back to normal video
    lcd.set_mode(24, false).expect("mode"); // Set multiplex rate, (rows 8/16/24/32, mixed=false) → ()
    // 1:24 drive; mixed is the PCF8578 mode bit T
    lcd.set_mode(16, false).expect("mode"); // Set multiplex rate, (rows 8/16/24/32, mixed=false) → ()
    // back to 1:16
    lcd.set_start_bank(1).expect("start bank"); // Set start bank, (bank 0–3) → ()
    // shows RAM bank 1 at the top (hardware scroll)
    lcd.scroll(-1).expect("scroll"); // Scroll by banks, (banks i32) → ()
    // adds -1 to the start bank modulo banks in use
    lcd.write_ram(16, 0, &[0xAA, 0x55], ACCESS_CHARACTER).expect("write ram"); // Write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → ()
    // character order at RAM column 16
    let mut buf = [0u8; 2];
    lcd.read_ram(16, 0, &mut buf, ACCESS_CHARACTER).expect("read ram"); // Read raw RAM, (x 0–39, bank 0–3, buf, mode) → ()
    // address-set commands, repeated start, then a read
    lcd.write_ram_cascade(&[0u8; 160], ACCESS_FULL_GRAPHIC).expect("cascade"); // Write across devices, (data bytes, mode) → ()
    // runs on into the following subaddresses after column 39
    lcd.reset().expect("reset"); // Re-initialise, () → ()
    // clears RAM and restores normal video
}
