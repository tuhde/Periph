//go:build tinygo

// DS3231 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, 0x68, nil, nil)
	defer conn.Close()

	clock, err := rtc.NewDS3231Full(conn) // Create DS3231 Full driver, (conn) → (*DS3231Full, error)
	if err != nil {
		panic(err)
	}

	if err := clock.SetDatetime(2026, 9, 22, 2, 14, 30, 0); err != nil { // Set clock/calendar, (year, month, day, weekday, hour, minute, second) → error
		panic(err)
	}
	// writes all seven registers, forces 24-hour mode, clears OSF

	year, month, day, weekday, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (int, int, int, int, int, int, int, error)
	if err != nil {
		panic(err)
	}
	// decodes the seven BCD clock/calendar registers

	if err := clock.SetAlarm1(0, 0, 12, 0, rtc.DS3231Alarm1MatchHoursMinutesSeconds); err != nil { // Set alarm 1, (second, minute, hour, day_or_date, mode) → error
		panic(err)
	}
	// fires once per day at 12:00:00

	a1Second, a1Minute, a1Hour, a1Day, a1Mode, err := clock.GetAlarm1() // Read alarm 1, () → (int, int, int, int, DS3231Alarm1Mode, error)
	if err != nil {
		panic(err)
	}
	// decodes 0x07-0x0A

	if err := clock.SetAlarm2(0, 0, 0, rtc.DS3231Alarm2EveryMinute); err != nil { // Set alarm 2, (minute, hour, day_or_date, mode) → error
		panic(err)
	}
	// fires once per minute at :00 seconds

	if err := clock.EnableInterrupt(rtc.DS3231SourceAlarm1); err != nil { // Enable alarm interrupt, (source) → error
		panic(err)
	}
	// sets A1IE and INTCN=1 on CONTROL

	status, err := clock.PollInterrupt() // Poll and clear alarm flags, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	// reads CONTROL_STATUS; clears A1F/A2F, preserves OSF/EN32kHz/BSY

	if err := clock.DisableInterrupt(rtc.DS3231SourceAlarm1); err != nil { // Disable alarm interrupt, (source) → error
		panic(err)
	}
	// clears A1IE

	if err := clock.EnableSquareWave(1024, false); err != nil { // Enable square wave, (rate_hz=1024, battery_backed=false) → error
		panic(err)
	}
	// sets INTCN=0, RS2:RS1 for 1.024 kHz

	if err := clock.DisableSquareWave(); err != nil { // Disable square wave, () → error
		panic(err)
	}
	// sets INTCN=1, returning INT/SQW to interrupt mode

	enabled, err := clock.Is32kHzEnabled() // Read 32kHz output state, () → (bool, error)
	if err != nil {
		panic(err)
	}
	// reads EN32kHz

	if err := clock.Enable32kHzOutput(); err != nil { // Enable 32kHz output, () → error
		panic(err)
	}
	if err := clock.Disable32kHzOutput(); err != nil { // Disable 32kHz output, () → error
		panic(err)
	}

	stopped, err := clock.OscillatorStopped() // Read oscillator-stop flag, () → (bool, error)
	if err != nil {
		panic(err)
	}
	// reads OSF; true means timekeeping data may be invalid

	if err := clock.ClearOscillatorStopped(); err != nil { // Clear oscillator-stop flag, () → error
		panic(err)
	}

	if err := clock.EnableBatteryOscillator(); err != nil { // Enable battery-backed oscillator, () → error
		panic(err)
	}
	if err := clock.DisableBatteryOscillator(); err != nil { // Disable battery-backed oscillator, () → error
		panic(err)
	}
	// clears/sets EOSC

	if err := clock.ForceTemperatureConversion(); err != nil { // Force temperature conversion, () → error
		panic(err)
	}
	// polls BSY until clear, max 200 ms

	temp, err := clock.ReadTemperature() // Read temperature, () → (float32, error) °C
	if err != nil {
		panic(err)
	}

	offset, err := clock.GetAgingOffset() // Read aging offset, () → (int8, error)
	if err != nil {
		panic(err)
	}
	// raw signed trim code, no fixed physical unit

	if err := clock.SetAgingOffset(offset); err != nil { // Write aging offset, (offset) → error
		panic(err)
	}

	println(year, "-", month, "-", day, " wd=", weekday, " ", hour, ":", minute, ":", second, "  ", temp, "C")
	println("alarm1: s=", a1Second, " m=", a1Minute, " h=", a1Hour, " d=", a1Day, " mode=", int(a1Mode))
	println("interrupt_status=", status, " 32khz=", enabled, " osf=", stopped, " aging=", offset)
}
