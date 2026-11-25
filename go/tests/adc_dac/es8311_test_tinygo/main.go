//go:build tinygo

// ES8311 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an ES8311 on I2C1 (GP4 = SDA, GP5 = SCL). Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x18, nil, nil)
	chip, err := adcdac.NewES8311Full(conn, 12288000, 48000)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}
	check("init_accepted", true)

	must(chip.SetVolume(-20))
	v, _ := chip.GetVolume()
	check("set_volume_get_volume_round_trip", v == -20)
	must(chip.SetMicGain(24))
	g, _ := chip.GetMicGain()
	check("set_mic_gain_get_mic_gain_round_trip", g == 24)
	must(chip.SetMute(true))
	r, _ := chip.ReadRegister(0x31)
	check("dac_mute_bits_set", r&0x60 == 0x60)
	must(chip.SetMute(false))
	r, _ = chip.ReadRegister(0x31)
	check("dac_mute_bits_cleared", r&0x60 == 0x00)
	check("configure_clock_accepted", chip.ConfigureClock(12288000, 16000, false, false) == nil)
	check("configure_serial_accepted", chip.ConfigureSerial(adcdac.ES8311SerialConfig{}) == nil)
	r, _ = chip.ReadRegister(0x09)
	check("serial_format_word_length_read_back", r&0x1F == 0x0C)
	must(chip.SetADCVolume(0))
	r, _ = chip.ReadRegister(0x17)
	check("adc_volume_0db", r == 0xBF)
	must(chip.ConfigureALC(adcdac.ES8311ALCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -18.1}))
	r, _ = chip.ReadRegister(0x18)
	check("alc_enabled_bit", r&0x80 == 0x80)
	must(chip.ConfigureALC(adcdac.DefaultES8311ALCConfig()))
	must(chip.ConfigureDRC(adcdac.ES8311DRCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -24.1}))
	r, _ = chip.ReadRegister(0x34)
	check("drc_enabled_bit", r&0x80 == 0x80)
	must(chip.ConfigureDRC(adcdac.ES8311DRCConfig{}))
	must(chip.SetLoopback(true))
	r, _ = chip.ReadRegister(0x44)
	check("loopback_bit_set", r&0x80 == 0x80)
	must(chip.SetLoopback(false))
	must(chip.SetAdcdatMode(adcdac.ES8311AdcdatDACADC))
	r, _ = chip.ReadRegister(0x44)
	check("adcdat_mode_dac_adc", (r>>4)&7 == 4)
	must(chip.SetAdcdatMode(adcdac.ES8311AdcdatADCADC))
	must(chip.SetDACRamp(8))
	r, _ = chip.ReadRegister(0x37)
	check("dac_ramp_rate_8", r>>4 == 8)
	must(chip.PowerDown())
	r, _ = chip.ReadRegister(0x00)
	check("power_down_clears_csm_on", r&0x80 == 0x00)
	must(chip.Reset())
	check("reset_accepted", true)

	time.Sleep(50 * time.Millisecond)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
