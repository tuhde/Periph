pub mod ads7828;
pub use ads7828::{
    Ads7828Minimal, Ads7828Full, Ads7828Error, ADS7828_I2C_ADDRESS, ADS7828_POWER_DOWN_BETWEEN,
    ADS7828_REF_OFF_ADC_ON, ADS7828_REF_ON_ADC_OFF, ADS7828_REF_ON_ADC_ON,
};
pub mod cat5171;
pub mod cs43l22;
pub use cs43l22::{CS43L22Minimal, CS43L22Full, Cs43l22Error, CS43L22_I2C_ADDRESS};
pub mod ds1881;
pub mod ds3502;
pub use ds3502::{DS3502Minimal, DS3502Full, Ds3502Error, Ds3502Terminal, DS3502_I2C_ADDRESS};
pub use ds1881::{DS1881Minimal, DS1881Full, Ds1881Config, Ds1881Error, DS1881_I2C_ADDRESS};
pub use cat5171::{CAT5171Minimal, CAT5171Full, CAT5171_I2C_ADDRESS, CAT5171_I2C_ADDRESS_AD0_HIGH};
pub mod hx710a;
pub use hx710a::{Hx710aMinimal, Hx710aFull};
pub mod hx710b;
pub use hx710b::{Hx710bMinimal, Hx710bFull};
pub mod hx711;
pub use hx711::{Hx711Minimal, Hx711Full};
pub mod es8311;
pub use es8311::{
    Es8311Minimal, Es8311Full, Es8311Error, ES8311_I2C_ADDRESS,
    Format as Es8311Format, Input as Es8311Input, AdcdatMode as Es8311AdcdatMode,
    SerialConfig as Es8311SerialConfig, AdcConfig as Es8311AdcConfig,
    AlcConfig as Es8311AlcConfig, DrcConfig as Es8311DrcConfig, LowPower as Es8311LowPower,
};
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
// AD7706 shares its master clock frequencies and PGA gain settings with the
// AD7705 (same register-level protocol) — reuse the already-exported
// constants above rather than re-exporting AD7706's identical copies.
pub mod ad7706;
pub use ad7706::{AD7706Minimal, AD7706Full};

pub mod hy3131;
pub use hy3131::{Hy3131Minimal, Hy3131Full, Hy3131Error};

pub mod ad5243;
pub use ad5243::{AD5243Minimal, AD5243Full, AD5243Error, AD5243_I2C_ADDRESS};
pub mod ads1013;
pub use ads1013::{
    Ads1013Minimal, Ads1013Full, Ads1013Error, ADS1013_I2C_ADDRESS,
    ADS1013_DR_128, ADS1013_DR_250, ADS1013_DR_490, ADS1013_DR_920,
    ADS1013_DR_1600, ADS1013_DR_2400, ADS1013_DR_3300,
};
pub mod ads1014;
pub use ads1014::{
    Ads1014Minimal, Ads1014Full, Ads1014Error, ADS1014_I2C_ADDRESS,
    ADS1014_PGA_6_144, ADS1014_PGA_4_096, ADS1014_PGA_2_048,
    ADS1014_PGA_1_024, ADS1014_PGA_0_512, ADS1014_PGA_0_256,
    ADS1014_DR_128, ADS1014_DR_250, ADS1014_DR_490, ADS1014_DR_920,
    ADS1014_DR_1600, ADS1014_DR_2400, ADS1014_DR_3300,
    ADS1014_COMP_TRADITIONAL, ADS1014_COMP_WINDOW, ADS1014_ACTIVE_LOW, ADS1014_ACTIVE_HIGH,
    ADS1014_QUEUE_1, ADS1014_QUEUE_2, ADS1014_QUEUE_4,
    ADS1014_SOURCE_CONVERSION_READY, ADS1014_SOURCE_COMPARATOR,
};
pub mod ad5248;
pub use ad5248::{Ad5248Minimal, Ad5248Full};
mod ads111x;
pub use ads111x::Ads111xError;
pub mod ads1113;
pub use ads1113::{Ads1113Minimal, Ads1113Full};
pub mod ads1114;
pub use ads1114::{Ads1114Minimal, Ads1114Full};
pub mod ads1115;
pub use ads1115::{Ads1115Minimal, Ads1115Full};
pub mod mcp4xxx;
pub use mcp4xxx::{Mcp4xxxMinimal, Mcp4xxxFull, Part as Mcp4xxxPart, PartInfo as Mcp4xxxPartInfo, Error as Mcp4xxxError, Status as Mcp4xxxStatus, NoHvc as Mcp4xxxNoHvc};
