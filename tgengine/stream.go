package tgengine

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gotd/td/telegram"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
)

const (
	chunkSize   = 1024 * 1024 // 1 MiB download chunk (aligned to GetFile boundaries)
	maxPoolSize = 16          // upper bound of connections per DC pool

	// tailProbeWindow is how close to EOF a concurrent range request must start to
	// be treated as a player's container-index probe (e.g. MKV Cues) rather than a
	// reopened main read. Index probes are served directly without disturbing the
	// disk-cache ring; a mid-stream concurrent read instead means the main reader
	// moved and the ring's owner is now orphaned.
	tailProbeWindow = 16 << 20 // 16 MiB

	// buildTag is stamped on every stream request so the log unambiguously
	// identifies the running engine build. Bump it on every engine change.
	buildTag = "media-6"
)

// proxyServer is the localhost HTTP server ExoPlayer streams from.
type proxyServer struct {
	e        *Engine
	ln       net.Listener
	srv      *http.Server
	port     int
}

// lanProxyPort is the preferred fixed port for the proxy so a paired box only has
// to be told the phone's IP once (it stays valid across app restarts). Falls back
// to an ephemeral port if it's taken.
const lanProxyPort = 8770

// StartProxy starts the HTTP proxy and returns the port it listens on. It binds on
// all interfaces (0.0.0.0) so it can double as a LAN "server" for other devices;
// LAN access is still gated by SetLanToken (loopback is always allowed).
func (e *Engine) StartProxy() (int, error) {
	if e.proxy != nil {
		return e.proxy.port, nil
	}
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", lanProxyPort))
	if err != nil {
		ln, err = net.Listen("tcp", "0.0.0.0:0")
	}
	if err != nil {
		return 0, err
	}
	mux := http.NewServeMux()
	p := &proxyServer{e: e, ln: ln, port: ln.Addr().(*net.TCPAddr).Port}
	mux.HandleFunc("/stream", p.handleStream)
	p.srv = &http.Server{Handler: mux}
	go func() { _ = p.srv.Serve(ln) }()
	e.proxy = p
	return p.port, nil
}

func (p *proxyServer) stop() {
	if p.srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = p.srv.Shutdown(ctx)
	}
}

// partInfo describes one Telegram message (part) of the logical file.
type partInfo struct {
	msgID       int
	doc         *tg.Document
	size        int64
	globalStart int64 // byte offset of this part within the logical file
}

// handleStream serves GET/HEAD /stream?channel=<id>&messages=<m1-m2-...>&conns=<n>
// with HTTP range support, streaming bytes from Telegram.
func (p *proxyServer) handleStream(w http.ResponseWriter, r *http.Request) {
	e := p.e
	// LAN gate: requests from other devices must carry the token; loopback (this
	// device's own player) is always allowed. Empty token = LAN access disabled.
	if !isLoopbackAddr(r.RemoteAddr) {
		tok := e.lanTokenValue()
		if tok == "" || r.URL.Query().Get("token") != tok {
			logW("stream req rejected: unauthorized LAN client %s", r.RemoteAddr)
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
	}
	if !e.IsReady() {
		logW("stream req rejected: engine not ready")
		http.Error(w, "engine not ready", http.StatusServiceUnavailable)
		return
	}
	q := r.URL.Query()
	channelID, err := strconv.ParseInt(q.Get("channel"), 10, 64)
	if err != nil {
		http.Error(w, "bad channel", http.StatusBadRequest)
		return
	}
	msgIDs, err := parseMessageIDs(q.Get("messages"))
	if err != nil || len(msgIDs) == 0 {
		http.Error(w, "bad messages", http.StatusBadRequest)
		return
	}
	conns := atoiDefault(q.Get("conns"), 1)
	if conns < 1 {
		conns = 1
	}
	if conns > maxPoolSize {
		conns = maxPoolSize
	}

	logI("stream req [build=%s]: channel=%d msgs=%v conns=%d range=%q method=%s",
		buildTag, channelID, msgIDs, conns, r.Header.Get("Range"), r.Method)

	ctx := r.Context()
	input, err := e.resolveChannel(ctx, channelID)
	if err != nil {
		logE("resolveChannel %d failed: %v", channelID, err)
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}

	// Resolve all parts and compute the total logical size.
	parts := make([]partInfo, 0, len(msgIDs))
	var total int64
	for _, mid := range msgIDs {
		doc, err := e.fetchDocument(ctx, channelID, input, mid)
		if err != nil {
			logE("fetchDocument msg=%d failed: %v", mid, err)
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		logI("part msg=%d size=%d dc=%d mime=%s", mid, doc.Size, doc.DCID, doc.MimeType)
		parts = append(parts, partInfo{msgID: mid, doc: doc, size: doc.Size, globalStart: total})
		total += doc.Size
	}
	logI("resolved %d part(s), total=%d bytes", len(parts), total)
	contentType := parts[0].doc.MimeType
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	start, end, isRange := parseRange(r.Header.Get("Range"), total)
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", contentType)
	if isRange {
		if start > end || start >= total {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", total))
			http.Error(w, "range not satisfiable", http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, total))
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.WriteHeader(http.StatusPartialContent)
	} else {
		start, end = 0, total-1
		w.Header().Set("Content-Length", strconv.FormatInt(total, 10))
		w.WriteHeader(http.StatusOK)
	}
	if r.Method == http.MethodHead {
		return
	}

	logI("serving bytes %d-%d (%d bytes) range=%v", start, end, end-start+1, isRange)

	// Optional disk cache: route through the per-stream write-ahead ring so a
	// continuous downloader keeps the pipe hot and the look-ahead lives off-heap.
	if q.Get("cache") == "1" {
		var durationSec float64
		for i := range parts {
			durationSec += durationOf(parts[i].doc)
		}
		key := fmt.Sprintf("%d|%s|%d", channelID, q.Get("messages"), conns)
		ring, rerr := e.getRing(key, channelID, input, parts, total, durationSec, conns)
		if rerr != nil {
			logW("diskcache: init failed (%v); serving direct", rerr)
		} else if ring != nil {
			err := ring.serve(ctx, w, start, end)
			if errors.Is(err, errRingBusy) {
				// The ring already has an owner. Two cases:
				if start >= total-tailProbeWindow {
					// (a) A small probe near EOF (MKV Cues/index) while the main
					// reader owns the ring: serve it directly and leave the ring
					// intact for the main reader.
					logI("diskcache: ring busy (tail probe), serving bytes %d-%d direct", start, end)
				} else {
					// (b) A concurrent mid-stream read: the player reopened its
					// main connection at a new offset and abandoned the one that
					// owns the ring (ExoPlayer does this WITHOUT closing the old
					// socket, so the old serve() stays blocked on Write and keeps
					// owning the ring). The orphaned producer would keep competing
					// for the media pool and starve this real reader, so drop the
					// cache and serve everything directly from here on.
					logW("diskcache: ring busy at offset %d (player reopened mid-stream); dropping cache, serving direct", start)
					go e.clearRing()
				}
				// fall through to streamRange
			} else {
				if err != nil {
					logW("stream ended (cached): %v", err)
				}
				return
			}
		}
	}

	if err := e.streamRange(ctx, w, channelID, input, parts, start, end, conns); err != nil {
		// Client disconnects are normal; just stop.
		logW("stream ended: %v", err)
	}
}

// chunkJob is a single GetFile request plus the slice of its result to emit.
type chunkJob struct {
	part       *partInfo
	offset     int64 // aligned offset within the part
	limit      int   // bytes to request
	sliceStart int   // first byte of result to emit
	sliceEnd   int   // last byte of result to emit (inclusive)
}

// streamRange writes the logical byte range [start,end] to w, fetching 1 MiB
// chunks with up to conns parallel GetFile calls and emitting them in order.
func (e *Engine) streamRange(ctx context.Context, w interface{ Write([]byte) (int, error) },
	channelID int64, input tg.InputChannelClass, parts []partInfo, start, end int64, conns int) error {

	// Build the ordered list of chunk jobs spanning all involved parts.
	var jobs []chunkJob
	for i := range parts {
		pt := &parts[i]
		pStart := pt.globalStart
		pEnd := pt.globalStart + pt.size - 1
		if pEnd < start || pStart > end {
			continue
		}
		localStart := maxI64(start, pStart) - pStart
		localEnd := minI64(end, pEnd) - pStart
		off := localStart - (localStart % chunkSize)
		for off <= localEnd {
			limit := int64(chunkSize)
			if off+limit > pt.size {
				limit = roundUp(pt.size-off, 4096)
			}
			emitFrom := maxI64(localStart, off) - off
			emitTo := minI64(localEnd, off+limit-1) - off
			jobs = append(jobs, chunkJob{
				part:       pt,
				offset:     off,
				limit:      int(limit),
				sliceStart: int(emitFrom),
				sliceEnd:   int(emitTo),
			})
			off += chunkSize
		}
	}

	// Pipeline with read-ahead: keep up to `conns` getChunk calls IN FLIGHT (sem),
	// but let the producer fetch a large WARM PREFETCH window ahead of the in-order
	// emit position. This is what absorbs bursty clients: ExoPlayer fills its
	// (heap-bounded) buffer, stops reading → TCP backpressure blocks our w.Write,
	// then drains and resumes. With only a tiny window the producer idles during
	// the pause and the Telegram fetch pipeline cold-restarts on resume (~2-3s gap
	// → stall). A big window (~64 MiB ≈ a few seconds of high-bitrate video) keeps
	// chunks ready and workers warm, so resume is instant and throughput uses the
	// full link instead of collapsing to ~bitrate. Concurrency stays bounded by
	// `conns`; only the buffered-ahead depth grows (native RAM, cheap).
	streamCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	// chunkSize is 1 MiB, so readAhead is also the warm-prefetch size in MiB.
	readAhead := 8 * conns
	if readAhead < 64 {
		readAhead = 64 // floor: cover the resume ramp even at low conns
	}
	if readAhead > 128 {
		readAhead = 128 // ceiling: bound native RAM per stream
	}
	resultChans := make(chan chan chunkResult, readAhead)

	// Producer: spawn workers bounded by `conns` in flight (sem), enqueueing
	// result channels in job order up to `readAhead` ahead of emission.
	go func() {
		defer close(resultChans)
		sem := make(chan struct{}, conns)
		for i := range jobs {
			select {
			case <-streamCtx.Done():
				return
			case sem <- struct{}{}:
			}
			rc := make(chan chunkResult, 1)
			select {
			case <-streamCtx.Done():
				<-sem
				return
			case resultChans <- rc:
			}
			go func(j chunkJob, rc chan chunkResult) {
				defer func() { <-sem }()
				data, err := e.getChunk(channelID, input, j.part, j.offset, j.limit, conns)
				if err != nil {
					rc <- chunkResult{err: err}
					return
				}
				lo, hi := j.sliceStart, j.sliceEnd
				if hi >= len(data) {
					hi = len(data) - 1
				}
				if lo > hi {
					rc <- chunkResult{}
					return
				}
				rc <- chunkResult{data: data[lo : hi+1]}
			}(jobs[i], rc)
		}
	}()

	// Consumer: emit in order. Returning cancels streamCtx (defer), unblocking
	// the producer and any in-flight getChunk calls.
	t0 := time.Now()
	var emitted int64
	nextLog := int64(32 << 20) // log throughput every 32 MiB
	for rc := range resultChans {
		res := <-rc
		if res.err != nil {
			return res.err
		}
		if len(res.data) == 0 {
			continue
		}
		if _, err := w.Write(res.data); err != nil {
			return err // client disconnected
		}
		emitted += int64(len(res.data))
		if emitted >= nextLog {
			if el := time.Since(t0).Seconds(); el > 0 {
				logI("download: %d MiB in %.1fs = %.1f MB/s (%.0f Mbps), conns=%d",
					emitted>>20, el, float64(emitted)/1e6/el, float64(emitted)*8/1e6/el, conns)
			}
			nextLog += 32 << 20
		}
	}
	return nil
}

// chunkResult carries one pipelined chunk's trimmed bytes or its error.
type chunkResult struct {
	data []byte
	err  error
}

// getChunk fetches one chunk from the document's DC using up to `conns`
// connections, handling flood-wait, expired file references and CDN redirects.
//
// The RPCs run on a context DETACHED from the HTTP request: ExoPlayer routinely
// closes/reopens connections (seek, buffer management), and if those
// cancellations reached the pooled gotd connections they'd tear the whole pool
// down and trigger an endless reconnect storm. The pipeline stops *issuing* new
// chunks when the request ends; the few already in flight finish on their own
// timeout instead of killing connections.
func (e *Engine) getChunk(channelID int64, input tg.InputChannelClass,
	part *partInfo, offset int64, limit, conns int) ([]byte, error) {

	// Engine-lived context (NOT the per-request one): cancelling a request on
	// seek/abandon must not cancel in-flight getFile RPCs and tear down pooled
	// media connections — that caused the conn-id churn spiral. The pipeline
	// already stops issuing new chunks when the request ends; the few in-flight
	// ones finish and are discarded.
	ctx := e.dlCtx

	media, err := e.mediaClient(ctx, part.doc.DCID, conns)
	if err != nil {
		return nil, err
	}
	loc := &tg.InputDocumentFileLocation{
		ID:            part.doc.ID,
		AccessHash:    part.doc.AccessHash,
		FileReference: part.doc.FileReference,
	}

	const maxAttempts = 6
	for attempt := 0; attempt < maxAttempts; attempt++ {
		res, err := media.UploadGetFile(ctx, &tg.UploadGetFileRequest{
			Precise:  true,
			Location: loc,
			Offset:   offset,
			Limit:    limit,
		})
		if err != nil {
			if d, ok := tgerr.AsFloodWait(err); ok {
				logW("flood wait %v on dc=%d offset=%d (attempt %d)", d, part.doc.DCID, offset, attempt)
				select {
				case <-time.After(d):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			if rpc, ok := tgerr.As(err); ok && strings.Contains(rpc.Type, "FILE_REFERENCE_EXPIRED") {
				logW("file reference expired msg=%d, refreshing", part.msgID)
				fresh, rerr := e.refreshDocument(ctx, channelID, input, part.msgID)
				if rerr != nil {
					return nil, rerr
				}
				part.doc = fresh
				loc.FileReference = fresh.FileReference
				loc.AccessHash = fresh.AccessHash
				continue
			}
			// Transient connection blip (pool reconnect, dead conn): brief
			// backoff and retry on another pooled connection.
			if attempt < maxAttempts-1 {
				logW("getFile dc=%d offset=%d transient: %v (retry %d)", part.doc.DCID, offset, err, attempt)
				select {
				case <-time.After(300 * time.Millisecond):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			logE("getFile dc=%d offset=%d limit=%d failed: %v", part.doc.DCID, offset, limit, err)
			return nil, err
		}

		switch f := res.(type) {
		case *tg.UploadFile:
			return f.Bytes, nil
		case *tg.UploadFileCDNRedirect:
			logI("CDN redirect to dc=%d offset=%d", f.DCID, offset)
			return e.getCDNChunk(ctx, f, offset, limit, conns)
		default:
			return nil, fmt.Errorf("unexpected getFile result %T", res)
		}
	}
	return nil, fmt.Errorf("getChunk: too many retries")
}

// getCDNChunk downloads and decrypts a chunk from a CDN DC.
// Ported from gotd's downloader CDN schema. Untested until a redirect occurs.
func (e *Engine) getCDNChunk(ctx context.Context, redirect *tg.UploadFileCDNRedirect,
	offset int64, limit, conns int) ([]byte, error) {

	cdn, err := e.mediaClient(ctx, redirect.DCID, conns)
	if err != nil {
		return nil, fmt.Errorf("cdn dc %d: %w", redirect.DCID, err)
	}
	for attempt := 0; attempt < 6; attempt++ {
		r, err := cdn.UploadGetCDNFile(ctx, &tg.UploadGetCDNFileRequest{
			FileToken: redirect.FileToken,
			Offset:    offset,
			Limit:     limit,
		})
		if err != nil {
			if d, ok := tgerr.AsFloodWait(err); ok {
				select {
				case <-time.After(d):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			return nil, err
		}
		switch f := r.(type) {
		case *tg.UploadCDNFile:
			return cdnDecrypt(redirect, f.Bytes, offset)
		case *tg.UploadCDNFileReuploadNeeded:
			// Ask the master DC to re-upload to CDN, then retry.
			if _, err := e.api.UploadReuploadCDNFile(ctx, &tg.UploadReuploadCDNFileRequest{
				FileToken:     redirect.FileToken,
				RequestToken:  f.RequestToken,
			}); err != nil {
				return nil, err
			}
			continue
		default:
			return nil, fmt.Errorf("unexpected getCDNFile result %T", r)
		}
	}
	return nil, fmt.Errorf("getCDNChunk: too many retries")
}

// cdnDecrypt decrypts a CDN chunk (AES-CTR, IV tail = offset/16). From gotd.
func cdnDecrypt(redirect *tg.UploadFileCDNRedirect, src []byte, offset int64) ([]byte, error) {
	block, err := aes.NewCipher(redirect.EncryptionKey)
	if err != nil {
		return nil, err
	}
	if block.BlockSize() != len(redirect.EncryptionIv) {
		return nil, fmt.Errorf("bad CDN IV length")
	}
	iv := make([]byte, len(redirect.EncryptionIv))
	copy(iv, redirect.EncryptionIv)
	binary.BigEndian.PutUint32(iv[len(iv)-4:], uint32(offset/16))
	dst := make([]byte, len(src))
	cipher.NewCTR(block, iv).XORKeyStream(dst, src)
	return dst, nil
}

// warmPool eagerly opens `conns` connections by firing that many `open` calls
// concurrently and waiting for them. gotd's pool creates a connection per
// in-flight request (up to its max), so concurrent calls force the pool to open
// up to `conns` distinct connections, which then sit in its free list ready for
// the real getFile calls. A single connection needs no warming. `open` errors
// are the caller's concern; warmPool only cares that the connection was opened.
func warmPool(ctx context.Context, conns int, open func(context.Context) error) {
	if conns <= 1 {
		return
	}
	var wg sync.WaitGroup
	for i := 0; i < conns; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = open(ctx)
		}()
	}
	wg.Wait()
}

// poolNeedsResize reports whether a cached media pool created with existingMax
// connections must be recreated to honor a request for `requested` connections.
// We only grow pools (requested > existingMax): shrinking would needlessly tear
// down healthy in-use connections, and the larger pool already serves smaller
// requests fine (the per-stream `conns` still bounds in-flight chunks).
func poolNeedsResize(existingMax, requested int64) bool {
	return requested > existingMax
}

// mediaClient returns (and caches) a tg.Client bound to a pool of up to `conns`
// connections on the given DC.
func (e *Engine) mediaClient(ctx context.Context, dcID, conns int) (*tg.Client, error) {
	max := int64(conns)
	if max < 1 {
		max = 1
	}
	if max > maxPoolSize {
		max = maxPoolSize
	}

	e.mediaMu.Lock()
	defer e.mediaMu.Unlock()
	if e.mediaAPI == nil {
		e.mediaAPI = make(map[int]*tg.Client)
		e.mediaInv = make(map[int]telegram.CloseInvoker)
		e.mediaMax = make(map[int]int64)
	}
	if c, ok := e.mediaAPI[dcID]; ok {
		if !poolNeedsResize(e.mediaMax[dcID], max) {
			return c, nil
		}
		// The connection setting was raised since this DC's pool was first built,
		// so the cached pool capped concurrency below the new value. Tear it down
		// and recreate at the larger size. In-flight chunks on the old pool finish
		// against the closing invoker; new chunks use the resized pool.
		logI("resizing media pool dc=%d from %d to %d conns", dcID, e.mediaMax[dcID], max)
		_ = e.mediaInv[dcID].Close()
		delete(e.mediaAPI, dcID)
		delete(e.mediaInv, dcID)
		delete(e.mediaMax, dcID)
	}
	// Download on the DC's MEDIA endpoint with N connections. Telegram throttles
	// sustained getFile on the regular endpoint (the flood waits we saw); the
	// media endpoint is not throttled — a single account reaches ~10 MB/s there.
	// gotd does not export auth in the pool path (export lives only in
	// transfer.go), so the home-DC media pool reuses the existing home auth key.
	inv, err := e.client.MediaOnly(ctx, dcID, max)
	if err != nil {
		logW("MediaOnly dc %d failed (%v); falling back to regular pool (throttled)", dcID, err)
		inv, err = e.client.Pool(max)
		if err != nil {
			logE("connect dc %d failed: %v", dcID, err)
			return nil, fmt.Errorf("connect dc %d: %w", dcID, err)
		}
		logI("media client ready dc=%d conns=%d (REGULAR endpoint — throttled)", dcID, max)
	} else {
		logI("media client ready dc=%d conns=%d (media endpoint)", dcID, max)
	}
	c := tg.NewClient(inv)
	e.mediaAPI[dcID] = c
	e.mediaInv[dcID] = inv
	e.mediaMax[dcID] = max
	// Pre-open the pool to `max` connections. The streaming pipeline already
	// opens N connections for any range spanning ≥N chunks, but a small initial
	// range (ExoPlayer probing the file head, or a sub-N-MiB file) would leave us
	// under-parallelized; warming guarantees all N are ready. Fire-and-forget on
	// the engine-lived ctx — holding mediaMu through N handshakes would serialize
	// every concurrent getChunk; warmPool errors are irrelevant (goal is just to
	// open the connections, which then land in the pool's free list).
	go warmPool(ctx, int(max), func(ctx context.Context) error {
		_, err := c.HelpGetConfig(ctx)
		return err
	})
	return c, nil
}

func (e *Engine) closeMedia() {
	e.mediaMu.Lock()
	defer e.mediaMu.Unlock()
	for _, inv := range e.mediaInv {
		_ = inv.Close()
	}
	e.mediaAPI = nil
	e.mediaInv = nil
	e.mediaMax = nil
}

// --- small helpers ---

func parseMessageIDs(s string) ([]int, error) {
	if s == "" {
		return nil, fmt.Errorf("empty")
	}
	// Accept both dash- and comma-separated lists.
	sep := "-"
	if strings.Contains(s, ",") {
		sep = ","
	}
	fields := strings.Split(s, sep)
	out := make([]int, 0, len(fields))
	for _, f := range fields {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		v, err := strconv.Atoi(f)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

func parseRange(header string, size int64) (start, end int64, ok bool) {
	if header == "" || !strings.HasPrefix(header, "bytes=") {
		return 0, size - 1, false
	}
	spec := strings.TrimPrefix(header, "bytes=")
	if i := strings.IndexByte(spec, ','); i >= 0 {
		spec = spec[:i] // only the first range
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, size - 1, false
	}
	startStr, endStr := spec[:dash], spec[dash+1:]
	if startStr == "" {
		// suffix: last N bytes
		n, err := strconv.ParseInt(endStr, 10, 64)
		if err != nil || n <= 0 {
			return 0, size - 1, false
		}
		if n > size {
			n = size
		}
		return size - n, size - 1, true
	}
	s, err := strconv.ParseInt(startStr, 10, 64)
	if err != nil {
		return 0, size - 1, false
	}
	if endStr == "" {
		return s, size - 1, true
	}
	en, err := strconv.ParseInt(endStr, 10, 64)
	if err != nil {
		return 0, size - 1, false
	}
	if en > size-1 {
		en = size - 1
	}
	return s, en, true
}

// isLoopbackAddr reports whether a "host:port" remote address is a loopback
// client (this device's own player), which never needs the LAN token.
func isLoopbackAddr(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func atoiDefault(s string, def int) int {
	if v, err := strconv.Atoi(s); err == nil {
		return v
	}
	return def
}

func roundUp(v, mult int64) int64 {
	if v <= 0 {
		return mult
	}
	return (v + mult - 1) / mult * mult
}

func maxI64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func minI64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}
