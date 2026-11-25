#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::pcf8578::{ACCESS_CHARACTER, DISPLAY_INVERSE, DISPLAY_NORMAL};
use periph::chips::display::Pcf8578Full;

esp_app_desc!();

const ADDR: u8 = 0x3C;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    // PCF8578 supports 100 kHz at most
    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(esp_hal::time::Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut lcd = Pcf8578Full::new(i2c, ADDR, 16, &mut delay).expect("init PCF8578"); // Create PCF8578 driver, (i2c, addr=0x3C, rows=16 8/16/24/32, delay)
    // clears RAM and switches the display on in mixed mode

    lcd.clear().expect("clear"); // Clear display RAM, () → ()
    // zeroes all 4 banks x 40 columns and the shadow
    lcd.set_pixel(1, 1, true).expect("pixel"); // Set one pixel, (x 0–23, y 0–15, on) → ()
    // writes a single RAM byte from the shadow
    lcd.write_columns(4, 0, &[0xFF, 0x81, 0xFF]).expect("columns"); // Write column bytes, (x 0–23, bank 0–1, data bytes) → ()
    // character access mode, bit 0 = top row of the bank
    let _on = lcd.get_pixel(1, 1); // Read pixel from shadow, (x, y) → bool
    // no bus traffic
    lcd.fill(true).expect("fill"); // Fill display, (on) → ()
    // every displayed pixel on
    lcd.fill(false).expect("fill"); // Fill display, (on) → ()
    // every displayed pixel off
    lcd.draw_line(0, 0, 23, 15, true).expect("line"); // Draw line, (x0, y0, x1, y1, on) → ()
    // Bresenham, written as one batch of changed bytes
    lcd.draw_rect(0, 0, 24, 16, true, false).expect("rect"); // Draw rectangle outline, (x, y, w, h, on, fill) → ()
    // border around the whole panel
    lcd.draw_rect(8, 4, 8, 8, true, true).expect("rect"); // Draw filled rectangle, (x, y, w, h, on, fill) → ()
    // solid 8x8 block in the middle
    lcd.show_bitmap(2, 8, &[&[0x18, 0x24, 0x42, 0x81]]).expect("bitmap"); // Blit bitmap, (x, y multiple of 8, bands of column bytes) → ()
    // one 8-pixel-high band at the lower bank
    lcd.set_display(DISPLAY_INVERSE).expect("display"); // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → ()
    // inverse video; RAM is preserved
    lcd.set_display(DISPLAY_NORMAL).expect("display"); // Set display status, (status 0–3) → ()
    // back to normal video
    lcd.set_mode(24, true).expect("mode"); // Set multiplex rate, (rows 8/16/24/32, mixed) → ()
    // 1:24 drive; mixed mode keeps RAM accessible
    lcd.set_mode(16, true).expect("mode"); // Set multiplex rate, (rows 8/16/24/32, mixed) → ()
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
    lcd.device_select(0).expect("device select"); // Select device subaddress, (subaddress 0–15) → ()
    // 0 addresses this PCF8578; others target cascaded PCF8579s
    lcd.reset().expect("reset"); // Re-initialise, () → ()
    // clears RAM and restores normal video
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
