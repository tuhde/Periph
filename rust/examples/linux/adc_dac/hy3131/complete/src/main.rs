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

    let mut chip = Hy3131Full::new(device, Delay, 1.2).expect("init HY3131");               // Create HY3131 driver, (spi, delay, vref=1.2 V) → Result
                                                                                            // resets the chip and applies the Minimal defaults (AD1 on PB2/AGND, 5 Hz)

    chip.configure_ad1(4, 2, 0, 0, 0, 0).expect("cfg ad1");                                 // Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → Result
                                                                                            // OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
    chip.set_ad1_input(10, 3, 2).expect("input");                                           // Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filter_r=2) → Result
                                                                                            // PB2 vs AGND with the filter resistor bypassed
    chip.set_ad1_reference(1, 1).expect("ref");                                             // Select AD1 reference, (high=0–6, low=0–6) → Result
                                                                                            // REFO (+) / AGND (−)

    let raw = chip.read_raw().expect("raw");                                                // Read AD1 code, () → Result<i32> 24-bit
                                                                                            // waits for AD1F in INTF, returns the sign-extended code
    let v = chip.read_voltage().expect("voltage");                                          // Read AD1 voltage, () → Result<f32> V
                                                                                            // code / 2^23 · RG − DCOS, scaled by vref / input gain
    println!("raw={} v={}", raw, v);

    let tcode = chip.read_temperature_code().expect("tcode");                               // Read temperature code, () → Result<i32>
                                                                                            // (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
    let t = chip.read_temperature(tcode).expect("temp");                                    // Read die temperature, (tcode_25) → Result<f32> °C
                                                                                            // one-point calibrated against tcode_25
    println!("temperature={}", t);

    chip.enable_ad2(5, 1, 0, false, false, 0, false).expect("ad2");                         // Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=false, clk_div4=false, source=0, use_ad3=false) → Result
                                                                                            // AD2 samples OP1O at gain 1.0, OSR 1024
    let ad2 = chip.read_ad2_raw().expect("ad2 raw");                                        // Read AD2 code, () → Result<i32> 19-bit
                                                                                            // waits for AD2F
    chip.enable_lpf(0).expect("lpf");                                                       // Enable low-pass filter, (bw=0) → Result
                                                                                            // OSR4 = 128
    let lpf = chip.read_lpf_raw().expect("lpf raw");                                        // Read LPF code, () → Result<i32> 19-bit
                                                                                            // waits for LPFF
    chip.enable_rms().expect("rms");                                                        // Enable RMS converter, () → Result
                                                                                            // accumulates the squared LPF input
    let rms = chip.read_rms().expect("rms read");                                           // Read RMS, () → Result<f32> LSB
                                                                                            // square root of the accumulator
    chip.enable_peak_hold(0).expect("pkh");                                                 // Enable peak hold, (source=0) → Result
                                                                                            // tracks AD2 min/max; re-enabling resets
    let (lo, hi) = chip.read_peak().expect("peak");                                         // Read peak hold, () → Result<(i32, i32)> 19-bit
    println!("ad2={} lpf={} rms={} peak={}..{}", ad2, lpf, rms, lo, hi);

    chip.start_counter(0).expect("counter");                                                // Start frequency counter, (gate_preload=0) → Result
                                                                                            // enables the CNT buffer and the counter
    let (cta, ctb, ctc, overflow) = chip.read_counter().expect("counter read");             // Read counter, () → Result<(u32, u32, u32, bool)>
                                                                                            // waits for CTF; overflow=true means the measurement is invalid
    println!("counter={} {} {} {}", cta, ctb, ctc, overflow);

    chip.enable_interrupts(0x04).expect("inte");                                            // Enable interrupts, (mask) → Result
                                                                                            // AD1IE: DO goes high while CS is high and AD1F is set
    let flags = chip.read_flags().expect("flags");                                          // Read and clear INTF, () → Result<u8>
                                                                                            // bit 2 = AD1F
    println!("flags={}", flags);

    chip.reset().expect("reset");                                                           // Software reset, () → Result
                                                                                            // writes 0x60 to R37 and re-applies the Minimal defaults
}
