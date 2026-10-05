// GENERATED from registry/chips.json by registry/scripts/generate.js - do not edit; run the script instead.
#pragma once
#ifdef __linux__  // host-only: the discovery tables are not part of the embedded builds
#include <cstddef>
#include <cstdint>

namespace periph {
namespace discovery {

struct IdProbe {
    uint32_t reg;
    uint8_t regBytes;
    uint8_t length;
    bool littleEndian;
    uint32_t mask;
    const uint32_t* expected;
    size_t nExpected;
};

struct ChipEntry {
    const char* id;
    const char* driver;  // nullptr when the chip has no driver in this library
    bool writeSensitive;
    bool aliased;
    const uint8_t* addresses;
    size_t nAddresses;
    const IdProbe* probe;  // nullptr when the chip has no identity register
};

static constexpr uint8_t kAddr_adxl345[] = {0x1D, 0x53};
static constexpr uint32_t kExp_adxl345[] = {0xE5};
static constexpr IdProbe kProbe_adxl345 = {0x00, 1, 1, false, 0xFF, kExp_adxl345, 1};
static constexpr uint8_t kAddr_bma180[] = {0x40, 0x41};
static constexpr uint32_t kExp_bma180[] = {0x03};
static constexpr IdProbe kProbe_bma180 = {0x00, 1, 1, false, 0x07, kExp_bma180, 1};
static constexpr uint8_t kAddr_lis3dh[] = {0x18, 0x19};
static constexpr uint32_t kExp_lis3dh[] = {0x33};
static constexpr IdProbe kProbe_lis3dh = {0x0F, 1, 1, false, 0xFF, kExp_lis3dh, 1};
static constexpr uint8_t kAddr_lsm303_accel[] = {0x19};
static constexpr uint8_t kAddr_lsm303_mag[] = {0x1E};
static constexpr uint32_t kExp_lsm303_mag[] = {0x483433};
static constexpr IdProbe kProbe_lsm303_mag = {0x0A, 1, 3, false, 0xFFFFFF, kExp_lsm303_mag, 1};
static constexpr uint8_t kAddr_mma8451q[] = {0x1C, 0x1D};
static constexpr uint32_t kExp_mma8451q[] = {0x1A};
static constexpr IdProbe kProbe_mma8451q = {0x0D, 1, 1, false, 0xFF, kExp_mma8451q, 1};
static constexpr uint8_t kAddr_ad5243[] = {0x2C};
static constexpr uint8_t kAddr_ad5248[] = {0x2C, 0x2D, 0x2E, 0x2F};
static constexpr uint8_t kAddr_ads1013[] = {0x48, 0x49, 0x4A, 0x4B};
static constexpr uint8_t kAddr_ads1014[] = {0x48, 0x49, 0x4A, 0x4B};
static constexpr uint8_t kAddr_ads1015[] = {0x48, 0x49, 0x4A, 0x4B};
static constexpr uint8_t kAddr_mcp4725[] = {0x60, 0x61};
static constexpr uint8_t kAddr_mcp4728[] = {0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67};
static constexpr uint8_t kAddr_pcf8591[] = {0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F};
static constexpr uint8_t kAddr_rda5807m[] = {0x10};
static constexpr uint8_t kAddr_pcf8576[] = {0x38, 0x39};
static constexpr uint8_t kAddr_aht21[] = {0x38};
static constexpr uint8_t kAddr_bme280[] = {0x76, 0x77};
static constexpr uint32_t kExp_bme280[] = {0x60};
static constexpr IdProbe kProbe_bme280 = {0xD0, 1, 1, false, 0xFF, kExp_bme280, 1};
static constexpr uint8_t kAddr_bme680[] = {0x76, 0x77};
static constexpr uint32_t kExp_bme680[] = {0x61};
static constexpr IdProbe kProbe_bme680 = {0xD0, 1, 1, false, 0xFF, kExp_bme680, 1};
static constexpr uint8_t kAddr_ens160[] = {0x52, 0x53};
static constexpr uint32_t kExp_ens160[] = {0x0160};
static constexpr IdProbe kProbe_ens160 = {0x00, 1, 2, true, 0xFFFF, kExp_ens160, 1};
static constexpr uint8_t kAddr_neo_6[] = {0x42};
static constexpr uint8_t kAddr_l3g4200d[] = {0x68, 0x69};
static constexpr uint32_t kExp_l3g4200d[] = {0xD3};
static constexpr IdProbe kProbe_l3g4200d = {0x0F, 1, 1, false, 0xFF, kExp_l3g4200d, 1};
static constexpr uint8_t kAddr_l3gd20h[] = {0x6A, 0x6B};
static constexpr uint32_t kExp_l3gd20h[] = {0xD4, 0xD7};
static constexpr IdProbe kProbe_l3gd20h = {0x0F, 1, 1, false, 0xFF, kExp_l3gd20h, 2};
static constexpr uint8_t kAddr_mpu6050[] = {0x68, 0x69};
static constexpr uint32_t kExp_mpu6050[] = {0x68};
static constexpr IdProbe kProbe_mpu6050 = {0x75, 1, 1, false, 0x7E, kExp_mpu6050, 1};
static constexpr uint8_t kAddr_icm20948[] = {0x68, 0x69};
static constexpr uint32_t kExp_icm20948[] = {0xEA};
static constexpr IdProbe kProbe_icm20948 = {0x00, 1, 1, false, 0xFF, kExp_icm20948, 1};
static constexpr uint8_t kAddr_mpu9250[] = {0x68, 0x69};
static constexpr uint32_t kExp_mpu9250[] = {0x71};
static constexpr IdProbe kProbe_mpu9250 = {0x75, 1, 1, false, 0xFF, kExp_mpu9250, 1};
static constexpr uint8_t kAddr_mpu9255[] = {0x68, 0x69};
static constexpr uint32_t kExp_mpu9255[] = {0x73};
static constexpr IdProbe kProbe_mpu9255 = {0x75, 1, 1, false, 0xFF, kExp_mpu9255, 1};
static constexpr uint8_t kAddr_mcp23008[] = {0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27};
static constexpr uint8_t kAddr_mcp23017[] = {0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27};
static constexpr uint8_t kAddr_pcf8574[] = {0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F};
static constexpr uint8_t kAddr_pcf8575[] = {0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27};
static constexpr uint8_t kAddr_apds_9930[] = {0x39};
static constexpr uint32_t kExp_apds_9930[] = {0x39};
static constexpr IdProbe kProbe_apds_9930 = {0x92, 1, 1, false, 0xFF, kExp_apds_9930, 1};
static constexpr uint8_t kAddr_apds9960[] = {0x39};
static constexpr uint32_t kExp_apds9960[] = {0xAB};
static constexpr IdProbe kProbe_apds9960 = {0x92, 1, 1, false, 0xFF, kExp_apds9960, 1};
static constexpr uint8_t kAddr_as5600[] = {0x36};
static constexpr uint8_t kAddr_hmc5883l[] = {0x1E};
static constexpr uint32_t kExp_hmc5883l[] = {0x483433};
static constexpr IdProbe kProbe_hmc5883l = {0x0A, 1, 3, false, 0xFFFFFF, kExp_hmc5883l, 1};
static constexpr uint8_t kAddr_24aa02uid[] = {0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57};
static constexpr uint8_t kAddr_24aa025uid[] = {0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57};
static constexpr uint8_t kAddr_mb85rc[] = {0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57};
static constexpr uint8_t kAddr_drv2605[] = {0x5A};
static constexpr uint32_t kExp_drv2605[] = {0x60};
static constexpr IdProbe kProbe_drv2605 = {0x00, 1, 1, false, 0xE0, kExp_drv2605, 1};
static constexpr uint8_t kAddr_drv8830[] = {0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68};
static constexpr uint8_t kAddr_mpr121[] = {0x5A, 0x5B, 0x5C, 0x5D};
static constexpr uint8_t kAddr_ade7953[] = {0x38};
static constexpr uint8_t kAddr_ina219[] = {0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F};
static constexpr uint8_t kAddr_ina226[] = {0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F};
static constexpr uint32_t kExp_ina226[] = {0x2260};
static constexpr IdProbe kProbe_ina226 = {0xFF, 1, 2, false, 0xFFFF, kExp_ina226, 1};
static constexpr uint8_t kAddr_ina3221[] = {0x40, 0x41, 0x42, 0x43};
static constexpr uint32_t kExp_ina3221[] = {0x3220};
static constexpr IdProbe kProbe_ina3221 = {0xFF, 1, 2, false, 0xFFFF, kExp_ina3221, 1};
static constexpr uint8_t kAddr_bmp085[] = {0x77};
static constexpr uint32_t kExp_bmp085[] = {0x55};
static constexpr IdProbe kProbe_bmp085 = {0xD0, 1, 1, false, 0xFF, kExp_bmp085, 1};
static constexpr uint8_t kAddr_bmp180[] = {0x77};
static constexpr uint32_t kExp_bmp180[] = {0x55};
static constexpr IdProbe kProbe_bmp180 = {0xD0, 1, 1, false, 0xFF, kExp_bmp180, 1};
static constexpr uint8_t kAddr_bmp280[] = {0x76, 0x77};
static constexpr uint32_t kExp_bmp280[] = {0x58};
static constexpr IdProbe kProbe_bmp280 = {0xD0, 1, 1, false, 0xFF, kExp_bmp280, 1};
static constexpr uint8_t kAddr_bmp384[] = {0x76, 0x77};
static constexpr uint32_t kExp_bmp384[] = {0x50};
static constexpr IdProbe kProbe_bmp384 = {0x00, 1, 1, false, 0xFF, kExp_bmp384, 1};
static constexpr uint8_t kAddr_bmp581[] = {0x46, 0x47};
static constexpr uint32_t kExp_bmp581[] = {0x50};
static constexpr IdProbe kProbe_bmp581 = {0x01, 1, 1, false, 0xFF, kExp_bmp581, 1};
static constexpr uint8_t kAddr_lps22df[] = {0x5C, 0x5D};
static constexpr uint32_t kExp_lps22df[] = {0xB4};
static constexpr IdProbe kProbe_lps22df = {0x0F, 1, 1, false, 0xFF, kExp_lps22df, 1};
static constexpr uint8_t kAddr_lps28dfw[] = {0x5C, 0x5D};
static constexpr uint32_t kExp_lps28dfw[] = {0xB4};
static constexpr IdProbe kProbe_lps28dfw = {0x0F, 1, 1, false, 0xFF, kExp_lps28dfw, 1};
static constexpr uint8_t kAddr_lps33hw[] = {0x5C, 0x5D};
static constexpr uint32_t kExp_lps33hw[] = {0xB1};
static constexpr IdProbe kProbe_lps33hw = {0x0F, 1, 1, false, 0xFF, kExp_lps33hw, 1};
static constexpr uint8_t kAddr_mfrc522[] = {0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F};
static constexpr uint32_t kExp_mfrc522[] = {0x91, 0x92};
static constexpr IdProbe kProbe_mfrc522 = {0x37, 1, 1, false, 0xFF, kExp_mfrc522, 2};
static constexpr uint8_t kAddr_ds3231[] = {0x68};
static constexpr uint8_t kAddr_pcf8523[] = {0x68};
static constexpr uint8_t kAddr_mcp9808[] = {0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F};
static constexpr uint32_t kExp_mcp9808[] = {0x0400};
static constexpr IdProbe kProbe_mcp9808 = {0x07, 1, 2, false, 0xFF00, kExp_mcp9808, 1};
static constexpr uint8_t kAddr_tmp117[] = {0x48, 0x49, 0x4A, 0x4B};
static constexpr uint32_t kExp_tmp117[] = {0x0117};
static constexpr IdProbe kProbe_tmp117 = {0x0F, 1, 2, false, 0x0FFF, kExp_tmp117, 1};
static constexpr uint8_t kAddr_vl53l0x[] = {0x29};
static constexpr uint32_t kExp_vl53l0x[] = {0xEE};
static constexpr IdProbe kProbe_vl53l0x = {0xC0, 1, 1, false, 0xFF, kExp_vl53l0x, 1};
static constexpr uint8_t kAddr_vl53l1x[] = {0x29};
static constexpr uint32_t kExp_vl53l1x[] = {0xEACC};
static constexpr IdProbe kProbe_vl53l1x = {0x10F, 2, 2, false, 0xFFFF, kExp_vl53l1x, 1};

static constexpr ChipEntry kChips[] = {
    {"adxl345", "adxl345", false, false, kAddr_adxl345, 2, &kProbe_adxl345},
    {"bma180", nullptr, false, false, kAddr_bma180, 2, &kProbe_bma180},
    {"lis3dh", nullptr, false, false, kAddr_lis3dh, 2, &kProbe_lis3dh},
    {"lsm303-accel", nullptr, false, false, kAddr_lsm303_accel, 1, nullptr},
    {"lsm303-mag", nullptr, false, false, kAddr_lsm303_mag, 1, &kProbe_lsm303_mag},
    {"mma8451q", nullptr, false, false, kAddr_mma8451q, 2, &kProbe_mma8451q},
    {"ad5243", nullptr, true, false, kAddr_ad5243, 1, nullptr},
    {"ad5248", nullptr, true, false, kAddr_ad5248, 4, nullptr},
    {"ads1013", nullptr, false, false, kAddr_ads1013, 4, nullptr},
    {"ads1014", nullptr, false, false, kAddr_ads1014, 4, nullptr},
    {"ads1015", nullptr, false, false, kAddr_ads1015, 4, nullptr},
    {"mcp4725", "mcp4725", true, false, kAddr_mcp4725, 2, nullptr},
    {"mcp4728", "mcp4728", true, false, kAddr_mcp4728, 8, nullptr},
    {"pcf8591", "pcf8591", true, false, kAddr_pcf8591, 8, nullptr},
    {"rda5807m", "rda5807m", true, false, kAddr_rda5807m, 1, nullptr},
    {"pcf8576", "pcf8576", true, false, kAddr_pcf8576, 2, nullptr},
    {"aht21", "aht21", true, false, kAddr_aht21, 1, nullptr},
    {"bme280", "bme280", false, false, kAddr_bme280, 2, &kProbe_bme280},
    {"bme680", "bme680", false, false, kAddr_bme680, 2, &kProbe_bme680},
    {"ens160", "ens160", false, false, kAddr_ens160, 2, &kProbe_ens160},
    {"neo-6", "neo-6", true, false, kAddr_neo_6, 1, nullptr},
    {"l3g4200d", "l3g4200d", false, false, kAddr_l3g4200d, 2, &kProbe_l3g4200d},
    {"l3gd20h", "l3gd20h", false, false, kAddr_l3gd20h, 2, &kProbe_l3gd20h},
    {"mpu6050", "mpu6050", false, false, kAddr_mpu6050, 2, &kProbe_mpu6050},
    {"icm20948", nullptr, false, false, kAddr_icm20948, 2, &kProbe_icm20948},
    {"mpu9250", "mpu9250", false, false, kAddr_mpu9250, 2, &kProbe_mpu9250},
    {"mpu9255", "mpu9255", false, false, kAddr_mpu9255, 2, &kProbe_mpu9255},
    {"mcp23008", nullptr, false, false, kAddr_mcp23008, 8, nullptr},
    {"mcp23017", "mcp23017", false, false, kAddr_mcp23017, 8, nullptr},
    {"pcf8574", "pcf8574", true, false, kAddr_pcf8574, 16, nullptr},
    {"pcf8575", "pcf8575", true, false, kAddr_pcf8575, 8, nullptr},
    {"apds-9930", "apds-9930", false, false, kAddr_apds_9930, 1, &kProbe_apds_9930},
    {"apds9960", "apds9960", false, false, kAddr_apds9960, 1, &kProbe_apds9960},
    {"as5600", "as5600", false, false, kAddr_as5600, 1, nullptr},
    {"hmc5883l", "hmc5883l", false, false, kAddr_hmc5883l, 1, &kProbe_hmc5883l},
    {"24aa02uid", "24aa02uid", false, true, kAddr_24aa02uid, 8, nullptr},
    {"24aa025uid", nullptr, false, false, kAddr_24aa025uid, 8, nullptr},
    {"mb85rc", nullptr, false, false, kAddr_mb85rc, 8, nullptr},
    {"drv2605", nullptr, false, false, kAddr_drv2605, 1, &kProbe_drv2605},
    {"drv8830", "drv8830", false, false, kAddr_drv8830, 9, nullptr},
    {"mpr121", "mpr121", false, false, kAddr_mpr121, 4, nullptr},
    {"ade7953", "ade7953", true, false, kAddr_ade7953, 1, nullptr},
    {"ina219", "ina219", false, false, kAddr_ina219, 16, nullptr},
    {"ina226", "ina226", false, false, kAddr_ina226, 16, &kProbe_ina226},
    {"ina3221", "ina3221", false, false, kAddr_ina3221, 4, &kProbe_ina3221},
    {"bmp085", "bmp085", false, false, kAddr_bmp085, 1, &kProbe_bmp085},
    {"bmp180", "bmp180", false, false, kAddr_bmp180, 1, &kProbe_bmp180},
    {"bmp280", "bmp280", false, false, kAddr_bmp280, 2, &kProbe_bmp280},
    {"bmp384", "bmp384", false, false, kAddr_bmp384, 2, &kProbe_bmp384},
    {"bmp581", "bmp581", false, false, kAddr_bmp581, 2, &kProbe_bmp581},
    {"lps22df", "lps22df", false, false, kAddr_lps22df, 2, &kProbe_lps22df},
    {"lps28dfw", "lps28dfw", false, false, kAddr_lps28dfw, 2, &kProbe_lps28dfw},
    {"lps33hw", "lps33hw", false, false, kAddr_lps33hw, 2, &kProbe_lps33hw},
    {"mfrc522", "mfrc522", false, false, kAddr_mfrc522, 8, &kProbe_mfrc522},
    {"ds3231", "ds3231", false, false, kAddr_ds3231, 1, nullptr},
    {"pcf8523", "pcf8523", false, false, kAddr_pcf8523, 1, nullptr},
    {"mcp9808", "mcp9808", false, false, kAddr_mcp9808, 8, &kProbe_mcp9808},
    {"tmp117", "tmp117", false, false, kAddr_tmp117, 4, &kProbe_tmp117},
    {"vl53l0x", "vl53l0x", false, false, kAddr_vl53l0x, 1, &kProbe_vl53l0x},
    {"vl53l1x", "vl53l1x", false, false, kAddr_vl53l1x, 1, &kProbe_vl53l1x},
};
static constexpr size_t kChipCount = 60;

}  // namespace discovery
}  // namespace periph
#endif  // __linux__
