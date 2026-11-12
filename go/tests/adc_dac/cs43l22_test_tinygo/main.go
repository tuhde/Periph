//go:build tinygo

// CS43L22 hardware test — TinyGo (Raspberry Pi Pico W).
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.CS43L22I2CAddress, nil, nil)
	defer conn.Close()

	dac, err := adcdac.NewCS43L22Full(conn, nil)
	if err != nil {
		fail("new", err)
		return
	}
	check("init", dac.Init() == nil)
	id, _, _ := dac.ReadID()
	check("read_id", id == 0x1C)
	dac.SetVolume(-20)
	v, _ := dac.GetVolume()
	check("volume_roundtrip", v > -20.01 && v < -19.99)
	dac.SetVolumeChannels(-10, -12)
	r20, _ := dac.ReadRegister(0x20)
	r21, _ := dac.ReadRegister(0x21)
	check("volume_channels", r20 == 0xEC && r21 == 0xE8)
	dac.SetHeadphoneGain(adcdac.CS43L22Gain1000)
	g, _ := dac.HeadphoneGain()
	check("headphone_gain", g == 1.0)
	dac.Mute(true)
	m, _ := dac.ReadRegister(0x0F)
	check("mute", m&0xC0 == 0xC0)
	dac.Mute(false)
	m, _ = dac.ReadRegister(0x0F)
	check("unmute", m&0xC0 == 0)
	dac.SetTone(6, 6, 0, 0, true)
	tone, _ := dac.ReadRegister(0x1F)
	check("tone", tone == 0x44)
	l := adcdac.DefaultCS43L22Limiter()
	l.Enabled, l.MaxDB, l.CushionDB = true, -6, -12
	dac.SetLimiter(l)
	lim, _ := dac.ReadRegister(0x28)
	check("limiter", lim&0x80 == 0x80)
	dac.SetLimiter(adcdac.DefaultCS43L22Limiter())
	_, err = dac.ReadSpeakerStatus()
	check("speaker_status_readable", err == nil)
	dac.Stop()
	pwr, _ := dac.ReadRegister(0x02)
	check("stop_powers_down", pwr == 0x9F)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
