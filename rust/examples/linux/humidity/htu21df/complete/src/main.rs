//! Complete example for the HTU21D-F — every HTU21DFFull method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTU21DFFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HTU21D-F driver, (i2c, addr=0x40) → HTU21DF
    let mut chip = HTU21DFFull::new(dev, addr);

    // Soft reset, (delay) → Result<()>; 15 ms power-up wait, soft reset, 15 ms
    chip.init(&mut delay).expect("init");
    // Read temperature and humidity, (delay) → Result<(f32, f32)> °C, %RH; no hold master, CRC checked
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32> %RH; not clamped
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read temperature only, (delay) → Result<f32> °C
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Temperature-compensated humidity, (delay) → Result<f32> %RH; -0.15 %RH/°C relative to 25 °C
    println!("compensated humidity {:.1} %RH", chip.read_humidity_compensated(&mut delay).expect("compensated"));
    // Read raw codes, (delay) → Result<(u16, u16)>; (temperature, humidity), status bits cleared
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));

    // Set resolution, (res) → Result<()>; 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
    chip.set_resolution(3).expect("set_resolution");
    // Get resolution, () → Result<u8>
    println!("resolution {}", chip.get_resolution().expect("get_resolution"));
    chip.set_resolution(0).expect("set_resolution");

    // Switch the heater, (on) → Result<()>; about 5.5 mW, readings drift while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.heater().expect("heater"));
    chip.set_heater(false).expect("heater off");
    // End-of-battery flag, () → Result<bool>; true when VDD is below about 2.25 V
    println!("battery low {}", chip.battery_low().expect("battery_low"));
    // Read the user register, () → Result<u8>; 0x02 after reset
    println!("user register 0x{:02X}", chip.read_user_register().expect("read_user_register"));
    // Use hold-master commands, (on) → (); needs bus-layer clock stretching; off polls the NACKed read header
    chip.set_hold_master(false);
    // Compute dew point, (delay) → Result<f32> °C; one measurement
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
    // Soft reset, (delay) → Result<()>; 0xFE, waits 15 ms; the heater bit survives
    chip.soft_reset(&mut delay).expect("soft_reset");
}
