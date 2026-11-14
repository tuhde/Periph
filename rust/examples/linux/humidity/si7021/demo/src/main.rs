//! Demo for the Si7021 — condensation-aware hygrometer: device ID, dew point, resolutions and heater test.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::Si7021Full;

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

    // Create Si7021 driver, (i2c, addr=0x40) → Si7021
    let mut chip = Si7021Full::new(dev, addr);

    // Software reset, (delay) → Result<()>
    chip.software_reset(&mut delay).expect("software_reset");
    // Device type, () → Result<u8> ; Electronic serial number, () → Result<u64> ; Firmware revision, () → Result<u8>
    println!("device type 0x{:02X}", chip.device_type().expect("device_type"));
    println!("serial number 0x{:016X}", chip.serial_number().expect("serial_number"));
    println!("firmware 0x{:02X}", chip.firmware_revision().expect("firmware_revision"));
    // Supply low flag, () → Result<bool>
    println!("VDD low {}", chip.vdd_low().expect("vdd_low"));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for _ in 0..15 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Compute dew point, (delay) → Result<f32> °C
        println!("{:.2} C {:.1} %RH dew point {:.2} C", t, rh, chip.dew_point(&mut delay).expect("dew_point"));
        sleep_ms!(2000);
    }

    // --- Value accessors and raw codes ---
    // () → Result<f32> ; () → Result<f32> ; () → Result<f32>
    println!("RH {:.1} T {:.2} T of last RH {:.2}",
        chip.read_humidity(&mut delay).expect("humidity"),
        chip.read_temperature(&mut delay).expect("temperature"),
        chip.read_temperature_previous().expect("previous"));
    // () → Result<u16> ; () → Result<u16>
    println!("raw {} {}", chip.read_raw_humidity(&mut delay).expect("raw rh"), chip.read_raw_temperature(&mut delay).expect("raw t"));
    // Use hold-master commands, (on) → ()
    chip.set_clock_stretching(false);
    // Read USER_REG_1, () → Result<u8> ; Write USER_REG_1, (value) → Result<()>
    let reg = chip.read_user_register().expect("read_user_register");
    chip.write_user_register(reg).expect("write_user_register");

    // --- Resolution comparison ---
    for (rh_bits, t_bits) in [(12u8, 14u8), (10, 13), (11, 11), (8, 12)] {
        // Set resolution, (rh_bits, temperature_bits) → Result<()>
        chip.set_resolution(rh_bits, t_bits).expect("set_resolution");
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Typical conversion time, () → Result<f32> s ; Get resolution, () → Result<(u8, u8)>
        println!("{}/{} bit: {:.2} C {:.1} %RH, typ. {:.1} ms, resolution {:?}", rh_bits, t_bits, t, rh,
            chip.conversion_time().expect("conversion_time") * 1000.0, chip.get_resolution().expect("get_resolution"));
    }
    chip.set_resolution(12, 14).expect("set_resolution");

    // --- Heater test: 10 s ---
    let rh_before = chip.read_humidity(&mut delay).expect("humidity");
    let t_before = chip.read_temperature(&mut delay).expect("temperature");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    println!("RH drop {:.1} %RH, temperature rise {:.2} K",
        rh_before - chip.read_humidity(&mut delay).expect("humidity"),
        chip.read_temperature(&mut delay).expect("temperature") - t_before);
    chip.set_heater(false).expect("heater off");
}
