use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::{Delay, SpidevBus};
use periph::chips::adc_dac::{Hy3131Full};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);
    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    // --- Bench voltmeter with on-chip temperature and AC analysis ---
    // A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
    // for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
    let mut chip = Hy3131Full::new(device, Delay, 1.2).expect("init HY3131");               // Create HY3131 driver, (spi, delay, vref=1.2 V) → Result
    chip.configure_ad1(4, 2, 0, 0, 0, 0).expect("cfg ad1");                                 // Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → Result

    // --- One-point temperature calibration ---
    // Run the board at 25 °C (or replace with a stored constant) and remember the code.
    let tcode_25 = chip.read_temperature_code().expect("tcode");                            // Read temperature code, () → Result<i32>

    // --- Enable AC measurement chain on the same input ---
    chip.enable_ad2(5, 1, 0, false, false, 2, false).expect("ad2");                         // Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=false, clk_div4=false, source=0, use_ad3=false) → Result
    chip.enable_lpf(3).expect("lpf");                                                       // Enable low-pass filter, (bw=0) → Result
    chip.enable_rms().expect("rms");                                                        // Enable RMS converter, () → Result
    chip.enable_peak_hold(0).expect("pkh");                                                 // Enable peak hold, (source=0) → Result

    loop {
        let v = chip.read_voltage().expect("v");                                            // Read AD1 voltage, () → Result<f32> V
        let t = chip.read_temperature(tcode_25).expect("t");                                // Read die temperature, (tcode_25) → Result<f32> °C
        let rms = chip.read_rms().expect("rms");                                            // Read RMS, () → Result<f32> LSB
        let (lo, hi) = chip.read_peak().expect("peak");                                     // Read peak hold, () → Result<(i32, i32)> 19-bit
        println!("V={:.4} V  T={:.1} C  rms={:.0}  p-p={}", v, t, rms, hi - lo);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
