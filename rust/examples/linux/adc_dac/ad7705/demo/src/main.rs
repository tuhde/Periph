use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::adc_dac::{AD7705Full, GAIN_128, GAIN_2, MCLK_2_4576MHZ};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

const TEMP_COEFF: f32 = 0.05;
const TEMP_REFERENCE: f32 = 1.25;
const CHANGE_THRESHOLD: f32 = 0.001;

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

    let mut chip = AD7705Full::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7705");      // Create AD7705 driver, (spi, vref=2.5 V, mclk_hz=2_457_600 Hz) → Result

    // --- Configure both channels for the bridge-pressure application ---
    chip.configure(1, GAIN_128, true, true, 50).expect("cfg1");                             // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → Result
    chip.configure(2, GAIN_2, true, false, 50).expect("cfg2");                              // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → Result

    // --- Self-calibrate both channels before the measurement loop ---
    chip.self_calibrate(1).expect("cal1");                                                  // Self-calibrate channel, (channel=1) → Result
    chip.self_calibrate(2).expect("cal2");                                                  // Self-calibrate channel, (channel=2) → Result

    // --- Sample continuously and compensate the pressure reading for temperature ---
    let mut last_pressure: Option<f32> = None;
    loop {
        let pressure_raw = chip.read_voltage_channel(1).expect("v1");                      // Read voltage, (channel=1) → Result<f32>
        let temp = chip.read_voltage_channel(2).expect("v2");                              // Read voltage, (channel=2) → Result<f32>
        let pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE);
        let need_print = match last_pressure {
            None => true,
            Some(p) => (pressure - p).abs() > CHANGE_THRESHOLD,
        };
        if need_print {
            println!("→ pressure={:.4} V (raw {:.4} V, temp {:.4} V)", pressure, pressure_raw, temp);
            last_pressure = Some(pressure);
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
}
