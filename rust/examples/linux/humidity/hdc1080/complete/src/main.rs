//! Complete example for the HDC1080 — every HDC1080Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HDC1080Full;

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

    // Create HDC1080 driver, (i2c, addr=0x40) → HDC1080
    let mut chip = HDC1080Full::new(dev, addr);

    // Write default configuration, () → Result<()>; 0x1000: 14-bit T and RH, sequence mode, heater off
    chip.init().expect("init");
    // Read the 40-bit serial ID, () → Result<u64>
    println!("serial id 0x{:010X}", chip.serial_id().expect("serial_id"));
    // Read the configuration register, () → Result<u16>
    println!("config 0x{:04X}", chip.read_config().expect("read_config"));
    // Read temperature and humidity, (delay) → Result<(f32, f32)> °C, %RH; sequence mode
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read temperature only, (delay) → Result<f32> °C; single mode
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Read humidity only, (delay) → Result<f32> %RH; single mode
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read raw words, (delay) → Result<(u16, u16)>; (temperature, humidity), bits 1:0 zero
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));

    // Set resolution, (temperature_bits, humidity_bits) → Result<()>; T 14/11 bit, RH 14/11/8 bit
    chip.set_resolution(11, 8).expect("set_resolution");
    // Conversion time, () → f32 s; typical T + RH time
    println!("conversion time {:.2} ms", chip.conversion_time() * 1000.0);
    chip.set_resolution(14, 14).expect("set_resolution");

    // Switch the heater, (on) → Result<()>; runs only during measurements, readings drift while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    chip.set_heater(false).expect("heater off");
    // Battery flag, () → Result<bool>; true when VDD is below 2.8 V
    println!("battery low {}", chip.battery_low().expect("battery_low"));
    // Software reset, (delay) → Result<()>; sets RST, waits 15 ms, rewrites the stored configuration
    chip.software_reset(&mut delay).expect("software_reset");

    // Manufacturer ID, () → Result<u16>; 0x5449 = Texas Instruments
    println!("manufacturer 0x{:04X}", chip.manufacturer_id().expect("manufacturer_id"));
    // Device ID, () → Result<u16>; 0x1050 = HDC1080
    println!("device 0x{:04X}", chip.device_id().expect("device_id"));
}
