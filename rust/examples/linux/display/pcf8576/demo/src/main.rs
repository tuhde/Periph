use linux_embedded_hal::I2cdev;
use periph::chips::display::{Pcf8576Full, SEVEN_SEG};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once per
    // second and the terminal mirrors the value sent to the display.
    let mut lcd = Pcf8576Full::new(dev, addr).expect("init PCF8576"); // Create PCF8576 driver, (i2c, addr=0x38)

    for n in (0..=9999u16).rev() {
        let d0 = ((n / 1000) % 10) as usize;
        let d1 = ((n / 100) % 10) as usize;
        let d2 = ((n / 10) % 10) as usize;
        let d3 = (n % 10) as usize;
        let out: [u8; 4] = [
            SEVEN_SEG[d0], // Encode 7-segment digit, (digit 0–9) → u8
            SEVEN_SEG[d1], // Encode 7-segment digit, (digit 0–9) → u8
            SEVEN_SEG[d2], // Encode 7-segment digit, (digit 0–9) → u8
            SEVEN_SEG[d3], // Encode 7-segment digit, (digit 0–9) → u8
        ];
        lcd.write_raw(0, &out).expect("write_raw"); // Write all four digits, (address 0, 4 bytes) → ()
        println!("countdown: {:04}", n);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    // --- Stop indicator: light only the middle segments (g) on every digit ---
    // When the counter reaches zero we replace the "0000" pattern with "----" to
    // signal that the demo has finished. Each digit's g segment is bit 1, so a
    // 0x02 byte lights just the bar across the middle.
    let dash: [u8; 4] = [0x02, 0x02, 0x02, 0x02];
    lcd.write_raw(0, &dash).expect("write_raw"); // Write dash pattern, (address 0, 4 bytes) → ()
    println!("countdown complete");
}
