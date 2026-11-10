//go:build tinygo

// PCF85063A complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, rtc.PCF85063AAddress, nil, nil) // Create I2C connection, (i2c, addr=0x51) → *I2CConnection
	defer conn.Close()

	clock, err := rtc.NewPCF85063AFull(conn) // Create PCF85063A Full driver, (conn) → (*PCF85063AFull, error)
	must(err)
	// bus check, forces 24-hour mode with STOP = 0

	must(clock.SetDatetime(2026, 9, 23, 3, 14, 30, 0)) // Set clock/calendar, (year 2000–2099, month, day, weekday 0=Sun, hour, minute, second) → error
	// STOP-bit precision start; clears OS
	year, month, day, weekday, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
	must(err)
	// decodes the seven BCD clock/calendar registers
	stopped, err := clock.OscillatorStopped() // Query oscillator-stop flag, () → (bool, error)
	must(err)
	// true means the time may be invalid until SetDatetime
	must(clock.ClearOscillatorStop()) // Clear oscillator-stop flag, () → error
	// still true afterwards means the crystal is not running
	must(clock.StopClock()) // Freeze the clock, () → error
	// STOP = 1, divider chain held in reset
	must(clock.StartClock()) // Release the clock, () → error

	must(clock.SetAlarm(rtc.PCF85063AAlarm{Second: 0, Minute: 0, Hour: 9, Day: rtc.PCF85063AAlarmDisabled, Weekday: rtc.PCF85063AAlarmDisabled})) // Configure alarm, (PCF85063AAlarm{Second, Minute, Hour, Day, Weekday}) → error
	// fires daily at 09:00:00; PCF85063AAlarmDisabled fields are ignored
	alarm, err := clock.GetAlarm() // Read alarm, () → (PCF85063AAlarm, error)
	must(err)
	// disabled fields decode as PCF85063AAlarmDisabled

	must(clock.ConfigureTimer(10, rtc.PCF85063AClock1Hz, false)) // Start countdown timer, (value 1–255, sourceClock, pulsed) → error
	// counts down 10 s, then sets TF
	remaining, err := clock.ReadTimer() // Read timer counter, () → (uint8, error)
	must(err)
	// live value, not the loaded one
	must(clock.DisableTimer()) // Stop timer, () → error
	// TE = 0, TIE = 0, 1/60 Hz clock for power saving

	must(clock.SetMinuteInterrupt(rtc.PCF85063AMinuteEvery)) // Select minute interrupt, (mode Off|Every|Half) → error
	// sets MI; reported as PCF85063ASourceTimer

	must(clock.SetClockOutput(1)) // Drive CLKOUT, (frequencyHz) → error
	// 1 Hz square wave on the CLKOUT pin
	must(clock.DisableClockOutput()) // Disable CLKOUT, () → error
	// COF = 111, pin low

	must(clock.SetOffset(-3, rtc.PCF85063AOffsetNormal)) // Write offset calibration, (offset −64–63, mode) → error
	// −3 LSB × 4.34 ppm = −13.02 ppm correction
	offset, offsetMode, err := clock.GetOffset() // Read offset calibration, () → (int8, PCF85063AOffsetMode, error)
	must(err)

	must(clock.SetOscillatorLoad(12.5)) // Select crystal load, (picofarads 7|12.5) → error
	// match the crystal's specified load capacitance
	must(clock.WriteRAM(0xA5))  // Write RAM byte, (value 0–255) → error
	ram, err := clock.ReadRAM() // Read RAM byte, () → (uint8, error)
	must(err)

	must(clock.OnInterrupt(func(status uint8) { // Subscribe to interrupts, (callback) → error
		fmt.Printf("interrupt status=0x%02X\n", status)
	}))
	// falls back to a polling goroutine when no INT pin is wired
	must(clock.EnableInterrupt(rtc.PCF85063ASourceAlarm | rtc.PCF85063ASourceTimer)) // Enable sources, (source) → error
	// sets AIE and TIE
	status, err := clock.PollInterrupt() // Poll & clear flags, () → (uint8, error)
	must(err)
	// clears AF/TF, returns the pre-clear mask
	must(clock.DisableInterrupt(rtc.PCF85063ASourceAlarm | rtc.PCF85063ASourceTimer | rtc.PCF85063ASourceMinute | rtc.PCF85063ASourceHalfMinute | rtc.PCF85063ASourceCorrection)) // Disable sources, (source) → error
	must(clock.OffInterrupt())                                                                                                                                                    // Unsubscribe, () → error

	must(clock.SoftwareReset()) // Software reset, () → error
	// resets ALL registers including the time

	fmt.Printf("%04d-%02d-%02d %02d:%02d:%02d wd=%d os_stopped=%v\n", year, month, day, hour, minute, second, weekday, stopped)
	fmt.Printf("alarm %+v timer=%d ram=0x%02X\n", alarm, remaining, ram)
	fmt.Printf("offset=%d mode=%d status=0x%02X\n", offset, offsetMode, status)
}
