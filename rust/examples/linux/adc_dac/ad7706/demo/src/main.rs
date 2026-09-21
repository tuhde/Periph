use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::adc_dac::{AD7706Full, GAIN_128, MCLK_2_4576MHZ};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

const FILTER_DP_THRESHOLD: f32 = 0.001;

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
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip = AD7706Full::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7706");      // Create AD7706 driver, (spi, vref=2.5 V, mclk_hz=2_457_600 Hz) → Result

    // --- Configure all three channels for the HVAC manifold-pressure application ---
    chip.configure(1, GAIN_128, true, true, 50).expect("cfg1");                             // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → Result
    chip.configure(2, GAIN_128, true, true, 50).expect("cfg2");                             // Configure channel 2, (channel=2, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → Result
    chip.configure(3, GAIN_128, true, true, 50).expect("cfg3");                             // Configure channel 3, (channel=3, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → Result

    // --- Self-calibrate all three channels before the measurement loop ---
    chip.self_calibrate(1).expect("cal1");                                                  // Self-calibrate channel, (channel=1) → Result
    chip.self_calibrate(2).expect("cal2");                                                  // Self-calibrate channel, (channel=2) → Result
    chip.self_calibrate(3).expect("cal3");                                                  // Self-calibrate channel, (channel=3) → Result

    // --- Sample continuously and compute filter differential pressure ---
    let mut last_filter_dp: Option<f32> = None;
    loop {
        let inlet  = chip.read_voltage_channel(1).expect("v1");                             // Read voltage, (channel=1) → Result<f32>
        let outlet = chip.read_voltage_channel(2).expect("v2");                             // Read voltage, (channel=2) → Result<f32>
        let duct   = chip.read_voltage_channel(3).expect("v3");                             // Read voltage, (channel=3) → Result<f32>
        let filter_dp = inlet - outlet;
        let need_print = match last_filter_dp {
            None => true,
            Some(p) => (filter_dp - p).abs() > FILTER_DP_THRESHOLD,
        };
        if need_print {
            println!("→ filter_dp={:.4} V, duct_pressure={:.4} V", filter_dp, duct);
            last_filter_dp = Some(filter_dp);
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
}
