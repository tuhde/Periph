//! Demo for the RRH62000 — room air-quality station.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::Rrh62000Full;

fn iaq_label(iaq: f32) -> &'static str {
    if iaq < 2.0 {
        "very good"
    } else if iaq < 3.0 {
        "good"
    } else if iaq < 4.0 {
        "medium"
    } else if iaq < 5.0 {
        "poor"
    } else {
        "bad"
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x69);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create RRH62000 driver, (i2c, delay, addr=0x69) → Rrh62000Full
    let mut chip = Rrh62000Full::new(bus, delay, addr);
    // Wait for start-up, check firmware version, () → Result<()>
    chip.init().expect("init");

    // --- Identity and settings ---
    // The module may only be accessed once per second; every call below waits
    // for that interval, so this section takes several seconds.
    // Firmware version, () → Result<(major, minor)>
    if let Ok((major, minor)) = chip.firmware_version() { println!("firmware {}.{}", major, minor); }
    // Algorithm version, () → Result<(major, minor, patch)>
    if let Ok((a, b, c)) = chip.algorithm_version() { println!("algorithm {}.{}.{}", a, b, c); }
    // Unique ID, () → Result<[u8; 6]>
    if let Ok(uid) = chip.unique_id() { println!("unique ID {:02X?}", uid); }
    // Moving average, () → Result<u8> / Fan speed, () → Result<u8>
    println!("moving average {:?}, fan {:?} %", chip.get_moving_average(), chip.get_fan_speed());
    // Cleaning interval, () → Result<u32 s> / Cleaning time, () → Result<u8 s>
    println!("cleaning every {:?} s for {:?} s", chip.get_cleaning_interval(), chip.get_cleaning_time());

    // --- Room air-quality station ---
    // A complete sample arrives every 3 s. The IAQ index follows the German UBA
    // scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
    // first use before trusting TVOC and eCO2.
    for _ in 0..10 {
        // Complete block, () → Result<Data>
        match chip.read_all() {
            Ok(d) => println!("IAQ {:.2} ({})  PM2.5 {:.1} ug/m3  eCO2 {} ppm  TVOC {} ug/m3  {:.1} C  {:.1} %RH",
                              d.iaq, iaq_label(d.iaq), d.particles.pm2_5_ug_m3, d.eco2_ppm, d.tvoc,
                              d.temperature_c, d.humidity_rh),
            Err(e) => println!("read failed: {:?}", e),
        }
    }

    // --- Particle counts and status ---
    // Number concentrations count particles from the bin size up to 10 µm per cm³.
    // Particle values, () → Result<Particles>
    if let Ok(p) = chip.read_particles() {
        println!("particles/cm3  >0.3um {:.1}  >0.5um {:.1}  >1um {:.1}  >2.5um {:.1}  >4um {:.1}",
                 p.nc_0_3, p.nc_0_5, p.nc_1, p.nc_2_5, p.nc_4);
        println!("PM2.5 KCl {:.1} / smoke {:.1} ug/m3", p.pm2_5_ug_m3, p.pm2_5_smoke_ug_m3);
    }
    // Status flags, () → Result<Status>
    println!("status {:?}", chip.read_status());
    // New sample available, () → Result<bool>
    println!("new data pending: {:?}", chip.data_ready());

    // --- Quieter, smoother operation ---
    // 30 moving averages smooth the PM values (slower response); 70 % fan speed
    // is quieter.
    // Moving average, (count 1–60) → Result<()>
    chip.set_moving_average(30).ok();
    // Fan speed, (percent 60–100) → Result<()>
    chip.set_fan_speed(70).ok();
    println!("moving average {:?}, fan {:?} %", chip.get_moving_average(), chip.get_fan_speed());

    // --- Fan cleaning ---
    // A manual cleaning blows dust out of the air channel; the automatic
    // cleaning is shortened to every 3 days for 10 s.
    // Start fan cleaning, () → Result<()>
    chip.start_cleaning().ok();
    for _ in 0..30 {
        // Cleaning status, () → Result<bool>
        if chip.cleaning_done().unwrap_or(false) {
            println!("cleaning done");
            break;
        }
    }
    // Cleaning interval, (seconds) → Result<()>
    chip.set_cleaning_interval(3 * 24 * 3600).ok();
    // Cleaning time, (seconds) → Result<()>
    chip.set_cleaning_time(10).ok();
    println!("cleaning every {:?} s", chip.get_cleaning_interval());

    // --- Sleep, diagnostics, reset ---
    // Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
    // Sleep, () → Result<()>
    chip.sleep().ok();
    sleep_ms!(5000);
    // Wake up, () → Result<()>
    chip.wake().ok();
    // MOX resistance, () → Result<u32 Ω>
    println!("MOX resistance {:?} ohm", chip.read_mox_resistance());
    // Reset, () → Result<()>
    chip.soft_reset().ok();
    // Main values, () → Result<Measurement>
    println!("after reset {:?}", chip.read());
}
