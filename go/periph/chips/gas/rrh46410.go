// RRH46410 digital gas sensor module for indoor air quality (Renesas).
//
// The host sends a request [command, params…, checksum] in one I²C write and reads the response
// [status, params…, checksum] in a separate read, where checksum = ^(sum of the other bytes).
// The module stretches the I²C clock; its address is fixed at 0x38.

package gas

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	rrh46410CmdGetProductID         byte = 0x00
	rrh46410CmdGetTrackingNumber    byte = 0x01
	rrh46410CmdGetOperationMode     byte = 0x10
	rrh46410CmdSetOperationMode     byte = 0x11
	rrh46410CmdSetHumidity          byte = 0x12
	rrh46410CmdGetMeasurementResult byte = 0x18
	rrh46410CmdConfigGPIO           byte = 0x20
	rrh46410CmdGetGPIO              byte = 0x21
	rrh46410CmdSetGPIO              byte = 0x22
	rrh46410CmdClearGPIO            byte = 0x23
	rrh46410CmdReset                byte = 0x8F

	rrh46410StatusNotComplete byte = 0x10
	rrh46410PollInterval           = 200 * time.Millisecond
	rrh46410InitTimeout            = time.Second
	rrh46410CleanTimeout           = 90 * time.Second
)

const (
	// RRH46410Address is the fixed 7-bit I²C address.
	RRH46410Address uint8 = 0x38
	// RRH46410ProductID is the expected Get Product ID value.
	RRH46410ProductID uint16 = 0x2310

	// RRH46410ModeSuspend stops measuring (default after power-up and reset).
	RRH46410ModeSuspend uint8 = 0x00
	// RRH46410ModeIAQ2ndGen samples every 3 s (100 warm-up samples).
	RRH46410ModeIAQ2ndGen uint8 = 0x01
	// RRH46410ModeULP samples every 90 s (10 warm-up samples).
	RRH46410ModeULP uint8 = 0x02
	// RRH46410ModePBAQ samples TVOC and ethanol every 5 s (60 warm-up samples).
	RRH46410ModePBAQ uint8 = 0x05
	// RRH46410ModeCleaning is the one-time 1-minute sensor cleaning.
	RRH46410ModeCleaning uint8 = 0x80
)

var (
	// ErrRRH46410Checksum is returned when a response checksum is wrong.
	ErrRRH46410Checksum = errors.New("rrh46410: response checksum error")
	// ErrRRH46410Timeout is returned when the module or a new sample does not arrive in time.
	ErrRRH46410Timeout = errors.New("rrh46410: timeout")
	// ErrRRH46410ProductID is returned by Init when the product ID is not 0x2310.
	ErrRRH46410ProductID = errors.New("rrh46410: unexpected product ID")
	// ErrRRH46410Argument is returned for an argument out of range.
	ErrRRH46410Argument = errors.New("rrh46410: invalid argument")
)

// RRH46410StatusError is returned when a response carries a non-zero status byte.
type RRH46410StatusError struct {
	Status  uint8
	Command byte
}

func (e *RRH46410StatusError) Error() string {
	return fmt.Sprintf("rrh46410: status 0x%02X (command 0x%02X)", e.Status, e.Command)
}

// RRH46410Result is a decoded Get Measurement Results response.
type RRH46410Result struct {
	Sample      uint8   // sample counter (0–255, wraps)
	IAQ         float64 // IAQ index, UBA level 1.0–5.0 (0 in PBAQ mode)
	TVOCMgM3    float64 // TVOC in mg/m³
	EtOHPPM     float64 // ethanol equivalent in ppm
	ECO2PPM     uint16  // estimated CO₂ in ppm (0 in PBAQ mode)
	RelativeIAQ uint16  // relative IAQ (0 in PBAQ mode)
	WarmedUp    bool    // true after the warm-up samples of the mode
	PBAQ        bool    // true when decoded in PBAQ mode (only TVOC and ethanol valid)
}

// RRH46410Status is the decoded status byte of the last response.
type RRH46410Status struct {
	Raw            uint8
	InvalidCommand bool  // bit 7: invalid command, read without request or bus error
	ChecksumError  bool  // bit 6: request checksum wrong
	SensorNACK     bool  // bit 5: internal gas-sensor I²C NACK
	NotComplete    bool  // bit 4: asynchronous command not complete
	ModeError      uint8 // bits 3:0: 0 OK, 0xA sensor damaged
}

func rrh46410Checksum(data []byte) byte {
	var s byte
	for _, b := range data {
		s += b
	}
	return ^s
}

func rrh46410Period(mode uint8) time.Duration {
	switch mode {
	case RRH46410ModeIAQ2ndGen:
		return 3 * time.Second
	case RRH46410ModeULP:
		return 90 * time.Second
	case RRH46410ModePBAQ:
		return 5 * time.Second
	}
	return 0
}

func rrh46410Warmup(mode uint8) int {
	switch mode {
	case RRH46410ModeIAQ2ndGen:
		return 100
	case RRH46410ModeULP:
		return 10
	case RRH46410ModePBAQ:
		return 60
	}
	return 0
}

// RRH46410AirQualityLabel returns the UBA rating text: "very good" (< 2.0), "good", "medium",
// "poor" or "bad" (≥ 5.0).
func RRH46410AirQualityLabel(iaq float64) string {
	switch {
	case iaq < 2.0:
		return "very good"
	case iaq < 3.0:
		return "good"
	case iaq < 4.0:
		return "medium"
	case iaq < 5.0:
		return "poor"
	}
	return "bad"
}

// RRH46410Minimal reads the air-quality values of the IAQ 2nd Gen mode.
//
// Init retries Get Product ID for up to 1 s, verifies 0x2310 and selects the IAQ 2nd Gen mode
// (one sample every 3 s; the first 100 samples, about 5 minutes, are the warm-up during which
// the values stay fixed). The module's default humidity (50 %RH) is kept; GPIOs are unused.
type RRH46410Minimal struct {
	conn        connection.Connection
	mu          sync.Mutex
	mode        uint8
	lastStatus  uint8
	haveCounter bool
	lastCounter uint8
	samples     int
	last        RRH46410Result
	pollEvery   time.Duration
}

// NewRRH46410Minimal creates the minimal driver for a plain I²C connection bound to 0x38; it
// does no bus access.
func NewRRH46410Minimal(conn connection.Connection) (*RRH46410Minimal, error) {
	return &RRH46410Minimal{conn: conn, pollEvery: rrh46410PollInterval}, nil
}

// Init waits for the module, verifies the product ID and starts IAQ 2nd Gen mode.
func (d *RRH46410Minimal) Init() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.waitProductID(); err != nil {
		return err
	}
	return d.setMode(RRH46410ModeIAQ2ndGen)
}

// Read waits for the next sample (sample period plus 2 s) and decodes it.
func (d *RRH46410Minimal) Read() (RRH46410Result, error) {
	ok, err := d.waitSample(rrh46410Period(d.currentMode()) + 2*time.Second)
	if err != nil {
		return RRH46410Result{}, err
	}
	if !ok {
		return RRH46410Result{}, ErrRRH46410Timeout
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.last, nil
}

func (d *RRH46410Minimal) currentMode() uint8 {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.mode
}

// transact sends a request and reads status + n bytes; with check a non-zero status fails.
func (d *RRH46410Minimal) transact(command byte, params []byte, n int, check bool) (byte, []byte, error) {
	req := append([]byte{command}, params...)
	req = append(req, rrh46410Checksum(req))
	if err := d.conn.Write(req); err != nil {
		return 0, nil, err
	}
	resp, err := d.conn.Read(n + 2)
	if err != nil {
		return 0, nil, err
	}
	if len(resp) < n+2 || rrh46410Checksum(resp[:n+1]) != resp[n+1] {
		return 0, nil, ErrRRH46410Checksum
	}
	d.lastStatus = resp[0]
	if check && resp[0] != 0 {
		return resp[0], nil, &RRH46410StatusError{Status: resp[0], Command: command}
	}
	return resp[0], resp[1 : n+1], nil
}

func (d *RRH46410Minimal) waitProductID() error {
	deadline := time.Now().Add(rrh46410InitTimeout)
	for {
		_, v, err := d.transact(rrh46410CmdGetProductID, nil, 5, true)
		if err == nil {
			if pid := uint16(v[0])<<8 | uint16(v[1]); pid != RRH46410ProductID {
				return fmt.Errorf("%w: 0x%04X", ErrRRH46410ProductID, pid)
			}
			return nil
		}
		if time.Now().After(deadline) {
			return ErrRRH46410Timeout
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func (d *RRH46410Minimal) setMode(mode uint8) error {
	if _, _, err := d.transact(rrh46410CmdSetOperationMode, []byte{mode}, 0, true); err != nil {
		return err
	}
	d.mode = mode
	d.haveCounter = false
	d.samples = 0
	return nil
}

// fetch reads the raw result; nil before the first sample of the mode.
func (d *RRH46410Minimal) fetch() ([]byte, error) {
	n := 9
	if d.mode == RRH46410ModePBAQ {
		n = 5
	}
	status, v, err := d.transact(rrh46410CmdGetMeasurementResult, nil, n, false)
	if err != nil {
		return nil, err
	}
	if status == rrh46410StatusNotComplete {
		return nil, nil
	}
	if status != 0 {
		return nil, &RRH46410StatusError{Status: status, Command: rrh46410CmdGetMeasurementResult}
	}
	return v, nil
}

func (d *RRH46410Minimal) decode(v []byte) RRH46410Result {
	counter := v[0]
	if d.haveCounter {
		d.samples += int(counter - d.lastCounter)
	} else {
		d.samples = int(counter)
	}
	d.haveCounter = true
	d.lastCounter = counter
	r := RRH46410Result{Sample: counter, WarmedUp: d.samples >= rrh46410Warmup(d.mode)}
	if d.mode == RRH46410ModePBAQ {
		r.PBAQ = true
		r.TVOCMgM3 = float64(uint16(v[1])<<8|uint16(v[2])) / 1000
		r.EtOHPPM = float64(uint16(v[3])<<8|uint16(v[4])) / 1000
	} else {
		r.IAQ = float64(v[1]) / 10
		r.TVOCMgM3 = float64(uint16(v[2])<<8|uint16(v[3])) / 100
		r.EtOHPPM = float64(uint16(v[4])<<8|uint16(v[5])) / 100
		r.ECO2PPM = uint16(v[6])<<8 | uint16(v[7])
		r.RelativeIAQ = uint16(v[8]) * 10
	}
	d.last = r
	return r
}

// waitSample polls until the sample counter changes (or INT is high and a new result exists).
func (d *RRH46410Minimal) waitSample(timeout time.Duration) (bool, error) {
	deadline := time.Now().Add(timeout)
	pin := d.conn.IntPin()
	d.mu.Lock()
	had, last := d.haveCounter, d.lastCounter
	d.mu.Unlock()
	for {
		high := true
		if pin != nil {
			if h, err := pin.IsHigh(); err == nil {
				high = h
			}
		}
		if high {
			d.mu.Lock()
			v, err := d.fetch()
			if err != nil {
				d.mu.Unlock()
				return false, err
			}
			if v != nil && (!had || v[0] != last) {
				d.decode(v)
				d.mu.Unlock()
				return true, nil
			}
			d.mu.Unlock()
		}
		if time.Now().After(deadline) {
			return false, nil
		}
		time.Sleep(d.pollEvery)
	}
}

// RRH46410Full adds identity, operation modes (IAQ 2nd Gen, ULP, PBAQ, cleaning), humidity
// compensation, GPIOs, status, reset, raw commands and the INT interrupt API. INT is active
// high, push-pull: high when a new result is available, low after Get Measurement Results.
type RRH46410Full struct {
	RRH46410Minimal

	imu         sync.Mutex
	callback    func(RRH46410Result)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewRRH46410Full creates the full driver for a plain I²C connection bound to 0x38; it does no
// bus access.
func NewRRH46410Full(conn connection.Connection) (*RRH46410Full, error) {
	return &RRH46410Full{RRH46410Minimal: RRH46410Minimal{conn: conn, pollEvery: rrh46410PollInterval}}, nil
}

// ProductID runs Get Product ID: the product ID (0x2310) and the firmware [major, minor, patch].
func (d *RRH46410Full) ProductID() (uint16, [3]uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, v, err := d.transact(rrh46410CmdGetProductID, nil, 5, true)
	if err != nil {
		return 0, [3]uint8{}, err
	}
	return uint16(v[0])<<8 | uint16(v[1]), [3]uint8{v[2], v[3], v[4]}, nil
}

// TrackingNumber runs Get Tracking Number (6 bytes).
func (d *RRH46410Full) TrackingNumber() ([]byte, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, v, err := d.transact(rrh46410CmdGetTrackingNumber, nil, 6, true)
	if err != nil {
		return nil, err
	}
	return append([]byte(nil), v...), nil
}

// GetMode runs Get Operation Mode (one of the RRH46410Mode* constants; other values behave as
// SUSPEND).
func (d *RRH46410Full) GetMode() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.getMode()
}

func (d *RRH46410Full) getMode() (uint8, error) {
	_, v, err := d.transact(rrh46410CmdGetOperationMode, nil, 1, true)
	if err != nil {
		return 0, err
	}
	d.mode = v[0]
	return v[0], nil
}

// SetMode runs Set Operation Mode with RRH46410ModeSuspend, ModeIAQ2ndGen, ModeULP or ModePBAQ.
// It stops a running measurement, restarts the warm-up and sets INT low. Use CleanSensor for
// the cleaning mode.
func (d *RRH46410Full) SetMode(mode uint8) error {
	switch mode {
	case RRH46410ModeSuspend, RRH46410ModeIAQ2ndGen, RRH46410ModeULP, RRH46410ModePBAQ:
	default:
		return ErrRRH46410Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.setMode(mode)
}

// Suspend stops measuring (SUSPEND, about 0.7 µA).
func (d *RRH46410Full) Suspend() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.setMode(RRH46410ModeSuspend)
}

// ReadResults reads and decodes the latest result without waiting; ok is false before the first
// sample of the mode.
func (d *RRH46410Full) ReadResults() (r RRH46410Result, ok bool, err error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.fetch()
	if err != nil || v == nil {
		return RRH46410Result{}, false, err
	}
	return d.decode(v), true, nil
}

// WaitForSample waits until the sample counter changes (or INT goes high); the new sample is
// returned by LastResult. It reports whether a new sample arrived within timeout.
func (d *RRH46410Full) WaitForSample(timeout time.Duration) (bool, error) {
	return d.waitSample(timeout)
}

// LastResult returns the result decoded by the last Read, ReadResults or WaitForSample.
func (d *RRH46410Full) LastResult() RRH46410Result {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.last
}

// SetHumidity runs Set Humidity with the ambient relative humidity 0–100 %, sent as
// round(percent / 100 × 255).
func (d *RRH46410Full) SetHumidity(percent float64) error {
	if !(percent >= 0 && percent <= 100) {
		return ErrRRH46410Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	_, _, err := d.transact(rrh46410CmdSetHumidity, []byte{byte(percent/100*255 + 0.5)}, 0, true)
	return err
}

// SamplePeriod returns the sample period of mode (3 s, 90 s, 5 s, 0 otherwise); a negative mode
// means the current one.
func (d *RRH46410Full) SamplePeriod(mode int) time.Duration {
	if mode < 0 {
		return rrh46410Period(d.currentMode())
	}
	return rrh46410Period(uint8(mode))
}

// WarmupSamples returns the warm-up samples of mode (100, 10, 60, 0 otherwise); a negative mode
// means the current one.
func (d *RRH46410Full) WarmupSamples(mode int) int {
	if mode < 0 {
		return rrh46410Warmup(d.currentMode())
	}
	return rrh46410Warmup(uint8(mode))
}

// AirQualityLabel returns the UBA rating text for iaq (see RRH46410AirQualityLabel).
func (d *RRH46410Full) AirQualityLabel(iaq float64) string {
	return RRH46410AirQualityLabel(iaq)
}

// CleanSensor runs the one-time 1-minute sensor cleaning and waits (up to 90 s) for SUSPEND.
// Execute it at most once in the sensor's lifetime and do not interrupt it; confirm must be
// true.
func (d *RRH46410Full) CleanSensor(confirm bool) error {
	if !confirm {
		return ErrRRH46410Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.setMode(RRH46410ModeCleaning); err != nil {
		return err
	}
	deadline := time.Now().Add(rrh46410CleanTimeout)
	for {
		m, err := d.getMode()
		if err != nil {
			return err
		}
		if m != RRH46410ModeCleaning {
			d.mode = RRH46410ModeSuspend
			return nil
		}
		if time.Now().After(deadline) {
			return ErrRRH46410Timeout
		}
		time.Sleep(time.Second)
	}
}

func (d *RRH46410Full) gpio(command byte, mask uint8) error {
	if mask > 3 {
		return ErrRRH46410Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	_, _, err := d.transact(command, []byte{mask}, 0, true)
	return err
}

// ConfigGPIO runs Config GPIO: bit 0 GPIO1 output, bit 1 GPIO2 output (0–3).
func (d *RRH46410Full) ConfigGPIO(directions uint8) error {
	return d.gpio(rrh46410CmdConfigGPIO, directions)
}

// GetGPIO runs Get GPIO: bits 0–3 = GPIO1–GPIO4 levels.
func (d *RRH46410Full) GetGPIO() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, v, err := d.transact(rrh46410CmdGetGPIO, nil, 1, true)
	if err != nil {
		return 0, err
	}
	return v[0], nil
}

// SetGPIO runs Set GPIO: drive the outputs in mask (0–3) high.
func (d *RRH46410Full) SetGPIO(mask uint8) error {
	return d.gpio(rrh46410CmdSetGPIO, mask)
}

// ClearGPIO runs Clear GPIO: drive the outputs in mask (0–3) low.
func (d *RRH46410Full) ClearGPIO(mask uint8) error {
	return d.gpio(rrh46410CmdClearGPIO, mask)
}

// Status decodes the status byte of the last response.
func (d *RRH46410Full) Status() RRH46410Status {
	d.mu.Lock()
	s := d.lastStatus
	d.mu.Unlock()
	return RRH46410Status{
		Raw:            s,
		InvalidCommand: s&0x80 != 0,
		ChecksumError:  s&0x40 != 0,
		SensorNACK:     s&0x20 != 0,
		NotComplete:    s&0x10 != 0,
		ModeError:      s & 0x0F,
	}
}

// Reset sends Reset (0x8F, no response), waits 500 ms and verifies the product ID again; the
// module restarts in SUSPEND.
func (d *RRH46410Full) Reset() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	_ = d.conn.Write([]byte{rrh46410CmdReset, rrh46410Checksum([]byte{rrh46410CmdReset})}) // may not be acknowledged
	time.Sleep(500 * time.Millisecond)
	if err := d.waitProductID(); err != nil {
		return err
	}
	d.mode = RRH46410ModeSuspend
	d.haveCounter = false
	d.samples = 0
	return nil
}

// SendCommand sends a raw request with checksums and returns responseLen parameter bytes; the
// status byte is available from Status. Reset (0x8F) sends the request only.
func (d *RRH46410Full) SendCommand(command uint8, params []byte, responseLen int) ([]byte, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if command == rrh46410CmdReset {
		req := append([]byte{command}, params...)
		return []byte{}, d.conn.Write(append(req, rrh46410Checksum(req)))
	}
	_, v, err := d.transact(command, params, responseLen, false)
	if err != nil {
		return nil, err
	}
	return append([]byte(nil), v...), nil
}

// PollInterrupt reports whether the INT pin is high (pin wired) or the sample counter changed
// since the last decoded result (the result is not consumed).
func (d *RRH46410Full) PollInterrupt() (bool, error) {
	if pin := d.conn.IntPin(); pin != nil {
		return pin.IsHigh()
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.fetch()
	if err != nil || v == nil {
		return false, err
	}
	return !d.haveCounter || v[0] != d.lastCounter, nil
}

// OnInterrupt calls callback with every new decoded sample. It uses the connection's INT pin
// (rising edge) when present, otherwise a 200 ms poll comparing the sample counter; the driver
// reads the result, which sets INT low.
func (d *RRH46410Full) OnInterrupt(callback func(RRH46410Result)) error {
	_ = d.OffInterrupt()
	d.imu.Lock()
	d.callback = callback
	d.imu.Unlock()
	ip := d.conn.IntPin()
	if ip == nil {
		poll := connection.NewPollingInputPin(d.pollEvery)
		d.imu.Lock()
		d.pollPin = poll
		d.imu.Unlock()
		ip = poll
	}
	unsub := ip.OnEdge(connection.Rising, d.handleEdge)
	d.imu.Lock()
	d.unsubscribe = unsub
	d.imu.Unlock()
	return nil
}

// OffInterrupt unsubscribes from the interrupt.
func (d *RRH46410Full) OffInterrupt() error {
	d.imu.Lock()
	unsub, poll := d.unsubscribe, d.pollPin
	d.unsubscribe, d.pollPin, d.callback = nil, nil, nil
	d.imu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *RRH46410Full) handleEdge() {
	d.imu.Lock()
	cb := d.callback
	d.imu.Unlock()
	if cb == nil {
		return
	}
	d.mu.Lock()
	v, err := d.fetch()
	if err != nil || v == nil || (d.haveCounter && v[0] == d.lastCounter) {
		d.mu.Unlock()
		return
	}
	r := d.decode(v)
	d.mu.Unlock()
	cb(r)
}
