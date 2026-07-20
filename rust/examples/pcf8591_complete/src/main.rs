use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::pcf8591::{MODE_3_DIFFERENTIAL, MODE_4_SINGLE_ENDED};
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

    loop {
        let ch0_raw = adc.read_channel(0).expect("read ch0");            // Read single channel, (channel=0–3) → Result<u8, E>
                                                                          // discards the stale first conversion byte; returns 0–255
        let ch1_raw = adc.read_channel(1).expect("read ch1");            // Read single channel, (channel=0–3) → Result<u8, E>
                                                                          // selects channel 1 via the control byte, returns 0–255
        let all_raw = adc.read_all().expect("read all");                 // Read all four channels, () → Result<[u8; 4], E>
                                                                          // sets AI=1 and reads 5 bytes; discards stale byte 0

        let v0 = adc.read_channel_voltage(0, 3.3, 0.0).expect("v0");     // Read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → Result<f32, E> V
                                                                          // converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256
        let v_all = adc.read_all_voltage(3.3, 0.0).expect("v_all");      // Read all channels as voltages, (vref=3.3 V, vagnd=0.0 V) → Result<[f32; 4], E> V
                                                                          // returns four voltages using the same conversion

        adc.configure(MODE_3_DIFFERENTIAL, false, false)               // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → Result<(), E>
            .expect("configure diff");                                   // sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI
        let diff = adc.read_differential(0).expect("read diff");         // Read differential channel, (channel=0–2) → Result<i8, E>
                                                                          // returns signed 8-bit two's complement (-128 to 127)
        adc.configure(MODE_4_SINGLE_ENDED, false, true)                 // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → Result<(), E>
            .expect("configure single-ended");                            // restores 4 single-ended mode and enables the DAC output
        adc.set_dac(128).expect("set_dac 128");                          // Enable DAC and set raw value, (value=0–255) → Result<(), E>
                                                                          // sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2
        adc.set_dac_voltage(0.25).expect("set_dac_voltage 0.25");        // Set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → Result<(), E>
                                                                          // maps fraction to 0–255 and writes the DAC; AOUT follows
        adc.disable_dac().expect("disable_dac");                         // Disable DAC output, () → Result<(), E>
                                                                          // clears AOE; AOUT returns to high-impedance
        println!("ch0_raw={} v0={:.3}V diff={}", ch0_raw, v0, diff);
        sleep(Duration::from_secs(1));
    }
}
