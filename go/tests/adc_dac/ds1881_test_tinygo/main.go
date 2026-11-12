//go:build tinygo

// DS1881 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	println(what+":", err)
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.DS1881I2CAddress, nil, nil)
	defer conn.Close()

	amp, err := adcdac.NewDS1881Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", amp != nil && amp.Init() == nil)
	amp.SetAttenuation(0, 20)
	amp.SetAttenuation(1, 33)
	p0, _ := amp.GetPosition(0)
	p1, _ := amp.GetPosition(1)
	check("position_roundtrip", p0 == 20 && p1 == 33)
	a0, _ := amp.GetAttenuation(0)
	check("attenuation_roundtrip", a0 == 20)
	amp.Mute(0)
	p0, _ = amp.GetPosition(0)
	check("mute", p0 == 63)
	amp.SetPosition(1, 5)
	p1, _ = amp.GetPosition(1)
	check("set_position", p1 == 5)

	full, err := adcdac.NewDS1881Full(conn)
	if err != nil {
		fail("init full", err)
		return
	}
	check("init_full", full.Init() == nil)
	cfg, _ := full.GetConfig()
	check("config_default", cfg.Option == 1 && cfg.ZeroCrossing && !cfg.Nonvolatile)
	full.SetBoth(10, 40)
	r0, r1, _, _ := full.ReadAll()
	check("set_both", r0 == 10 && r1 == 40)
	full.SetAttenuationBoth(15)
	r0, r1, _, _ = full.ReadAll()
	check("set_attenuation_both", r0 == 15 && r1 == 15)
	full.SetZeroCrossing(false)
	cfg, _ = full.GetConfig()
	check("zero_crossing_off", !cfg.ZeroCrossing)
	full.SetZeroCrossing(true)
	full.SetOption(2)
	cfg, _ = full.GetConfig()
	check("option2", cfg.Option == 2 && full.MaxPosition() == 32)
	full.SetBoth(13, 33)
	r0, r1, _, _ = full.ReadAll()
	check("option2_positions", r0 == 13 && r1 == 33 && full.PositionToDB(13) == 14)
	full.SetOption(1)
	cfg, _ = full.GetConfig()
	check("option1_again", cfg.Option == 1)
	full.Reset()
	r0, r1, _, _ = full.ReadAll()
	check("reset_mutes", r0 == 63 && r1 == 63)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
