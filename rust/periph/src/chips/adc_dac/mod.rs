pub mod hx710a;
pub use hx710a::{Hx710aMinimal, Hx710aFull};
pub mod hx710b;
pub use hx710b::{Hx710bMinimal, Hx710bFull};
pub mod hx711;
pub use hx711::{Hx711Minimal, Hx711Full};
pub mod mcp4725;
pub use mcp4725::{Mcp4725Minimal, Mcp4725Full};
pub mod mcp4728;
pub use mcp4728::{Mcp4728Minimal, Mcp4728Full, ChannelState, ReadResult};
pub mod pcf8591;
pub use pcf8591::{
    Pcf8591Minimal, Pcf8591Full,
    MODE_2_DIFFERENTIAL, MODE_3_DIFFERENTIAL, MODE_4_SINGLE_ENDED, MODE_MIXED,
};

pub mod ad7705;
pub use ad7705::{
    AD7705Minimal, AD7705Full,
    MCLK_1MHZ, MCLK_2MHZ, MCLK_2_4576MHZ, MCLK_4_9152MHZ,
    GAIN_1, GAIN_2, GAIN_4, GAIN_8, GAIN_16, GAIN_32, GAIN_64, GAIN_128,
};
