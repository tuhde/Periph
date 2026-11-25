use linux_embedded_hal::I2cdev;
use periph::chips::power::Axp2101Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x34);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Axp2101Minimal::new(dev, addr).expect("init AXP2101");      // Create AXP2101 driver, (i2c, addr=0x34) → Axp2101Minimal
    std::thread::sleep(std::time::Duration::from_millis(20));                  // wait for the first ADC conversions

    for _ in 0..10 {
        let v = chip.battery_voltage().expect("battery_voltage");              // Read battery voltage, () → f32 V
        let pct = chip.battery_percent().expect("battery_percent");            // Read state of charge, () → u8 %
        let state = chip.charge_state().expect("charge_state");                // Read charge state, () → u8 (0 trickle … 5 none)
        let vbus = chip.vbus_present().expect("vbus_present");                 // Check input source, () → bool
        println!("{:.3} V  {} %  state={}  vbus={}", v, pct, state, vbus);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
