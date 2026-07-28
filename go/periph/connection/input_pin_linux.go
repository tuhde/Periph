//go:build linux && !tinygo

// GpioInputPin is the Linux implementation of InputPin, backed by the GPIO
// v2 character-device edge-event ioctl on /dev/gpiochip0. Unlike the v1
// GPIO ioctls used elsewhere in this package (gpio_linux.go — static
// get/set line values, no edge support), GPIO_V2_GET_LINE_IOCTL can request
// a line configured for rising/falling edge detection and deliver each
// edge as a struct gpio_v2_line_event read from the returned line fd.
package connection

import (
	"fmt"
	"sync"
	"unsafe"

	"golang.org/x/sys/unix"
)

// GPIO v2 ioctl numbers, struct sizes, and flag/event bits from
// <linux/gpio.h>. Precomputed from the standard Linux _IOWR(0xB4, nr, size)
// encoding — GPIO_V2_GET_LINE_IOCTL = _IOWR(0xB4, 0x07, sizeof(struct
// gpio_v2_line_request)) = 0xc250b407, matching the constant used by other
// Go GPIO v2 client libraries.
const (
	gpioV2LinesMax        = 64
	gpioMaxNameSize       = 32
	gpioV2LineNumAttrsMax = 10

	gpioV2LineFlagInput       = 1 << 2
	gpioV2LineFlagEdgeRising  = 1 << 4
	gpioV2LineFlagEdgeFalling = 1 << 5

	gpioV2LineEventRisingEdge  = 1
	gpioV2LineEventFallingEdge = 2

	gpioV2GetLineIoctl = 0xc250b407

	sizeofGpioV2LineEvent = 48
)

// gpioV2LineAttribute mirrors struct gpio_v2_line_attribute. The union of
// flags/values/debounce_period_us is represented as a plain uint64 since
// this package never populates per-line attributes (numAttrs stays 0; see
// gpioV2LineConfig).
type gpioV2LineAttribute struct {
	id      uint32
	padding uint32
	value   uint64
}

// gpioV2LineConfigAttribute mirrors struct gpio_v2_line_config_attribute.
type gpioV2LineConfigAttribute struct {
	attr gpioV2LineAttribute
	mask uint64
}

// gpioV2LineConfig mirrors struct gpio_v2_line_config. flags applies to
// every requested line; attrs/numAttrs are left zero since GpioInputPin
// only ever requests a single line.
type gpioV2LineConfig struct {
	flags    uint64
	numAttrs uint32
	padding  [5]uint32
	attrs    [gpioV2LineNumAttrsMax]gpioV2LineConfigAttribute
}

// gpioV2LineRequest mirrors struct gpio_v2_line_request.
type gpioV2LineRequest struct {
	offsets         [gpioV2LinesMax]uint32
	consumer        [gpioMaxNameSize]byte
	config          gpioV2LineConfig
	numLines        uint32
	eventBufferSize uint32
	padding         [5]uint32
	fd              int32
}

// gpioV2LineEvent mirrors struct gpio_v2_line_event (48 bytes).
type gpioV2LineEvent struct {
	timestampNs uint64
	id          uint32
	offset      uint32
	seqno       uint32
	lineSeqno   uint32
	padding     [6]uint32
}

// GpioInputPin delivers edge notifications from a single GPIO line via the
// GPIO v2 character-device edge-event ioctl. The line is requested once,
// with both rising and falling edges enabled; individual handlers filter by
// their own requested Trigger in software, mirroring PollingInputPin's
// dispatch so a single physical line can serve handlers with different
// trigger directions concurrently.
type GpioInputPin struct {
	lineFd int

	mu       sync.Mutex
	handlers map[int]edgeHandlerEntry
	nextID   int
}

// NewGpioInputPin opens /dev/gpiochip0 and requests line (a BCM line
// offset) as an input with both edges enabled.
func NewGpioInputPin(line uint32) (*GpioInputPin, error) {
	chipFd, err := unix.Open("/dev/gpiochip0", unix.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("open /dev/gpiochip0: %w", err)
	}
	defer unix.Close(chipFd)

	req := gpioV2LineRequest{numLines: 1}
	req.offsets[0] = line
	copy(req.consumer[:], "periph")
	req.config.flags = gpioV2LineFlagInput | gpioV2LineFlagEdgeRising | gpioV2LineFlagEdgeFalling

	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(chipFd), uintptr(gpioV2GetLineIoctl), uintptr(unsafe.Pointer(&req))); errno != 0 {
		return nil, fmt.Errorf("ioctl(GPIO_V2_GET_LINE_IOCTL): %v", errno)
	}

	p := &GpioInputPin{
		lineFd:   int(req.fd),
		handlers: make(map[int]edgeHandlerEntry),
	}
	go p.loop()
	return p, nil
}

// OnEdge registers handler to run on each edge matching trigger. Returns a
// function that removes it.
func (p *GpioInputPin) OnEdge(trigger Trigger, handler func()) (unsubscribe func()) {
	p.mu.Lock()
	id := p.nextID
	p.nextID++
	p.handlers[id] = edgeHandlerEntry{trigger: trigger, fn: handler}
	p.mu.Unlock()

	return func() {
		p.mu.Lock()
		delete(p.handlers, id)
		p.mu.Unlock()
	}
}

// loop blocks reading edge events from the line fd until it is closed,
// dispatching each event to every handler whose trigger matches.
func (p *GpioInputPin) loop() {
	buf := make([]byte, sizeofGpioV2LineEvent)
	for {
		n, err := unix.Read(p.lineFd, buf)
		if err != nil || n != len(buf) {
			return // fd closed or errored — exit the goroutine
		}
		ev := (*gpioV2LineEvent)(unsafe.Pointer(&buf[0]))

		var trigger Trigger
		switch ev.id {
		case gpioV2LineEventRisingEdge:
			trigger = Rising
		case gpioV2LineEventFallingEdge:
			trigger = Falling
		default:
			continue
		}

		p.mu.Lock()
		toCall := make([]func(), 0, len(p.handlers))
		for _, h := range p.handlers {
			if h.trigger == Change || h.trigger == trigger {
				toCall = append(toCall, h.fn)
			}
		}
		p.mu.Unlock()

		for _, fn := range toCall {
			fn()
		}
	}
}

// Close releases the line file descriptor, which unblocks and terminates
// the background read loop.
func (p *GpioInputPin) Close() error {
	return unix.Close(p.lineFd)
}
