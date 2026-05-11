use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina3221Full, CF1, CF2, CF3, SF, WF1, WF2, WF3, PVF, TCF, CVRF};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina3221Full::new(dev, addr, 0.1);  // Create INA3221 driver, (i2c, addr, r_shunt=0.1 Ω)

    // --- Monitor three rails simultaneously ---
    // User wires CH1 to 5V rail, CH2 to 3.3V rail, CH3 to 12V rail.
    // The demo prints a one-line tabular update each second for 30 seconds.
    println!("V1       I1       P1       V2       I2       P2       V3       I3       P3");
    for t in 0..30 {
        for ch in 1..=3 {
            let v = chip.voltage(ch).expect("voltage");   // Read bus voltage, (channel) → f32 V
            let i = chip.current(ch).expect("current");   // Read load current, (channel) → f32 A
            let p = chip.power(ch).expect("power");       // Read power, (channel) → f32 W
            print!("{:-8.3} {:-8.4} {:-8.4} ", v, i, p);
        }
        println!();

        if t == 9 {
            // --- Arm critical-alert limits at 1.5x current draw ---
            for ch in 1..=3 {
                let i = chip.current(ch).expect("current");
                chip.set_critical_alert(ch, i * 1.5, false).expect("set_critical_alert");
            }
            println!("alerts armed");
        }

        if t == 19 {
            // --- Arm shunt-voltage summation across all three channels ---
            chip.set_summation_channels(&[1, 2, 3], 0.3).expect("set_summation_channels");  // Set summation channels, (channels, limit_v) → ()
            println!("summation armed");
        }

        sleep(Duration::from_secs(1));
    }

    // --- Dump alert flags and decode any that fired ---
    let flags = chip.alert_flags().expect("alert_flags");  // Read alert flags, () → u16
    println!("Mask/Enable: 0x{:04X}", flags);
    let alert_names = ["CF1", "CF2", "CF3", "SF", "WF1", "WF2", "WF3", "PVF", "TCF", "CVRF"];
    let alert_bits = [CF1, CF2, CF3, SF, WF1, WF2, WF3, PVF, TCF, CVRF];
    let fired: Vec<_> = alert_names.iter().zip(alert_bits.iter()).filter(|(_, &bit)| flags & bit != 0).map(|(n, _)| *n).collect();
    if fired.is_empty() {
        println!("No alert flags fired");
    } else {
        println!("Flags fired: {}", fired.join(", "));
    }
}