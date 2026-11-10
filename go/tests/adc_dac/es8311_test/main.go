//go:build linux && !tinygo

// ES8311 hardware test — Linux host.
//
// Drives the ES8311Full API and reports PASS/FAIL per check. Prints the standard
// ===DONE: ... === summary line at the end. Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x18"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := adcdac.NewES8311Full(conn, 12288000, 48000)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
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

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
