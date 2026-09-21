use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::adc_dac::{AD7705Full, GAIN_8, MCLK_2_4576MHZ};
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
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip = AD7705Full::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7705");       // Create AD7705 driver, (spi, vref=2.5 V, mclk_hz=2_457_600 Hz) → Result

    chip.configure(2, GAIN_8, true, true, 60).expect("configure ch2");                      // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → Result
                                                                                            // sets gain/bipolar/buffered/output_rate; does not calibrate
    chip.self_calibrate(2).expect("self calibrate");                                        // Self-calibrate channel, (channel=2) → Result
                                                                                            // runs internal self-calibration, blocking until DRDY

    let off2 = chip.get_offset_calibration(2).expect("offset");                             // Read offset calibration, (channel=2) → Result<u32> 24-bit
    let gain2 = chip.get_gain_calibration(2).expect("gain");                                // Read gain calibration, (channel=2) → Result<u32> 24-bit
    println!("ch2 offset={} gain={}", off2, gain2);

    let raw1 = chip.read_raw_channel(1).expect("raw1");                                     // Read raw 16-bit code, (channel=1) → Result<u16>
    let v1 = chip.read_voltage_channel(1).expect("v1");                                     // Read voltage, (channel=1) → Result<f32>
    let v2 = chip.read_voltage_channel(2).expect("v2");                                     // Read voltage, (channel=2) → Result<f32>
    println!("ch1 raw={} ch1 v={} ch2 v={}", raw1, v1, v2);

    chip.standby().expect("standby");                                                       // Enter standby, () → Result
    chip.wakeup().expect("wakeup");                                                         // Exit standby, () → Result
}
