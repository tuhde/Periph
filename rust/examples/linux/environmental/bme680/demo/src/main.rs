use linux_embedded_hal::I2cdev;
use periph::chips::environmental::{Bme680Full, OSRS_X2, OSRS_X16, OSRS_X1, MODE_FORCED, FILTER_15};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
    // Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
    // At tick 30, the user is prompted to expose the sensor to a VOC source
    // (isopropyl alcohol, marker pen). Gas resistance drops sharply on exposure
    // and recovers over the remaining ticks, demonstrating raw VOC sensitivity
    // without the closed-source BSEC library.
    let mut bme = Bme680Full::new(dev, addr).expect("init BME680"); // Create BME680 driver, (i2c, addr=0x76)
    bme.configure(OSRS_X2, OSRS_X16, OSRS_X1, MODE_FORCED, FILTER_15).expect("configure"); // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → ()
    bme.set_heater(320, 150).expect("set_heater");                 // Configure heater profile 0, (temp_c=320, duration_ms=150) → ()

    let mut t_min = f32::MAX;
    let mut t_max = f32::MIN;
    let mut t_sum = 0.0f32;
    let mut g_min = f32::MAX;
    let mut g_max = 0.0f32;
    let mut gas_count = 0u32;

    for n in 0..60u32 {
        if n == 30 {
            println!("--- Expose sensor to VOC source now (alcohol/marker) ---");
        }
        let (t, p, h, g) = bme.read_all().expect("read_all");      // Read all sensors in one cycle, () → (f32, f32, f32, f32)
        if t < t_min { t_min = t; }
        if t > t_max { t_max = t; }
        t_sum += t;
        if !g.is_nan() {
            if g < g_min { g_min = g; }
            if g > g_max { g_max = g; }
            gas_count += 1;
        }
        println!("{}: {:.1} C, {:.1} %RH, {:.1} hPa, {:.0} Ohm", n, t, h, p, g);
        std::thread::sleep(std::time::Duration::from_secs(5));
    }

    let t_avg = t_sum / 60.0;
    println!("T: {:.1}/{:.1}/{:.1} C", t_min, t_avg, t_max);
    if gas_count > 0 && g_min > 0.0 {
        println!("VOC response ratio: {:.1}x", g_max / g_min);
    }
}
