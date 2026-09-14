use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::Mpr121Full;
use std::thread::sleep;
use std::time::Duration;

static NOTES: [&str; 12] = [
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4",
];

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Mpr121Full::new(dev, addr, &mut delay).expect("init");         // Construct MPR121 Full, (i2c, addr=0x5A, delay) → Result<Mpr121Full, _>
    let mut previous: u16 = 0;

    loop {
        let mask = chip.touched().expect("touched");                                // Read 12-bit touch bitmask, () → Result<u16, _> bitmask
        let newly_pressed = mask & !previous;
        let newly_released = !mask & previous;
        for n in 0..12u8 {
            if newly_pressed & (1u16 << n) != 0 {
                println!("NOTE ON:  {}", NOTES[n as usize]);
            }
            if newly_released & (1u16 << n) != 0 {
                println!("NOTE OFF: {}", NOTES[n as usize]);
            }
        }
        previous = mask;
        sleep(Duration::from_millis(50));
    }
}
