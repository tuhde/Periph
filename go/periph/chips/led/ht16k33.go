package led

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HT16K33 command bytes.
const (
	ht16k33CmdSystem  uint8 = 0x20
	ht16k33CmdDisplay uint8 = 0x80
	ht16k33CmdDimming uint8 = 0xE0
	ht16k33CmdKeyPtr  uint8 = 0x40
	ht16k33CmdIntPtr  uint8 = 0x60
)

// Geometry of the 28-pin package.
const (
	// HT16K33Commons is the number of COM lines.
	HT16K33Commons = 8
	// HT16K33Rows is the number of ROW lines.
	HT16K33Rows = 16
)

// ErrHT16K33Range is returned when an argument is outside its documented range.
var ErrHT16K33Range = errors.New("ht16k33: argument out of range")

// HT16K33Minimal is the 16x8 LED matrix driver — minimal interface.
//
// Drives up to 16 ROW x 8 COM = 128 LEDs through a 16-byte display RAM that is
// shadowed in the driver. The I²C address is 0x70 (A0–A2 select 0x70–0x77).
//
// Defaults baked in at construction: oscillator on, ROW15 pin as a ROW output,
// brightness 16/16, blink off, RAM cleared, display on.
type HT16K33Minimal struct {
	connection connection.Connection
	ram        [16]byte
	brightness uint8
	blink      uint8
	display    bool
	rowInt     uint8
}

// NewHT16K33Minimal creates the driver and runs the initialization sequence.
//
// t must be a configured I²C connection bound to the chip's 7-bit address.
func NewHT16K33Minimal(t connection.Connection) (*HT16K33Minimal, error) {
	d := &HT16K33Minimal{connection: t, brightness: 15, display: true, rowInt: 0xA0}
	if err := d.command(ht16k33CmdSystem | 0x01); err != nil {
		return nil, err
	}
	if err := d.command(d.rowInt); err != nil {
		return nil, err
	}
	if err := d.command(ht16k33CmdDimming | d.brightness); err != nil {
		return nil, err
	}
	if err := d.Show(); err != nil {
		return nil, err
	}
	if err := d.applyDisplay(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *HT16K33Minimal) command(b uint8) error {
	return d.connection.Write([]byte{b})
}

func (d *HT16K33Minimal) applyDisplay() error {
	b := ht16k33CmdDisplay | (d.blink << 1)
	if d.display {
		b |= 1
	}
	return d.command(b)
}

// Clear zeroes the shadow RAM and writes it to the chip.
func (d *HT16K33Minimal) Clear() error {
	d.ram = [16]byte{}
	return d.Show()
}

// SetPixel sets or clears one LED in the shadow RAM (it does not write to the
// chip). com is 0–7 and row is 0–15; otherwise ErrHT16K33Range is returned.
func (d *HT16K33Minimal) SetPixel(com, row uint8, on bool) error {
	if com > 7 || row > 15 {
		return ErrHT16K33Range
	}
	idx := 2*com + (row >> 3)
	mask := uint8(1) << (row & 7)
	if on {
		d.ram[idx] |= mask
	} else {
		d.ram[idx] &^= mask
	}
	return nil
}

// WriteRaw writes 1–16 raw RAM bytes at address (0–15) and updates the shadow
// RAM. ErrHT16K33Range is returned if data is empty or address+len(data) > 16.
func (d *HT16K33Minimal) WriteRaw(data []byte, address uint8) error {
	if len(data) == 0 || int(address)+len(data) > 16 {
		return ErrHT16K33Range
	}
	copy(d.ram[address:], data)
	buf := make([]byte, 1+len(data))
	buf[0] = address
	copy(buf[1:], data)
	return d.connection.Write(buf)
}

// Show writes the whole 16-byte shadow RAM to the chip.
func (d *HT16K33Minimal) Show() error {
	buf := make([]byte, 17)
	copy(buf[1:], d.ram[:])
	return d.connection.Write(buf)
}

// SetBrightness sets the dimming level, 0–15, giving (level+1)/16 duty.
func (d *HT16K33Minimal) SetBrightness(level uint8) error {
	if level > 15 {
		return ErrHT16K33Range
	}
	d.brightness = level
	return d.command(ht16k33CmdDimming | level)
}

// HT16K33Full extends HT16K33Minimal with display control, standby, key scan
// and the INT output.
//
// Key-press delivery (OnInterrupt) uses the connection's InputPin if wired,
// otherwise a 20 ms polling goroutine.
type HT16K33Full struct {
	*HT16K33Minimal

	mu          sync.Mutex
	callback    func(keys uint64)
	unsubscribe func()
	stopPoll    chan struct{}
}

// NewHT16K33Full creates the Full driver; the initialization sequence is the
// same as for NewHT16K33Minimal.
func NewHT16K33Full(t connection.Connection) (*HT16K33Full, error) {
	m, err := NewHT16K33Minimal(t)
	if err != nil {
		return nil, err
	}
	return &HT16K33Full{HT16K33Minimal: m}, nil
}

// DisplayOn turns the display output on (keeps the blink setting).
func (d *HT16K33Full) DisplayOn() error {
	d.display = true
	return d.applyDisplay()
}

// DisplayOff turns the display output off (keeps the blink setting).
func (d *HT16K33Full) DisplayOff() error {
	d.display = false
	return d.applyDisplay()
}

// SetBlink sets the blink frequency: 0 (off), 2, 1 or 0.5 Hz. Any other value
// returns ErrHT16K33Range.
func (d *HT16K33Full) SetBlink(frequency float32) error {
	var code uint8
	switch frequency {
	case 0:
		code = 0
	case 2:
		code = 1
	case 1:
		code = 2
	case 0.5:
		code = 3
	default:
		return ErrHT16K33Range
	}
	d.blink = code
	return d.applyDisplay()
}

// Standby stops the oscillator (display off, key scan stopped).
func (d *HT16K33Full) Standby() error {
	return d.command(ht16k33CmdSystem)
}

// Wake starts the oscillator and restores display, dimming, ROW/INT and RAM.
func (d *HT16K33Full) Wake() error {
	if err := d.command(ht16k33CmdSystem | 0x01); err != nil {
		return err
	}
	if err := d.command(d.rowInt); err != nil {
		return err
	}
	if err := d.command(ht16k33CmdDimming | d.brightness); err != nil {
		return err
	}
	if err := d.Show(); err != nil {
		return err
	}
	return d.applyDisplay()
}

// SetIntOutput selects the function of the ROW15/INT pin. enable makes it an
// interrupt output (ROW15 and K13 become unusable); activeHigh selects the
// INT polarity (default active low).
func (d *HT16K33Full) SetIntOutput(enable, activeHigh bool) error {
	switch {
	case !enable:
		d.rowInt = 0xA0
	case activeHigh:
		d.rowInt = 0xA3
	default:
		d.rowInt = 0xA1
	}
	return d.command(d.rowInt)
}

// ReadKeys reads and clears the key data RAM. The result is a bitmask: bit
// k*13 + (K-1) for key input K (1–13) on key source KSk (0–2).
func (d *HT16K33Full) ReadKeys() (uint64, error) {
	if err := d.connection.Write([]byte{ht16k33CmdKeyPtr}); err != nil {
		return 0, err
	}
	b, err := d.connection.Read(6)
	if err != nil {
		return 0, err
	}
	var mask uint64
	for k := 0; k < 3; k++ {
		word := (uint64(b[2*k]) | uint64(b[2*k+1])<<8) & 0x1FFF
		mask |= word << (13 * uint(k))
	}
	return mask, nil
}

// KeyPressed reads the INT flag; true if a key press is pending.
func (d *HT16K33Full) KeyPressed() (bool, error) {
	if err := d.connection.Write([]byte{ht16k33CmdIntPtr}); err != nil {
		return false, err
	}
	b, err := d.connection.Read(1)
	if err != nil {
		return false, err
	}
	return b[0]&0x01 != 0, nil
}

// GetPixel reads one LED state from the shadow RAM.
func (d *HT16K33Full) GetPixel(com, row uint8) (bool, error) {
	if com > 7 || row > 15 {
		return false, ErrHT16K33Range
	}
	return (d.ram[2*com+(row>>3)]>>(row&7))&1 == 1, nil
}

// PollInterrupt checks the INT flag and, if set, reads (and clears) the key
// data; it returns 0 when no key is pending.
func (d *HT16K33Full) PollInterrupt() (uint64, error) {
	pressed, err := d.KeyPressed()
	if err != nil || !pressed {
		return 0, err
	}
	return d.ReadKeys()
}

// OnInterrupt subscribes callback to key-press events. The connection's
// InputPin is used if wired (edge per the configured INT polarity), otherwise
// a 20 ms polling goroutine.
func (d *HT16K33Full) OnInterrupt(callback func(keys uint64)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	if pin := d.connection.IntPin(); pin != nil {
		trigger := connection.Falling
		if d.rowInt == 0xA3 {
			trigger = connection.Rising
		}
		unsub := pin.OnEdge(trigger, d.service)
		d.mu.Lock()
		d.unsubscribe = unsub
		d.mu.Unlock()
		return nil
	}
	stop := make(chan struct{})
	d.mu.Lock()
	d.stopPoll = stop
	d.mu.Unlock()
	go func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			d.service()
			time.Sleep(20 * time.Millisecond)
		}
	}()
	return nil
}

// OffInterrupt unsubscribes key-press delivery.
func (d *HT16K33Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	stop := d.stopPoll
	d.unsubscribe = nil
	d.stopPoll = nil
	d.callback = nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if stop != nil {
		close(stop)
	}
	return nil
}

func (d *HT16K33Full) service() {
	keys, err := d.PollInterrupt()
	if err != nil || keys == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(keys)
	}
}
