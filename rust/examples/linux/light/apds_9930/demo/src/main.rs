// Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9930Full;
use std::thread::sleep;
use std::time::Duration;

const DIM_LUX_THRESHOLD: f32 = 10.0;
const PROX_SCREEN_OFF: u16 = 400;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Apds9930Full::new(dev, addr, &mut delay).expect("init");       // Construct APDS-9930 Full, (i2c, addr=0x39, delay) → Result<Apds9930Full, _>
                                                                                    // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

    sleep(Duration::from_millis(110));

    // --- Sample lux and proximity once per second for 30 cycles ---
    // The user is encouraged to cover the sensor with a hand (proximity
    // rises) and to dim/undim the room light to watch both action lines
    // fire.
    for _ in 0..30 {
        sleep(Duration::from_secs(1));
        let lx = chip.lux().expect("lux");                                           // Read ambient illuminance, () → Result<f32, _> lx
                                                                                    // IR-compensated lux via Ch0/Ch1 difference
        let p  = chip.proximity().expect("proximity");                               // Read proximity count, () → Result<u16, _> count
                                                                                    // 16-bit ADC value; higher = closer
        println!("lux={:.1} lx  proximity={}", lx, p);
        if lx < DIM_LUX_THRESHOLD {
            println!("  -> dim backlight");
        }
        if p > PROX_SCREEN_OFF {
            println!("  -> disable screen");
        }
    }
}