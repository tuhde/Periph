//go:build linux && !tinygo

// PCF8523 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, rtc.PCF8523Address, nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	clock, err := rtc.NewPCF8523Full(conn) // Create PCF8523 Full driver, (conn) → (*PCF8523Full, error)
	must(err)                              // enables battery switch-over standard mode (PM=000)

	must(clock.SetDatetime(2026, 9, 23, 3, 14, 30, 0)) // Set clock/calendar, (year, month, day, weekday 0=Sun, hour, minute, second) → error
	// STOP-bit precision start; forces 24-hour mode and clears OS
	year, month, day, weekday, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
	must(err)                                                                   // decodes the seven BCD clock/calendar registers
	stopped, err := clock.OscillatorStopped()                                   // Query oscillator-stop flag, () → (bool, error)
	must(err)                                                                   // true means the time may be invalid until SetDatetime

	must(clock.SetAlarm(rtc.PCF8523Alarm{Minute: 0, Hour: 9, Day: rtc.PCF8523AlarmDisabled, Weekday: rtc.PCF8523AlarmDisabled})) // Configure alarm, (PCF8523Alarm{Minute, Hour, Day, Weekday}) → error
	// fires daily at 09:00; PCF8523AlarmDisabled fields are ignored
	alarm, err := clock.GetAlarm() // Read alarm, () → (PCF8523Alarm, error)
	must(err)                      // disabled fields decode as PCF8523AlarmDisabled

	must(clock.ConfigureTimerA(rtc.PCF8523TimerACountdown, 10, rtc.PCF8523Clock1Hz, false)) // Start Timer A, (mode, value 0–255, sourceClock, pulsed) → error
	// counts down 10 s, then sets CTAF
	remainingA, err := clock.ReadTimerA() // Read Timer A counter, () → (uint8, error)
	must(err)                             // live value, not the loaded one
	must(clock.DisableTimerA())           // Stop Timer A, () → error

	must(clock.ConfigureTimerB(30, rtc.PCF8523Clock1Hz, 62.5, true)) // Start Timer B, (value 0–255, sourceClock, pulseWidthMs ms, pulsed) → error
	// 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
	remainingB, err := clock.ReadTimerB() // Read Timer B counter, () → (uint8, error)
	must(err)
	must(clock.DisableTimerB()) // Stop Timer B, () → error

	must(clock.SetClockOutput(1)) // Drive CLKOUT, (frequencyHz Hz) → error
	// 1 Hz square wave on the shared INT1/CLKOUT pin
	must(clock.DisableClockOutput()) // Disable CLKOUT, () → error
	// frees INT1 for interrupts

	must(clock.SetOffset(-3, rtc.PCF8523OffsetEveryTwoHours)) // Write offset calibration, (offset −64–63, mode) → error
	// −3 LSB × 4.34 ppm = −13.02 ppm correction
	offset, offsetMode, err := clock.GetOffset() // Read offset calibration, () → (int8, PCF8523OffsetMode, error)
	must(err)

	must(clock.ConfigureBatteryBackup(rtc.PCF8523BatteryStandard, true)) // Select battery switch-over, (mode, lowDetection) → error
	// switches to VBAT when VDD < VBAT and VDD < 2.5 V
	switched, err := clock.IsBatterySwitchedOver() // Query switch-over flag, () → (bool, error)
	must(err)
	must(clock.ClearBatterySwitchover()) // Clear switch-over flag, () → error
	low, err := clock.IsBatteryLow()     // Query battery-low flag, () → (bool, error)
	must(err)                            // read-only; clears itself once the cell is replaced

	must(clock.OnInterrupt(func(status uint8) { fmt.Printf("interrupt status=0x%02X\n", status) })) // Subscribe to interrupts, (callback) → error
	// falls back to a polling goroutine when no INT pin is wired
	must(clock.EnableInterrupt(rtc.PCF8523SourceAlarm | rtc.PCF8523SourceTimerB | rtc.PCF8523SourceBatteryLow)) // Enable sources, (source) → error
	// sets AIE, CTBIE and BLIE
	status, err := clock.PollInterrupt()                                                                                                   // Poll & clear flags, () → (uint8, error)
	must(err)                                                                                                                              // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
	must(clock.DisableInterrupt(rtc.PCF8523SourceAlarm | rtc.PCF8523SourceTimerA | rtc.PCF8523SourceTimerB | rtc.PCF8523SourceBatteryLow)) // Disable sources, (source) → error
	must(clock.OffInterrupt())                                                                                                             // Unsubscribe, () → error

	must(clock.SoftwareReset()) // Software reset, () → error
	// control registers back to POR (PM=111); time is kept

	fmt.Printf("%04d-%02d-%02d wd=%d %02d:%02d:%02d os_stopped=%v\n", year, month, day, weekday, hour, minute, second, stopped)
	fmt.Printf("alarm=%+v timerA=%d timerB=%d\n", alarm, remainingA, remainingB)
	fmt.Printf("offset=%d mode=%d switched=%v low=%v status=0x%02X\n", offset, offsetMode, switched, low, status)
}
