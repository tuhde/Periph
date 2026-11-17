package power

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LC709203F command codes.
const (
	lc709203fRegBeforeRSOC   uint8 = 0x04
	lc709203fRegThermistorB  uint8 = 0x06
	lc709203fRegInitialRSOC  uint8 = 0x07
	lc709203fRegTemperature  uint8 = 0x08
	lc709203fRegVoltage      uint8 = 0x09
	lc709203fRegDirection    uint8 = 0x0A
	lc709203fRegAPA          uint8 = 0x0B
	lc709203fRegAPT          uint8 = 0x0C
	lc709203fRegRSOC         uint8 = 0x0D
	lc709203fRegITE          uint8 = 0x0F
	lc709203fRegICVersion    uint8 = 0x11
	lc709203fRegProfile      uint8 = 0x12
	lc709203fRegAlarmRSOC    uint8 = 0x13
	lc709203fRegAlarmVoltage uint8 = 0x14
	lc709203fRegPowerMode    uint8 = 0x15
	lc709203fRegStatus       uint8 = 0x16
	lc709203fRegProfileID    uint8 = 0x1A
)

// Current direction values for LC709203FFull.SetCurrentDirection.
const (
	LC709203FDirectionAuto      uint16 = 0x0000
	LC709203FDirectionCharge    uint16 = 0x0001
	LC709203FDirectionDischarge uint16 = 0xFFFF
)

// Alarm bitmask bits returned by LC709203FFull.PollInterrupt.
const (
	// LC709203FAlarmRSOC is set when RSOC is below the Alarm Low RSOC threshold.
	LC709203FAlarmRSOC uint8 = 0x01
	// LC709203FAlarmVoltage is set when the cell voltage is below the Alarm Low Cell Voltage threshold.
	LC709203FAlarmVoltage uint8 = 0x02
)

// ErrLC709203FCRC is returned when the CRC-8 of a read did not match, twice in a row.
var ErrLC709203FCRC = errors.New("lc709203f: CRC mismatch")

// ErrLC709203FInvalidArgument is returned for a value the chip does not accept.
var ErrLC709203FInvalidArgument = errors.New("lc709203f: invalid argument")

// LC709203FCRC8 computes the CRC-8 (ATM): polynomial 0x07, init 0x00, MSB first,
// no reflection, no final XOR.
func LC709203FCRC8(data []byte) byte {
	var crc byte
	for _, b := range data {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = crc<<1 ^ 0x07
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

var lc709203fAPATable = [6][2]int{{100, 0x08}, {200, 0x0B}, {500, 0x10}, {1000, 0x19}, {2000, 0x2D}, {3000, 0x36}}

// LC709203FAPAFor returns the APA value interpolated from the datasheet table
// (Type-01/03) for a design capacity in mAh, clamped to 100–3000 mAh.
func LC709203FAPAFor(capacityMah int) uint8 {
	t := lc709203fAPATable
	if capacityMah <= t[0][0] {
		return uint8(t[0][1])
	}
	if capacityMah >= t[5][0] {
		return uint8(t[5][1])
	}
	for i := 1; i < len(t); i++ {
		if capacityMah <= t[i][0] {
			c0, a0 := float64(t[i-1][0]), float64(t[i-1][1])
			c1, a1 := float64(t[i][0]), float64(t[i][1])
			return uint8(math.Round(a0 + (a1-a0)*(float64(capacityMah)-c0)/(c1-c0)))
		}
	}
	return uint8(t[5][1])
}

// LC709203FMinimal is the LC709203F 1-cell Li-ion fuel gauge driver — minimal interface.
//
// The constructor runs the datasheet starting flow (operational mode, APA,
// battery profile, I²C temperature mode at 25 °C). Every transfer carries a
// CRC-8 that the driver appends on writes and verifies on reads (one retry
// on a CRC mismatch or bus error).
type LC709203FMinimal struct {
	connection connection.RegisterConnection
}

// NewLC709203FMinimal creates a new LC709203FMinimal and runs the starting flow.
//
// connection must be an I²C connection bound to address 0x0B. capacityMah is
// the design capacity (clamped to 100–3000 mAh for the APA interpolation) and
// profile the battery profile for register 0x12 (0 or 1).
func NewLC709203FMinimal(connection connection.RegisterConnection, capacityMah int, profile uint8) (*LC709203FMinimal, error) {
	d := &LC709203FMinimal{connection: connection}
	d.wakeAndOperational()
	if err := d.writeWord(lc709203fRegAPA, uint16(LC709203FAPAFor(capacityMah))); err != nil {
		return nil, err
	}
	if err := d.writeWord(lc709203fRegProfile, uint16(profile&1)); err != nil {
		return nil, err
	}
	time.Sleep(100 * time.Millisecond) // TINIT: re-initialization like a battery insertion
	d.wakeAndOperational()
	if err := d.writeWord(lc709203fRegStatus, 0x0000); err != nil {
		return nil, err
	}
	if err := d.SetTemperature(25.0); err != nil {
		return nil, err
	}
	return d, nil
}

// wakeAndOperational writes operational mode twice, ignoring bus errors: the
// first transfer may be lost while the chip wakes up.
func (d *LC709203FMinimal) wakeAndOperational() {
	_ = d.writeWordOpt(lc709203fRegPowerMode, 0x0001, false)
	_ = d.writeWordOpt(lc709203fRegPowerMode, 0x0001, false)
	time.Sleep(time.Millisecond)
}

func (d *LC709203FMinimal) readWord(cmd uint8) (uint16, error) {
	var last error
	for attempt := 0; attempt < 2; attempt++ {
		buf, err := d.connection.ReadReg(uint32(cmd), 3)
		if err != nil {
			last = err
			continue
		}
		if LC709203FCRC8([]byte{0x16, cmd, 0x17, buf[0], buf[1]}) == buf[2] {
			return uint16(buf[0]) | uint16(buf[1])<<8, nil
		}
		last = ErrLC709203FCRC
	}
	return 0, last
}

func (d *LC709203FMinimal) writeWordOpt(cmd uint8, value uint16, retry bool) error {
	low, high := byte(value&0xFF), byte(value>>8)
	data := []byte{low, high, LC709203FCRC8([]byte{0x16, cmd, low, high})}
	err := d.connection.WriteReg(uint32(cmd), data)
	if err != nil && retry {
		err = d.connection.WriteReg(uint32(cmd), data)
	}
	return err
}

func (d *LC709203FMinimal) writeWord(cmd uint8, value uint16) error {
	return d.writeWordOpt(cmd, value, true)
}

// Voltage reads the cell voltage in volts (raw × 1 mV).
func (d *LC709203FMinimal) Voltage() (float32, error) {
	raw, err := d.readWord(lc709203fRegVoltage)
	if err != nil {
		return 0, err
	}
	return float32(raw) / 1000.0, nil
}

// Percent reads the state of charge from the ITE register in % (0.0–100.0).
func (d *LC709203FMinimal) Percent() (float32, error) {
	raw, err := d.readWord(lc709203fRegITE)
	if err != nil {
		return 0, err
	}
	return float32(raw) / 10.0, nil
}

// Temperature reads the cell temperature in °C.
func (d *LC709203FMinimal) Temperature() (float32, error) {
	raw, err := d.readWord(lc709203fRegTemperature)
	if err != nil {
		return 0, err
	}
	return float32(raw)/10.0 - 273.15, nil
}

// SetTemperature supplies the cell temperature (I²C temperature mode),
// clamped to −20…+60 °C.
func (d *LC709203FMinimal) SetTemperature(celsius float32) error {
	if celsius < -20 {
		celsius = -20
	}
	if celsius > 60 {
		celsius = 60
	}
	return d.writeWord(lc709203fRegTemperature, uint16(math.Round((float64(celsius)+273.15)*10)))
}

// LC709203FFull is the LC709203F full driver — extends LC709203FMinimal with
// RSOC control, thermistor mode, alarms, battery profiles and power mode.
type LC709203FFull struct {
	LC709203FMinimal

	mu           sync.Mutex
	alarmRSOC    uint16
	alarmVoltage uint16
	callback     func(status uint8)
	unsubscribe  func()
	pollPin      *connection.PollingInputPin
}

// NewLC709203FFull creates a new LC709203FFull and runs the starting flow.
// Arguments as for NewLC709203FMinimal.
func NewLC709203FFull(connection connection.RegisterConnection, capacityMah int, profile uint8) (*LC709203FFull, error) {
	m, err := NewLC709203FMinimal(connection, capacityMah, profile)
	if err != nil {
		return nil, err
	}
	d := &LC709203FFull{LC709203FMinimal: *m}
	if d.alarmRSOC, err = d.readWord(lc709203fRegAlarmRSOC); err != nil {
		return nil, err
	}
	if d.alarmVoltage, err = d.readWord(lc709203fRegAlarmVoltage); err != nil {
		return nil, err
	}
	return d, nil
}

// RSOC reads the RSOC register in % (0–100).
func (d *LC709203FFull) RSOC() (uint8, error) {
	v, err := d.readWord(lc709203fRegRSOC)
	return uint8(v), err
}

// SetRSOC forces the RSOC register (0–100 %); use sleep mode to keep the value.
func (d *LC709203FFull) SetRSOC(percent uint8) error {
	if percent > 100 {
		percent = 100
	}
	return d.writeWord(lc709203fRegRSOC, uint16(percent))
}

// InitRSOC initializes RSOC from the present voltage (0x07 ← 0xAA55) and waits 2 ms.
func (d *LC709203FFull) InitRSOC() error {
	err := d.writeWord(lc709203fRegInitialRSOC, 0xAA55)
	time.Sleep(2 * time.Millisecond)
	return err
}

// BeforeRSOC initializes RSOC from the maximum voltage since reset (0x04 ← 0xAA55) and waits 2 ms.
func (d *LC709203FFull) BeforeRSOC() error {
	err := d.writeWord(lc709203fRegBeforeRSOC, 0xAA55)
	time.Sleep(2 * time.Millisecond)
	return err
}

// UseThermistor switches to thermistor temperature mode with the given B constant (K).
func (d *LC709203FFull) UseThermistor(bConstant uint16) error {
	if err := d.writeWord(lc709203fRegThermistorB, bConstant); err != nil {
		return err
	}
	return d.writeWord(lc709203fRegStatus, 0x0001)
}

// UseI2CTemperature switches to I²C temperature mode and writes a temperature in °C.
func (d *LC709203FFull) UseI2CTemperature(celsius float32) error {
	if err := d.writeWord(lc709203fRegStatus, 0x0000); err != nil {
		return err
	}
	return d.SetTemperature(celsius)
}

// IsThermistorMode reports whether the temperature source is the thermistor.
func (d *LC709203FFull) IsThermistorMode() (bool, error) {
	v, err := d.readWord(lc709203fRegStatus)
	return v == 0x0001, err
}

// GetBConstant reads the thermistor B constant (K).
func (d *LC709203FFull) GetBConstant() (uint16, error) { return d.readWord(lc709203fRegThermistorB) }

// SetAPA writes the Adjustment Pack Application register (0–255).
func (d *LC709203FFull) SetAPA(value uint8) error { return d.writeWord(lc709203fRegAPA, uint16(value)) }

// GetAPA reads the Adjustment Pack Application register.
func (d *LC709203FFull) GetAPA() (uint8, error) {
	v, err := d.readWord(lc709203fRegAPA)
	return uint8(v), err
}

// SetAPT writes the Adjustment Pack Thermistor register.
func (d *LC709203FFull) SetAPT(value uint16) error { return d.writeWord(lc709203fRegAPT, value) }

// GetAPT reads the Adjustment Pack Thermistor register.
func (d *LC709203FFull) GetAPT() (uint16, error) { return d.readWord(lc709203fRegAPT) }

// SetCurrentDirection sets the current direction (LC709203FDirectionAuto,
// LC709203FDirectionCharge or LC709203FDirectionDischarge); any other value
// returns ErrLC709203FInvalidArgument.
func (d *LC709203FFull) SetCurrentDirection(mode uint16) error {
	if mode != LC709203FDirectionAuto && mode != LC709203FDirectionCharge && mode != LC709203FDirectionDischarge {
		return fmt.Errorf("%w: current direction 0x%04X", ErrLC709203FInvalidArgument, mode)
	}
	return d.writeWord(lc709203fRegDirection, mode)
}

// GetCurrentDirection reads the current direction register.
func (d *LC709203FFull) GetCurrentDirection() (uint16, error) {
	return d.readWord(lc709203fRegDirection)
}

// SetProfile selects battery profile 0 or 1 (re-initializes the chip: waits
// 90 ms, then re-enters operational mode).
func (d *LC709203FFull) SetProfile(profile uint8) error {
	if err := d.writeWord(lc709203fRegProfile, uint16(profile&1)); err != nil {
		return err
	}
	time.Sleep(100 * time.Millisecond)
	d.wakeAndOperational()
	return nil
}

// GetProfile reads the selected battery profile (0 or 1).
func (d *LC709203FFull) GetProfile() (uint8, error) {
	v, err := d.readWord(lc709203fRegProfile)
	return uint8(v), err
}

// ProfileID reads the Number of the Parameter register (e.g. 0x0301).
func (d *LC709203FFull) ProfileID() (uint16, error) { return d.readWord(lc709203fRegProfileID) }

// ICVersion reads the IC Version register.
func (d *LC709203FFull) ICVersion() (uint16, error) { return d.readWord(lc709203fRegICVersion) }

// SetAlarmRSOC sets the low-RSOC alarm threshold in % (0 disables, 1–100).
func (d *LC709203FFull) SetAlarmRSOC(percent uint8) error {
	if percent > 100 {
		percent = 100
	}
	if err := d.writeWord(lc709203fRegAlarmRSOC, uint16(percent)); err != nil {
		return err
	}
	d.alarmRSOC = uint16(percent)
	return nil
}

// GetAlarmRSOC reads the low-RSOC alarm threshold in %.
func (d *LC709203FFull) GetAlarmRSOC() (uint8, error) {
	v, err := d.readWord(lc709203fRegAlarmRSOC)
	return uint8(v), err
}

// SetAlarmVoltage sets the low-voltage alarm threshold in volts (0 disables; resolution 1 mV).
func (d *LC709203FFull) SetAlarmVoltage(volts float32) error {
	raw := math.Round(float64(volts) * 1000)
	if raw < 0 {
		raw = 0
	}
	if raw > 0xFFFF {
		raw = 0xFFFF
	}
	if err := d.writeWord(lc709203fRegAlarmVoltage, uint16(raw)); err != nil {
		return err
	}
	d.alarmVoltage = uint16(raw)
	return nil
}

// GetAlarmVoltage reads the low-voltage alarm threshold in volts.
func (d *LC709203FFull) GetAlarmVoltage() (float32, error) {
	v, err := d.readWord(lc709203fRegAlarmVoltage)
	return float32(v) / 1000.0, err
}

// Sleep enters sleep mode (only I²C stays alive; charge counting stops).
func (d *LC709203FFull) Sleep() error { return d.writeWord(lc709203fRegPowerMode, 0x0002) }

// Wake wakes the chip: a dummy transaction (SDA pulse), a 400 µs wait and operational mode.
func (d *LC709203FFull) Wake() error {
	d.wakeAndOperational()
	return nil
}

// IsSleeping reports whether the chip is in sleep mode (the bus is woken first).
func (d *LC709203FFull) IsSleeping() (bool, error) {
	_ = d.connection.WriteReg(uint32(lc709203fRegPowerMode), nil) // command code only: ignored by the chip
	time.Sleep(time.Millisecond)
	v, err := d.readWord(lc709203fRegPowerMode)
	return v == 0x0002, err
}

// OnInterrupt subscribes callback to ALARMB (falling edge): it receives the
// LC709203FAlarm* bitmask. Uses the connection's InputPin if wired, otherwise a
// polling goroutine (1 s tick, evaluating the cached thresholds).
func (d *LC709203FFull) OnInterrupt(callback func(status uint8)) error {
	d.mu.Lock()
	if d.unsubscribe != nil {
		d.unsubscribe()
		d.unsubscribe = nil
	}
	if d.pollPin != nil {
		_ = d.pollPin.Close()
		d.pollPin = nil
	}
	d.callback = callback
	pin := d.connection.IntPin()
	if pin == nil {
		poll := connection.NewPollingInputPin(time.Second)
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	unsub := pin.OnEdge(connection.Falling, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *LC709203FFull) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *LC709203FFull) handleEdge() {
	status, err := d.PollInterrupt()
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if err == nil && status != 0 && cb != nil {
		cb(status)
	}
}

// PollInterrupt evaluates RSOC and voltage against the cached alarm thresholds
// and returns a bitmask of LC709203FAlarmRSOC / LC709203FAlarmVoltage (0 = no alarm).
func (d *LC709203FFull) PollInterrupt() (uint8, error) {
	var status uint8
	if d.alarmRSOC != 0 {
		r, err := d.readWord(lc709203fRegRSOC)
		if err != nil {
			return 0, err
		}
		if r < d.alarmRSOC {
			status |= LC709203FAlarmRSOC
		}
	}
	if d.alarmVoltage != 0 {
		v, err := d.readWord(lc709203fRegVoltage)
		if err != nil {
			return 0, err
		}
		if v < d.alarmVoltage {
			status |= LC709203FAlarmVoltage
		}
	}
	return status, nil
}
