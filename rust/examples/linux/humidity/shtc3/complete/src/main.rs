//! Complete example for the SHTC3 — every SHTC3Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::SHTC3Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x70);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SHTC3 driver, (i2c, addr=0x70) → SHTC3
    let mut chip = SHTC3Full::new(dev, addr);
    // Soft reset and ID check, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>; normal mode, both CRCs verified, one retry
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32 %RH>
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("rh"));
    // Read temperature only, (delay) → Result<f32 °C>
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("t"));
    // Read raw codes, (delay) → Result<(u16, u16)>; (raw_temperature, raw_humidity)
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));
    // Read the ID word, (delay) → Result<u16>; (id & 0x083F) == 0x0807 for the SHTC3
    println!("id 0x{:04X}", chip.read_id(&mut delay).expect("id"));

    // Select low-power mode, (on) → ()
    // 0.7 ms measurement, slightly noisier
    chip.set_low_power(true);
    println!("low power {:?}", chip.read(&mut delay).expect("read"));
    chip.set_low_power(false);

    // Select clock stretching, (on) → (); needs a controller that tolerates SCL held low
    chip.set_clock_stretching(true);
    println!("stretching {:?}", chip.read(&mut delay).expect("read"));
    chip.set_clock_stretching(false);

    // Sleep after every read?, (on) → (); off keeps the sensor idle (higher supply current)
    chip.set_sleep_between(false);
    println!("no sleep {:?}", chip.read(&mut delay).expect("read"));
    chip.set_sleep_between(true);
    // Wake-up command, (delay) → Result<()>
    chip.wake(&mut delay).expect("wake");
    // Sleep command, () → Result<()>
    chip.sleep().expect("sleep");
    // Compute dew point, (delay) → Result<f32 °C>; one measurement, Magnus formula
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew"));
    // Soft reset, (delay) → Result<()>; 0x805D, leaves the sensor idle
    chip.soft_reset(&mut delay).expect("reset");
}
