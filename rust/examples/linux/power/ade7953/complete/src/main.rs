use linux_embedded_hal::I2cdev;
use periph::chips::power::Ade7953Minimal;
use embedded_hal::delay::DelayNs;
use std::time::Duration;

struct Delay;

impl DelayNs for Delay {
    fn delay_ms(&mut self, ms: u32) {
        std::thread::sleep(Duration::from_millis(ms as u64));
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut ade = Ade7953Minimal::new(dev, addr, 251.0, 30.0, &mut delay).expect("init ADE7953");  // Create ADE7953 driver, (i2c, addr, voltage_gain=V/V, current_gain=A/V, delay)

    println!("version: 0x{:02X}", ade.version().expect("version"));     // Read silicon version, () → u8
    println!("V={:.2}",   ade.voltage().expect("voltage"));              // Read bus voltage, () → f32 V
                                                                            // converts raw VRMS to volts using voltage_gain
    println!("I_a={:.3}", ade.current().expect("current"));              // Read load current, () → f32 A
                                                                            // converts raw IRMSA to amperes using current_gain
    println!("P_a={:.2}", ade.active_power().expect("active_power"));     // Read active power, () → f32 W
                                                                            // converts raw AWATT (instantaneous, 6.99 kHz) to watts
    println!("E_a={:.4}", ade.active_energy().expect("active_energy"));   // Read active energy, () → f32 Wh
                                                                            // converts raw AENERGYA accumulated LSBs to watt-hours
    println!("PF={:.3}",  ade.power_factor().expect("power_factor"));     // Read power factor, () → f32 ratio
                                                                            // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
    println!("f={:.2}",    ade.line_frequency().expect("line_frequency")); // Read line frequency, () → f32 Hz
    println!("Q={:.2}",   ade.reactive_power().expect("reactive_power")); // Read reactive power, () → f32 VAR
    println!("S={:.2}",   ade.apparent_power().expect("apparent_power")); // Read apparent power, () → f32 VA

    ade.configure_overvoltage(260.0).expect("overvoltage");              // Configure overvoltage, (threshold) → ()
                                                                            // threshold in volts (same scale as voltage())
    ade.configure_overcurrent(40.0).expect("overcurrent");               // Configure overcurrent, (threshold) → ()
                                                                            // threshold in amperes; applies to BOTH current channels

    ade.reset(&mut delay).expect("reset");                                // Software reset, () → ()
                                                                            // waits 110 ms then re-runs the mandatory power-up sequence
}