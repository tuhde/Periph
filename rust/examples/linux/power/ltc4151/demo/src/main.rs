use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::{Ltc4151Full, CH_ADIN, CH_SENSE, CH_VIN};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Ltc4151Full::new(dev, addr, 0.02).expect("init LTC4151"); // Create LTC4151 driver, (i2c, addr, r_sense=0.02 Ω)

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    for _ in 0..20 {
        let r = chip.read_all().unwrap();                               // Consistent read of all channels, () → Ltc4151Readings
        println!("{:.2} V  {:.2} mV  {:.4} A  {:.3} W  adin {:.3} V",
                 r.vin, r.sense_v * 1000.0, r.current, r.power, r.adin);
        sleep(Duration::from_secs(1));
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    for (name, ch) in [("SENSE", CH_SENSE), ("VIN", CH_VIN), ("ADIN", CH_ADIN)] {
        let t0 = std::time::Instant::now();
        let value = chip.snapshot(ch, &mut delay).unwrap();             // One snapshot conversion, (channel, delay) → Result<f32> V
        println!("{} snapshot {:.4} V in {} ms", name, value, t0.elapsed().as_millis());
    }
    println!("snapshot mode afterwards {}", chip.is_snapshot_mode().unwrap()); // Snapshot mode active, () → bool

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    chip.set_stuck_bus_timer(false).unwrap();                           // Stuck-bus reset timer, (enabled)
    chip.set_page_rw(false).unwrap();                                   // I²C page read/write, (enabled)
    println!("CONTROL (timer off, page off) 0x{:02X}", chip.read_control().unwrap()); // Read CONTROL, () → u8
    chip.set_stuck_bus_timer(true).unwrap();
    chip.set_page_rw(true).unwrap();
    println!("CONTROL (restored) 0x{:02X}", chip.read_control().unwrap());

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    println!("temperature {:.1} C", Ltc4151Full::<I2cdev>::temperature_from_adin(900, 1200)); // NTC example circuit, (adin_code, vin_code) → f32 °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    let (mut pmin, mut pmax, mut psum) = (f32::MAX, 0.0f32, 0.0f32);
    for _ in 0..25 {
        let p = chip.power().unwrap();                                  // Load power, () → f32 W
        pmin = pmin.min(p);
        pmax = pmax.max(p);
        psum += p;
        sleep(Duration::from_millis(200));
    }
    println!("power min {:.3} W, max {:.3} W, avg {:.3} W", pmin, pmax, psum / 25.0);
}
