// Demo: an adaptive backlight controller. Arms an interrupt window of +-30 %
// around the current illuminance; whenever INT_STATUS reports that the light
// left the window for 2 s it maps the new level logarithmically to a backlight
// level of 10-100 %, re-centres the window and re-arms it. Prints the exponent /
// mantissa once a second and runs for 60 s.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{MAX44009Full, MAX44009_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = MAX44009Full::new(dev, MAX44009_I2C_ADDRESS); // Create MAX44009 Full driver, (i2c, addr=0x4A)
    sensor.init(&mut Delay).expect("init");                        // Run initialization sequence, (delay) → ()

    let backlight = |lx: f32| 10.0 + 90.0 * (lx.clamp(0.1, 10000.0).log10() + 1.0) / 5.0;

    println!("config {:?}", sensor.get_config().expect("get_config")); // Read CONFIG, () → MAX44009Config
    let lux = sensor.lux().expect("lux"); // Read illuminance, () → f32 lux
    println!("lux {:.2}, resolution {:.3} lx/LSB", lux, sensor.resolution()); // Lux per LSB, () → f32

    sensor.set_threshold_delay(2.0).expect("delay"); // Set the threshold delay, (seconds) → ()
    sensor.set_thresholds(lux * 0.7, lux * 1.3).expect("window"); // Set the interrupt window, (low_lux, high_lux) → Result
    sensor.enable_interrupt().expect("enable"); // Enable the interrupt, () → ()
    println!("backlight {:.0} %", backlight(lux));

    for _ in 0..60 {
        if sensor.poll_interrupt().expect("status") != 0 { // Read and clear INT_STATUS, () → u8
            let now = sensor.lux().expect("lux");
            println!("lux {:.2} -> backlight {:.0} %", now, backlight(now));
            sensor.set_thresholds(now * 0.7, now * 1.3).expect("window"); // re-centre the window
        }
        let (e, m, _over) = sensor.read_raw().expect("raw"); // Read exponent and mantissa, () → (u8, u8, bool)
        println!("E={} M={} overrange={}", e, m, sensor.is_overrange()); // Query the last reading, () → bool
        sleep(Duration::from_secs(1));
    }

    sensor.disable_interrupt().expect("disable"); // Disable the interrupt, () → ()
}
