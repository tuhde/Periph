//! Complete example for the SHT31 — every Sht31Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::{Repeatability, Sht31Full};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x44);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SHT31 driver, (i2c, addr=0x44, delay) → Result
    let mut chip = Sht31Full::new(dev, addr, &mut delay).expect("init");

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
    // single shot, high repeatability, CRC checked
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read temperature only, (delay) → Result<f32> °C
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Read humidity only, (delay) → Result<f32> %RH
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read raw words, (delay) → Result<(u16, u16)>
    // 16-bit temperature and humidity ticks
    let (raw_t, raw_rh) = chip.read_raw(&mut delay).expect("raw");
    println!("raw {} {}", raw_t, raw_rh);

    // Select repeatability, (level) → ()
    // Low / Medium / High (4 / 6 / 15 ms)
    chip.set_repeatability(Repeatability::Low);
    println!("{:?}", chip.read(&mut delay).expect("read low"));
    chip.set_repeatability(Repeatability::High);
    // Use clock-stretching commands, (on) → ()
    // needs bus-layer support; off polls the NACKed read header
    chip.set_clock_stretching(false);

    // Start periodic mode, (rate, repeatability, art) → Result<()>
    // rate 0.5 / 1 / 2 / 4 / 10 mps; art = true selects the 4 mps ART mode
    chip.start_periodic(2.0, Repeatability::High, false).expect("start_periodic");
    sleep_ms!(1000);
    // Fetch the latest periodic result, () → Result<Option<(f32, f32)>>
    // None while the read header is NACKed
    println!("{:?}", chip.fetch().expect("fetch"));
    // Stop periodic mode, (delay) → Result<()>
    // break command 0x3093, waits 1 ms
    chip.stop_periodic(&mut delay).expect("stop_periodic");

    // Switch the heater, (on) → Result<()>
    // about 5-25 mW; readings are unusable while on
    chip.set_heater(true).expect("heater on");
    // Read status register, () → Result<u16>
    // CRC verified; bit 13 = heater on
    println!("status 0x{:04X}", chip.read_status().expect("status"));
    chip.set_heater(false).expect("heater off");
    // Clear status flags, () → Result<()>
    // command 0x3041
    chip.clear_status().expect("clear_status");
    // Compute dew point, (delay) → Result<f32> °C
    // one measurement, Magnus formula
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
    // Soft reset, (delay) → Result<()>
    // 0x30A2, waits 1 ms, back to single shot
    chip.soft_reset(&mut delay).expect("soft_reset");
}
