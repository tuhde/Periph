// Complete example: exercise every method in the public API.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{MCP3422Full, MCP3422_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = MCP3422Full::new(dev, MCP3422_I2C_ADDRESS); // Create MCP3422 Full driver, (i2c, addr=0x68)
    adc.init().expect("init");                                // Write the default configuration, () → ()
    // one-shot, 16 bit, x1, RDY = 0

    println!("{}", adc.read_raw(&mut Delay, 0).expect("read_raw")); // One-shot code, (delay, channel 0–1) → i32
    // writes RDY = 1, O/C = 0, waits, polls RDY
    println!("{}", adc.read_voltage(&mut Delay, 1).expect("read_voltage")); // One-shot voltage, (delay, channel 0–1) → f32 V
    println!("{:?}", adc.read_all(&mut Delay).expect("read_all")); // Both channels, (delay) → [f32; 2]
    let mut scan = [0.0f32; 3];
    adc.read_scan(&mut Delay, &[0, 1, 0], &mut scan).expect("read_scan"); // Scan a list of channels, (delay, channels, out) → ()
    println!("{:?}", scan);

    adc.set_resolution(18).expect("set_resolution"); // Select 18 bit / 3.75 SPS, (bits 12|14|16|18) → Result
    // InvalidArgument for other values
    adc.set_gain(8).expect("set_gain"); // Select PGA x8, (gain 1|2|4|8) → Result
    println!("{} {}", adc.get_resolution(), adc.get_gain()); // Read back the selection, () → (u8, u8)
    println!("{} {}", adc.lsb(), adc.full_scale()); // LSB and full scale, () → (f32 V, f32 V)
    // 2·2.048/2^bits and 2.048/gain

    adc.set_resolution(12).expect("set_resolution"); // Back to 12 bit / 240 SPS, (bits) → Result
    adc.set_gain(1).expect("set_gain"); // Back to x1, (gain) → Result
    adc.set_channel(0).expect("set_channel"); // Channel for continuous conversion, (channel 0–1) → ()
    adc.start_continuous(None).expect("start_continuous"); // Start continuous conversion, (channel Option<u8>) → ()
    // O/C = 1; the first sample after a change is discarded
    println!("{}", adc.is_continuous()); // Report the mode, () → bool
    println!("{}", adc.read_continuous_raw(&mut Delay).expect("raw")); // Next fresh code, (delay) → i32
    println!("{}", adc.read_continuous(&mut Delay).expect("voltage")); // Next fresh voltage, (delay) → f32 V
    println!("{:#x} {}", adc.read_config().expect("config"), adc.is_ready().expect("ready")); // Config byte and RDY, () → (u8, bool)
    adc.stop_continuous().expect("stop_continuous"); // Back to one-shot / standby, () → ()

    adc.general_call_convert().expect("general_call_convert"); // Start a conversion on every device, () → ()
    // sends 0x00 0x08
    sleep(Duration::from_millis(100));
    println!("{}", adc.read_pending_raw(&mut Delay).expect("pending")); // Result of that conversion, (delay) → i32
    adc.general_call_reset().expect("general_call_reset"); // Reset to power-on defaults, () → ()
    // sends 0x00 0x06; the driver state follows
}
