use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads7828Full, ADS7828_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads7828Full::new(dev, Delay, ADS7828_I2C_ADDRESS, None); // Create ADS7828 driver, (i2c, delay, addr=0x48, vref None → internal 2.5 V)
    adc.init().expect("init");                                  // Enable internal reference, () → Result<(), E>

    {
        const DIVIDER: f32 = 2.0; // inputs sit behind 2:1 dividers (rails up to 5 V)

        // --- Eight-channel supply monitor ---
        // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
        // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
        let mut vmin = [99.0f32; 8];
        let mut vmax = [0.0f32; 8];
        for _ in 0..5 {
            let mut raws = [0u16; 8];
            adc.read_scan(&[0, 1, 2, 3, 4, 5, 6, 7], &mut raws).expect("scan"); // Scan channels, (channels, out) → Result<(), E>
            for ch in 0..8 {
                let volts = raws[ch] as f32 * adc.lsb() * DIVIDER;
                if volts < vmin[ch] { vmin[ch] = volts; }
                if volts > vmax[ch] { vmax[ch] = volts; }
                println!("ch{}={} ({:.3} V)", ch, raws[ch], volts);
            }
            sleep(Duration::from_secs(1));
        }
        println!("min={:?} max={:?}", vmin, vmax);

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        let mv = adc.read_signed_differential(0).expect("signed") * 1000.0; // Read pair 0 signed, (pair=0–3) → Result<f32, E> V
        println!("bridge {:+.2} mV", mv);

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.set_reference(Some(5.0)).expect("set_reference");   // Use external reference, (vref Option<f32> V) → Result<(), E>
        let mut ext = [0u16; 8];
        adc.read_scan(&[0, 1, 2, 3, 4, 5, 6, 7], &mut ext).expect("scan"); // Scan channels, (channels, out) → Result<(), E>
        println!("ext 5 V scan ch0={} ch7={}", ext[0], ext[7]);

        // --- Finish in full power-down ---
        adc.power_down().expect("power_down");                  // Power down completely, () → Result<(), E>
        adc.set_reference(None).expect("set_reference");
        sleep(Duration::from_secs(1));
    }
}
