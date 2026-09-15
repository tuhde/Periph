use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx710aMinimal;
use periph::connection::hx711::HX711Connection;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32  = std::env::var("HX710A_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32  = std::env::var("HX710A_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dout_handle = chip.get_line(dout_offset).expect("get dout line")
        .request(LineRequestFlags::INPUT, 0, "hx710a_minimal").expect("request dout");
    let sck_handle = chip.get_line(sck_offset).expect("get sck line")
        .request(LineRequestFlags::OUTPUT, 0, "hx710a_minimal").expect("request pd_sck");

    let dout   = CdevPin::new(dout_handle).expect("dout pin");
    let pd_sck = CdevPin::new(sck_handle).expect("pd_sck pin");

    let connection = HX711Connection::new(dout, pd_sck);
    let mut chip = Hx710aMinimal::new(connection).expect("init HX710A");  // Create HX710A driver — discards first conversion, (connection) → Result<Hx710aMinimal, _>

    loop {
        let ready = chip.is_ready().expect("is_ready");    // Check if conversion is ready (non-blocking), () → Result<bool, _>
        let raw = chip.read_raw().expect("read_raw");      // Read signed 24-bit differential-input value, () → Result<i32, _>
        println!("{}", raw);
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
