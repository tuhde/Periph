//go:build tinygo

// ENS160 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the ENS160Full API: status, individual gas
// readings, raw sensor resistance, firmware version, interrupt
// configuration, and sleep/wake control.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x53, nil, nil)        // Create I2C connection, (i2c, addr=0x53) → (*I2CConnection)
	chip, err := gas.NewENS160Full(conn)                 // Create ENS160 driver, (connection) → (*ENS160Full, error)
	if err != nil {
		panic(err)
	}

	status, err := chip.Status() // Read validity flag, () → (int, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("status: %d\n", status) // 0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output

	aqi, tvoc, eco2, err := chip.ReadAirQuality() // Read air quality, () → (int, float32 ppb, float32 ppm, error)
	if err != nil {
		fmt.Printf("read_air_quality: %v\n", err)
	} else {
		fmt.Printf("aqi: %d  tvoc: %.0f ppb  eco2: %.0f ppm\n", aqi, tvoc, eco2) // 5-byte burst from 0x21
	}

	if err := chip.SetCompensation(25.0, 50.0); err != nil { // Set T/RH compensation, (temp_celsius, rh_percent) → error
		panic(err)
	}
	// TEMP_IN = round((25+273.15)*64) = 19082; RH_IN = round(50*512) = 25600

	tvoc, err = chip.ReadTVOC() // Read TVOC only, () → (float32 ppb, error)
	if err != nil {
		fmt.Printf("read_tvoc: %v\n", err)
	} else {
		fmt.Printf("tvoc: %.0f ppb\n", tvoc) // 2-byte little-endian from 0x22
	}

	eco2, err = chip.ReadECO2() // Read eCO2 only, () → (float32 ppm, error)
	if err != nil {
		fmt.Printf("read_eco2: %v\n", err)
	} else {
		fmt.Printf("eco2: %.0f ppm\n", eco2) // 2-byte little-endian from 0x24
	}

	aqiOnly, err := chip.ReadAQI() // Read AQI only, () → (int, error)
	if err != nil {
		fmt.Printf("read_aqi: %v\n", err)
	} else {
		fmt.Printf("aqi_only: %d\n", aqiOnly) // bits 2:0 of 0x21
	}

	ethanol, err := chip.ReadEthanol() // Read ethanol estimate, () → (float32 ppb, error)
	if err != nil {
		fmt.Printf("read_ethanol: %v\n", err)
	} else {
		fmt.Printf("ethanol: %.0f ppb\n", ethanol) // alias of DATA_TVOC at 0x22
	}

	r1, err := chip.ReadRawResistance(1) // Read raw resistance, (sensor=1|4) → (float32 ohm, error)
	if err != nil {
		fmt.Printf("read_raw_resistance(1): %v\n", err)
	} else {
		fmt.Printf("raw_resistance_1: %.2f ohm\n", r1) // GPR_READ[0:1], 2^(raw/2048)
	}

	r4, err := chip.ReadRawResistance(4) // Read raw resistance, (sensor=1|4) → (float32 ohm, error)
	if err != nil {
		fmt.Printf("read_raw_resistance(4): %v\n", err)
	} else {
		fmt.Printf("raw_resistance_4: %.2f ohm\n", r4) // GPR_READ[6:7], 2^(raw/2048)
	}

	tAct, rhAct, err := chip.ReadCompensationActuals() // Read compensation actuals, () → (float32 °C, float32 %RH, error)
	if err != nil {
		fmt.Printf("read_compensation_actuals: %v\n", err)
	} else {
		fmt.Printf("compensation_actuals: %.2f C, %.2f %%RH\n", tAct, rhAct) // DATA_T (0x30) and DATA_RH (0x32)
	}

	major, minor, release, err := chip.GetFirmwareVersion() // Query firmware version, () → (int, int, int, error)
	if err != nil {
		fmt.Printf("get_firmware_version: %v\n", err)
	} else {
		fmt.Printf("firmware: %d.%d.%d\n", major, minor, release) // GET_APPVER → GPR_READ[4:6]
	}

	if err := chip.ConfigureInterrupt(false, false, false, false, false); err != nil { // Configure INTn, (enabled, activeHigh, pushPull, onData, onGPR) → error
		panic(err)
	}
	// writes CONFIG (0x11)

	if err := chip.Sleep(); err != nil { // Enter DEEP SLEEP, () → error
		panic(err)
	}
	// OPMODE = 0x00

	if err := chip.Wake(); err != nil { // Return to STANDARD mode, () → error
		panic(err)
	}
	// OPMODE = 0x01 then 0x02

	time.Sleep(100 * time.Millisecond)
}
