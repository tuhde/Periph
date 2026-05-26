use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx711Minimal;
use periph::transport::hx711::HX711Transport;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32  = std::env::var("HX711_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32  = std::env::var("HX711_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let chip = gpiocdev::Chip::new(&chip_path).expect("open gpio chip");
    let dout_line = chip
        .request_line(gpiocdev::Request::input([dout_offset]).consumer("hx711_minimal"))
        .expect("request dout");
    let sck_line = chip
        .request_line(gpiocdev::Request::output([sck_offset]).consumer("hx711_minimal"))
        .expect("request pd_sck");

    let dout   = CdevPin::new(dout_line, dout_offset).expect("dout pin");
    let pd_sck = CdevPin::new(sck_line,  sck_offset).expect("pd_sck pin");

    let transport = HX711Transport::new(dout, pd_sck);
    let mut chip = Hx711Minimal::new(transport).expect("init HX711");  // Create HX711 driver — discards first conversion, (transport) → Result<Hx711Minimal, _>

    loop {
        let ready = chip.is_ready().expect("is_ready");    // Check if conversion is ready (non-blocking), () → Result<bool, _>
        let raw = chip.read_raw().expect("read_raw");      // Read signed 24-bit ADC value, () → Result<i32, _>
        println!("{}", raw);
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
