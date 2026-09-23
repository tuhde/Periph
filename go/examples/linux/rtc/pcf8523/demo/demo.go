//go:build linux && !tinygo

// PCF8523 demo example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

// Scheduling core of a battery-backed logger: reseeds the clock after a
// power loss, checks the coin cell, then wakes on an hourly alarm to print
// a timestamp while Timer B pulses a 30-second "still running" heartbeat
// on INT2 that toggles an LED.

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
	must(err)

	// --- Detect a lost time reference and reseed if needed ---
	// A fresh chip, or one whose backup cell was disconnected too long, reports
	// the OS flag set: its calendar cannot be trusted until it is reseeded.
	stopped, err := clock.OscillatorStopped() // Query oscillator-stop flag, () → (bool, error)
	must(err)
	if stopped {
		must(clock.SetDatetime(2026, 1, 1, 4, 0, 0, 0)) // Set clock/calendar, (year, month, day, weekday 0=Sun, hour, minute, second) → error
		fmt.Println("oscillator was stopped - reseeded from reference timestamp")
	}

	// --- Keep the clock alive through power cuts ---
	// Standard switch-over is already the driver default; it is repeated here
	// so the logger's power policy is explicit. A low coin cell is reported
	// once so it can be replaced before the next outage.
	must(clock.ConfigureBatteryBackup(rtc.PCF8523BatteryStandard, true)) // Select battery switch-over, (mode, lowDetection) → error
	low, err := clock.IsBatteryLow()                                     // Query battery-low flag, () → (bool, error)
	must(err)
	if low {
		fmt.Println("warning: backup battery low - replace the coin cell")
	}

	// --- Hourly wake-up plus a 30 s heartbeat ---
	// Only the minute field is enabled, so the alarm matches at hh:00 every
	// hour. Timer B reloads automatically and has its own INT2 pin, so the
	// heartbeat keeps running independently of the hourly alarm.
	must(clock.DisableClockOutput())                                                                                                                    // Disable CLKOUT, () → error
	must(clock.SetAlarm(rtc.PCF8523Alarm{Minute: 0, Hour: rtc.PCF8523AlarmDisabled, Day: rtc.PCF8523AlarmDisabled, Weekday: rtc.PCF8523AlarmDisabled})) // Configure alarm, (PCF8523Alarm{Minute, Hour, Day, Weekday}) → error
	must(clock.ConfigureTimerB(30, rtc.PCF8523Clock1Hz, 46.875, false))                                                                                 // Start Timer B, (value 0–255, sourceClock, pulseWidthMs ms, pulsed) → error

	// --- Dispatch by source: log on the alarm, blink on the heartbeat ---
	alarms := make(chan struct{}, 4)
	ledOn := false
	must(clock.OnInterrupt(func(status uint8) { // Subscribe to interrupts, (callback) → error
		if status&rtc.PCF8523SourceAlarm != 0 {
			year, month, day, _, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
			if err == nil {
				fmt.Printf("[hourly] %04d-%02d-%02d %02d:%02d:%02d\n", year, month, day, hour, minute, second)
			}
			alarms <- struct{}{}
		}
		if status&rtc.PCF8523SourceTimerB != 0 {
			ledOn = !ledOn
			fmt.Printf("[heartbeat] LED %v\n", ledOn)
		}
	}))
	must(clock.EnableInterrupt(rtc.PCF8523SourceAlarm | rtc.PCF8523SourceTimerB)) // Enable sources, (source) → error

	for i := 0; i < 3; i++ {
		<-alarms
	}

	// --- Leave the chip quiet on exit ---
	must(clock.DisableInterrupt(rtc.PCF8523SourceAlarm | rtc.PCF8523SourceTimerB)) // Disable sources, (source) → error
	must(clock.DisableTimerB())                                                    // Stop Timer B, () → error
	must(clock.OffInterrupt())                                                     // Unsubscribe, () → error
}
