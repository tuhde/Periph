use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::pcf8591::MODE_4_SINGLE_ENDED;
use periph::chips::adc_dac::Pcf8591Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x48);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Pcf8591Full::new(dev, addr).expect("init PCF8591");

    const VREF: f32  = 3.3;
    const VAGND: f32 = 0.0;

    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    adc.configure(MODE_4_SINGLE_ENDED, false, true)                     // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → Result<(), E>
        .expect("configure");                                            // single-ended mode with DAC output enabled
    loop {
        for n in 0..20 {
            let raw = adc.read_channel(0).expect("read ch0");            // Read single channel, (channel=0–3) → Result<u8, E>
            let vin  = VAGND + (raw as f32) * (VREF - VAGND) / 256.0;
            adc.set_dac(raw).expect("set_dac");                          // Enable DAC and set raw value, (value=0–255) → Result<(), E>
            let vout = VAGND + (raw as f32) * (VREF - VAGND) / 256.0;
            println!("n={:2} raw={:3} vin={:.3}V  vout={:.3}V", n, raw, vin, vout);
            sleep(Duration::from_millis(200));
        }
    }
}
