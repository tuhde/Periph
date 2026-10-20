package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// UC1611s geometry and transfer limits.
const (
	// UC1611sWidth and UC1611sHeight are the dimensions of the EA DOGXL240-7.
	UC1611sWidth  = 240
	UC1611sHeight = 128
	// UC1611sI2CChunk is the largest data write in bytes (the 32-byte limit of some I2C layers).
	UC1611sI2CChunk = 32
	// UC1611sMaxAttempts is the number of attempts per transfer before it counts as failed.
	UC1611sMaxAttempts = 8
	// UC1611sRetryDelay is the wait between two attempts.
	UC1611sRetryDelay = 2 * time.Millisecond
)

// ErrUC1611sArgument is returned for a size, position or buffer argument that is out of range.
var ErrUC1611sArgument = errors.New("uc1611s: argument out of range")

const (
	uc1611sPagesRAM         = 20 // RAM pages cleared at init (160 rows / 8)
	uc1611sCmdTemp          = 0x24
	uc1611sCmdPanel         = 0x28
	uc1611sCmdPump          = 0x2C
	uc1611sCmdScrollLo      = 0x40
	uc1611sCmdScrollHi      = 0x50
	uc1611sCmdPageLo        = 0x60
	uc1611sCmdPageHi        = 0x70
	uc1611sCmdPotentiometer = 0x81
	uc1611sCmdIsolation     = 0x82
	uc1611sCmdPartial       = 0x84
	uc1611sCmdRAMAddr       = 0x88
	uc1611sCmdFixed         = 0x90
	uc1611sCmdLineRate      = 0xA0
	uc1611sCmdAllOn         = 0xA4
	uc1611sCmdInverse       = 0xA6
	uc1611sCmdDisplay       = 0xA8
	uc1611sCmdMapping       = 0xC0
	uc1611sCmdNLine         = 0xC8
	uc1611sCmdPattern       = 0xD0
	uc1611sCmdReset         = 0xE2
	uc1611sCmdNop           = 0xE3
	uc1611sCmdBias          = 0xE8
	uc1611sCmdComEnd        = 0xF1
	uc1611sCmdPartStart     = 0xF2
	uc1611sCmdPartEnd       = 0xF3
)

// UC1611sMinimal is the UC1611s graphic LCD controller-driver — minimal interface.
//
// Drives an EA DOGXL240-7 (240 x 128 dots, 3.3 V) in 1 bit per pixel black/white mode over I2C. The
// chip carries the command/data bit in its I2C address, so it answers on two consecutive 7-bit
// addresses: 0x38 + 2n for commands and 0x39 + 2n for display RAM data (n = A3 << 1 | A2, the two
// address pins). The driver therefore takes two connections on the same bus.
//
// All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel of
// the page, 3840 bytes at 240 x 128); Show sends it to the display RAM. The initialisation follows
// the DOG module's bottom-view example: System Reset, temperature compensation -0.10 %/degC, COM end
// 127, potentiometer (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.
//
// Error handling: the chip occasionally NACKs a transfer (seen on long cables and for about 6 ms
// while the charge pump restarts). Every transfer is retried up to UC1611sMaxAttempts times,
// UC1611sRetryDelay apart: a command stream is repeated and a RAM page is rewritten from its address
// command (a partial write cannot be continued). Repeated attempts are counted (RetryCount); a
// transfer that still fails returns its error and is counted in ErrorCount.
type UC1611sMinimal struct {
	cmd, data connection.Connection
	width     int
	height    int
	pages     int
	mx, my    uint8
	pattern   uint8
	contrast  uint8
	retries   uint32
	errors    uint32
	fb        []byte
}

// NewUC1611sMinimal creates the driver and runs the initialisation sequence (about 400 ms).
//
// command is the connection at the command address (0x38, 0x3A, 0x3C or 0x3E), data the connection at
// the data address (command address + 1). width is 1..240 and height a multiple of 8, 8..128 (pass 0
// for the defaults 240 and 128). It returns ErrUC1611sArgument for a bad size and the bus error when
// the chip does not acknowledge the System Reset.
func NewUC1611sMinimal(command, data connection.Connection, width, height int) (*UC1611sMinimal, error) {
	return newUC1611s(command, data, width, height, false, 0x8F)
}

func newUC1611s(command, data connection.Connection, width, height int, topView bool, contrast uint8) (*UC1611sMinimal, error) {
	if width == 0 {
		width = UC1611sWidth
	}
	if height == 0 {
		height = UC1611sHeight
	}
	if width < 1 || width > UC1611sWidth || height < 8 || height > UC1611sHeight || height%8 != 0 {
		return nil, ErrUC1611sArgument
	}
	d := &UC1611sMinimal{
		cmd: command, data: data, width: width, height: height, pages: height / 8,
		contrast: contrast, fb: make([]byte, width*(height/8)),
	}
	if topView {
		d.my = 1
	} else {
		d.mx = 1
	}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the columns of the framebuffer.
func (d *UC1611sMinimal) Width() int { return d.width }

// Height returns the rows of the framebuffer.
func (d *UC1611sMinimal) Height() int { return d.height }

// OK reports whether no transfer has failed after all retries since construction or ClearErrors.
func (d *UC1611sMinimal) OK() bool { return d.errors == 0 }

// RetryCount returns the number of transfer attempts that had to be repeated.
func (d *UC1611sMinimal) RetryCount() uint32 { return d.retries }

// ErrorCount returns the number of transfers that still failed after UC1611sMaxAttempts attempts.
func (d *UC1611sMinimal) ErrorCount() uint32 { return d.errors }

// ClearErrors resets the retry and error counters.
func (d *UC1611sMinimal) ClearErrors() {
	d.retries = 0
	d.errors = 0
}

// Clear zeroes the framebuffer. Call Show to update the panel.
func (d *UC1611sMinimal) Clear() {
	for i := range d.fb {
		d.fb[i] = 0
	}
}

// SetPixel sets or clears one pixel in the framebuffer. Out-of-range coordinates are ignored.
func (d *UC1611sMinimal) SetPixel(x, y int, on bool) {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return
	}
	i := (y>>3)*d.width + x
	if on {
		d.fb[i] |= 1 << uint(y&7)
	} else {
		d.fb[i] &^= 1 << uint(y&7)
	}
}

// Show writes the whole framebuffer to the display RAM (page + column, then width data bytes per page).
func (d *UC1611sMinimal) Show() error {
	for page := 0; page < d.pages; page++ {
		if err := d.showPage(page); err != nil {
			return err
		}
	}
	return nil
}

// ---- internals ------------------------------------------------------------------------------

// retry runs fn up to UC1611sMaxAttempts times, UC1611sRetryDelay apart, counting retries and the
// final failure.
func (d *UC1611sMinimal) retry(fn func() error) error {
	var err error
	for attempt := 0; attempt < UC1611sMaxAttempts; attempt++ {
		if err = fn(); err == nil {
			return nil
		}
		d.retries++
		time.Sleep(UC1611sRetryDelay)
	}
	d.errors++
	return err
}

// command sends bytes as one command transaction on the command address, retried on failure.
func (d *UC1611sMinimal) command(b ...byte) error {
	return d.retry(func() error { return d.cmd.Write(b) })
}

// writeChunks is one pass over the data in chunks of at most UC1611sI2CChunk bytes.
func (d *UC1611sMinimal) writeChunks(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > UC1611sI2CChunk {
			n = UC1611sI2CChunk
		}
		if err := d.data.Write(b[:n]); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (d *UC1611sMinimal) addressBytes(page, column int) []byte {
	pa := int(d.pattern)<<5 | page&0x1F
	return []byte{byte(uc1611sCmdPageLo | pa&0x0F), byte(uc1611sCmdPageHi | (pa>>4)&0x07), byte(column & 0x0F), byte(0x10 | column>>4)}
}

// writePage writes one page: address command, then the data; a failure repeats both.
func (d *UC1611sMinimal) writePage(page int, b []byte) error {
	return d.retry(func() error {
		if err := d.cmd.Write(d.addressBytes(page, 0)); err != nil {
			return err
		}
		return d.writeChunks(b)
	})
}

func (d *UC1611sMinimal) showPage(page int) error {
	start := page * d.width
	return d.writePage(page, d.fb[start:start+d.width])
}

// readStatus reads Status1..3; a NOP write first restarts the chip's status cycle (it advances by
// n + 1 per read).
func (d *UC1611sMinimal) readStatus() ([]byte, error) {
	var out []byte
	err := d.retry(func() error {
		if err := d.cmd.Write([]byte{uc1611sCmdNop}); err != nil {
			return err
		}
		b, err := d.cmd.Read(3)
		if err != nil {
			return err
		}
		out = b
		return nil
	})
	return out, err
}

// waitDisplayEnabled polls Status1.DE until the pump is up; NACKs while it starts are expected and not
// counted.
func (d *UC1611sMinimal) waitDisplayEnabled(timeout time.Duration) {
	for waited := time.Duration(0); waited <= timeout; waited += UC1611sRetryDelay {
		if err := d.cmd.Write([]byte{uc1611sCmdNop}); err == nil {
			if b, err := d.cmd.Read(3); err == nil && len(b) == 3 && b[0]&0x08 != 0 {
				return
			}
		}
		time.Sleep(UC1611sRetryDelay)
	}
}

func (d *UC1611sMinimal) mapping() byte { return d.my<<2 | d.mx<<1 }

// resetChip sends System Reset in its own transaction, then the conservative 150 ms wait.
func (d *UC1611sMinimal) resetChip() error {
	if err := d.command(uc1611sCmdReset); err != nil {
		return err
	}
	time.Sleep(150 * time.Millisecond)
	return nil
}

// configure sends the configuration stream of the DOG bottom-view example (the display stays off).
func (d *UC1611sMinimal) configure() error {
	last := byte(d.height - 1)
	return d.command(
		uc1611sCmdTemp|1,
		uc1611sCmdMapping, d.mapping(),
		uc1611sCmdLineRate|3,
		uc1611sCmdComEnd, last,
		uc1611sCmdPartStart, 0,
		uc1611sCmdPartEnd, last,
		uc1611sCmdPotentiometer, d.contrast,
		uc1611sCmdRAMAddr|1,
		uc1611sCmdPattern|1,
	)
}

func (d *UC1611sMinimal) enable() error { return d.command(uc1611sCmdDisplay | 1) }

func (d *UC1611sMinimal) initSequence() error {
	time.Sleep(150 * time.Millisecond)
	if err := d.resetChip(); err != nil {
		return err
	}
	if err := d.configure(); err != nil {
		return err
	}
	zeros := make([]byte, d.width)
	for page := 0; page < uc1611sPagesRAM; page++ {
		if err := d.writePage(page, zeros); err != nil {
			return err
		}
	}
	if err := d.enable(); err != nil {
		return err
	}
	d.waitDisplayEnabled(200 * time.Millisecond)
	return nil
}

// UC1611sFull is the UC1611s graphic LCD controller-driver — full interface.
//
// It extends UC1611sMinimal with drawing primitives, contrast, sleep, invert, flip / top view,
// hardware scroll with fixed lines, bias ratio, temperature compensation, line rate, charge pump /
// external VLCD, panel loading, partial display, N-line inversion, isolation clock, the four RAM
// patterns, status and RAM reads, and raw commands.
type UC1611sFull struct {
	*UC1611sMinimal
}

// NewUC1611sFull creates the driver and runs the initialisation sequence.
//
// command and data are the connections at the command address (0x38, 0x3A, 0x3C, 0x3E) and the data
// address (command address + 1); width is 1..240 and height a multiple of 8, 8..128 (0 selects the
// defaults); topView selects the 12:00 viewing direction (MY = 1, MX = 0); contrast is the
// potentiometer PM 0..255 (the DOG example value is 0x8F).
func NewUC1611sFull(command, data connection.Connection, width, height int, topView bool, contrast uint8) (*UC1611sFull, error) {
	m, err := newUC1611s(command, data, width, height, topView, contrast)
	if err != nil {
		return nil, err
	}
	return &UC1611sFull{m}, nil
}

// GetPixel returns a framebuffer pixel; out-of-range coordinates read as false.
func (d *UC1611sFull) GetPixel(x, y int) bool {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return false
	}
	return d.fb[(y>>3)*d.width+x]&(1<<uint(y&7)) != 0
}

// Fill sets (or clears) the whole framebuffer.
func (d *UC1611sFull) Fill(on bool) {
	v := byte(0)
	if on {
		v = 0xFF
	}
	for i := range d.fb {
		d.fb[i] = v
	}
}

func uc1611sAbs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *UC1611sFull) DrawLine(x0, y0, x1, y1 int, on bool) {
	dx, dy := uc1611sAbs(x1-x0), -uc1611sAbs(y1-y0)
	sx, sy := 1, 1
	if x0 >= x1 {
		sx = -1
	}
	if y0 >= y1 {
		sy = -1
	}
	err := dx + dy
	for {
		d.SetPixel(x0, y0, on)
		if x0 == x1 && y0 == y1 {
			return
		}
		e2 := 2 * err
		if e2 >= dy {
			err += dy
			x0 += sx
		}
		if e2 <= dx {
			err += dx
			y0 += sy
		}
	}
}

// DrawRect draws a rectangle outline or a filled rectangle into the framebuffer.
func (d *UC1611sFull) DrawRect(x, y, w, h int, on, fill bool) {
	if w <= 0 || h <= 0 {
		return
	}
	if fill {
		for yy := y; yy < y+h; yy++ {
			for xx := x; xx < x+w; xx++ {
				d.SetPixel(xx, yy, on)
			}
		}
		return
	}
	d.DrawLine(x, y, x+w-1, y, on)
	d.DrawLine(x, y+h-1, x+w-1, y+h-1, on)
	d.DrawLine(x, y, x, y+h-1, on)
	d.DrawLine(x+w-1, y, x+w-1, y+h-1, on)
}

// Blit copies a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. columns
// holds heightPages groups of columns, page by page: byte p*n + c is column c of page row p
// (n = len(columns) / heightPages). The bitmap is clipped to the framebuffer. It returns
// ErrUC1611sArgument if y is not a multiple of 8 or heightPages is 0.
func (d *UC1611sFull) Blit(x, y int, columns []byte, heightPages int) error {
	if y%8 != 0 || heightPages <= 0 {
		return ErrUC1611sArgument
	}
	n := len(columns) / heightPages
	for p := 0; p < heightPages; p++ {
		page := y/8 + p
		if page < 0 || page >= d.pages {
			continue
		}
		for c := 0; c < n; c++ {
			if xx := x + c; xx >= 0 && xx < d.width {
				d.fb[page*d.width+xx] = columns[p*n+c]
			}
		}
	}
	return nil
}

// ShowPage writes one framebuffer page to the display RAM (cheaper partial update). Out-of-range pages
// are ignored.
func (d *UC1611sFull) ShowPage(page int) error {
	if page < 0 || page >= d.pages {
		return nil
	}
	return d.showPage(page)
}

// SetContrast sets the potentiometer PM that adjusts VLCD (contrast), 0..255.
func (d *UC1611sFull) SetContrast(value uint8) error {
	d.contrast = value
	return d.command(uc1611sCmdPotentiometer, value)
}

func uc1611sBit(b bool) byte {
	if b {
		return 1
	}
	return 0
}

// SetDisplay switches the display on, or into sleep (registers and RAM kept, below 1 uA).
func (d *UC1611sFull) SetDisplay(on bool) error { return d.command(uc1611sCmdDisplay | uc1611sBit(on)) }

// Sleep enters sleep mode: drivers, charge pump and clock stop; the host interface stays active.
func (d *UC1611sFull) Sleep() error { return d.SetDisplay(false) }

// Wake leaves sleep mode. The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump
// restarts, so the driver polls DE (at most 50 ms) instead of sleeping.
func (d *UC1611sFull) Wake() error {
	if err := d.SetDisplay(true); err != nil {
		return err
	}
	d.waitDisplayEnabled(50 * time.Millisecond)
	return nil
}

// SetInvert inverts the displayed image without changing RAM.
func (d *UC1611sFull) SetInvert(on bool) error { return d.command(uc1611sCmdInverse | uc1611sBit(on)) }

// SetAllOn forces all pixels on (test mode, RAM unchanged).
func (d *UC1611sFull) SetAllOn(on bool) error { return d.command(uc1611sCmdAllOn | uc1611sBit(on)) }

// SetFlip mirrors columns (MX) and/or rows (MY). MY acts at once; MX is applied to RAM writes, so call
// Show afterwards to re-send the framebuffer.
func (d *UC1611sFull) SetFlip(mirrorX, mirrorY bool) error {
	d.mx, d.my = uc1611sBit(mirrorX), uc1611sBit(mirrorY)
	return d.command(uc1611sCmdMapping, d.mapping())
}

// SetScrollLine scrolls the image up by line rows (hardware scroll, wraps at 160 rows); 0 .. 159 - 2 * fixed lines.
func (d *UC1611sFull) SetScrollLine(line uint8) error {
	return d.command(uc1611sCmdScrollLo|line&0x0F, uc1611sCmdScrollHi|(line>>4)&0x0F)
}

// SetFixedLines keeps the first 2 * lines rows out of the scroll (FL, 0..15).
func (d *UC1611sFull) SetFixedLines(lines uint8) error {
	return d.command(uc1611sCmdFixed | lines&0x0F)
}

// SetBiasRatio selects the LCD bias ratio: 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12.
func (d *UC1611sFull) SetBiasRatio(ratio uint8) error { return d.command(uc1611sCmdBias | ratio&3) }

// SetTemperatureCompensation selects the VBIAS compensation: 0 = -0.05, 1 = -0.10, 2 = -0.15, 3 = 0.00 %/degC.
func (d *UC1611sFull) SetTemperatureCompensation(curve uint8) error {
	return d.command(uc1611sCmdTemp | curve&3)
}

// SetLineRate selects the line rate (On/Off mode): 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second.
func (d *UC1611sFull) SetLineRate(rate uint8) error { return d.command(uc1611sCmdLineRate | rate&3) }

// SetPump selects the internal 11x charge pump (true, reset) or an external VLCD supply (false).
func (d *UC1611sFull) SetPump(internal bool) error {
	return d.command(uc1611sCmdPump | 3*uc1611sBit(internal))
}

// SetPanelLoading tells the chip the panel capacitance: true = 33..55 nF (reset), false = up to 33 nF.
func (d *UC1611sFull) SetPanelLoading(high bool) error {
	return d.command(uc1611sCmdPanel | 3*uc1611sBit(high))
}

// SetPartialDisplay scans only rows start..end (the rest sleeps), or returns to full scan. A negative
// end selects height - 1.
func (d *UC1611sFull) SetPartialDisplay(on bool, start uint8, end int) error {
	if !on {
		return d.command(uc1611sCmdPartial)
	}
	last := byte(d.height - 1)
	if end >= 0 {
		last = byte(end)
	}
	return d.command(uc1611sCmdPartStart, start, uc1611sCmdPartEnd, last, uc1611sCmdPartial|3)
}

// SetNLineInversion sets N-line inversion (cross-talk tuning): lines 0 (off) .. 63, optional XOR mode.
func (d *UC1611sFull) SetNLineInversion(lines uint8, xor bool) error {
	return d.command(uc1611sCmdNLine, 0x40*uc1611sBit(xor)|lines&0x3F)
}

// SetIsolationClock sets the isolation clock before (front) and after (back) the COM pulse, 0..15 each.
func (d *UC1611sFull) SetIsolationClock(front, back uint8) error {
	if err := d.command(uc1611sCmdIsolation, 0x13, front&0x0F); err != nil {
		return err
	}
	return d.command(uc1611sCmdIsolation, 0x14, back&0x0F)
}

// SetDisplayPattern selects which of the four RAM screens (patterns, 0..3) is displayed.
func (d *UC1611sFull) SetDisplayPattern(pattern uint8) error {
	return d.command(uc1611sCmdPattern | (pattern&3)<<1 | 1)
}

// SetWritePattern selects the RAM screen (0..3) that Show / ShowPage write to (PA[6:5]).
func (d *UC1611sFull) SetWritePattern(pattern uint8) { d.pattern = pattern & 3 }

// ReadStatus reads the three status bytes (a NOP is sent first to restart the chip's status cycle):
// Status1 (Ver, MX, MY, WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]) and Status3 (product code 1
// in bits 7..4, ESD flag in bit 0).
func (d *UC1611sFull) ReadStatus() ([]byte, error) { return d.readStatus() }

// ReadRAM reads n (at most 255) bytes of display RAM; the dummy byte after the address change is
// discarded.
func (d *UC1611sFull) ReadRAM(page, column, n int) ([]byte, error) {
	if n < 0 || n > 255 {
		return nil, ErrUC1611sArgument
	}
	var out []byte
	err := d.retry(func() error {
		if err := d.cmd.Write(d.addressBytes(page, column)); err != nil {
			return err
		}
		b, err := d.data.Read(n + 1)
		if err != nil {
			return err
		}
		out = append([]byte(nil), b[1:]...)
		return nil
	})
	return out, err
}

// Reset sends System Reset and reconfigures with the cached contrast and mapping (RAM is kept).
func (d *UC1611sFull) Reset() error {
	if err := d.resetChip(); err != nil {
		return err
	}
	if err := d.configure(); err != nil {
		return err
	}
	if err := d.enable(); err != nil {
		return err
	}
	d.waitDisplayEnabled(50 * time.Millisecond)
	return nil
}

// SendCommand sends raw bytes as one command transaction (retried on NACK).
func (d *UC1611sFull) SendCommand(b ...byte) error { return d.command(b...) }

// SendData sends raw bytes to the display RAM (chunked) at the current RAM address. A failed chunk is
// not retried (the partial write cannot be continued); the failure is counted.
func (d *UC1611sFull) SendData(b []byte) error {
	if err := d.writeChunks(b); err != nil {
		d.errors++
		return err
	}
	return nil
}
