package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
	"unicode"

	mnet "github.com/metacubex/mihomo/common/net"
	"github.com/metacubex/mihomo/component/ca"
)

type credentials struct {
	user      [sha256.Size]byte
	password  [sha256.Size]byte
	challenge string
}

func newCredentials(user, password string) (credentials, error) {
	if len(user) == 0 || len(password) < 16 || len(user) > 255 || len(password) > 255 ||
		strings.ContainsRune(user, ':') || strings.ContainsFunc(user, unicode.IsControl) {
		return credentials{}, errors.New("本地代理认证信息无效")
	}
	realm := strings.NewReplacer("\\", "\\\\", "\"", "\\\"").Replace(user)
	return credentials{sha256.Sum256([]byte(user)), sha256.Sum256([]byte(password)),
		"Basic realm=\"infinity-proxy-" + realm + "\""}, nil
}

func (c credentials) matches(user, password []byte) bool {
	u, p := sha256.Sum256(user), sha256.Sum256(password)
	return (subtle.ConstantTimeCompare(c.user[:], u[:]) & subtle.ConstantTimeCompare(c.password[:], p[:])) == 1
}

func (c credentials) matchesHTTP(header http.Header) bool {
	values := header.Values("Proxy-Authorization")
	if len(values) != 1 {
		return false
	}
	scheme, encoded, ok := strings.Cut(values[0], " ")
	if !ok || !strings.EqualFold(scheme, "basic") || len(encoded) > 684 {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return false
	}
	user, password, ok := strings.Cut(string(decoded), ":")
	return ok && c.matches([]byte(user), []byte(password))
}

func (s *proxyService) handleClient(conn net.Conn, auth credentials) {
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	limit := &io.LimitedReader{R: conn, N: 64 << 10}
	reader := bufio.NewReader(limit)
	first, err := reader.Peek(1)
	if err != nil {
		return
	}
	if first[0] == 5 {
		limit.N = math.MaxInt64
		s.handleSOCKS(conn, reader, auth)
	} else {
		s.handleHTTP(conn, reader, limit, auth)
	}
}

func sendHTTPError(conn net.Conn, status int, message, challenge string) {
	fmt.Fprintf(conn, "HTTP/1.1 %d %s\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\n", status, http.StatusText(status), len(message))
	if challenge != "" {
		fmt.Fprintf(conn, "Proxy-Authenticate: %s\r\n", challenge)
	}
	io.WriteString(conn, "\r\n"+message)
}

func urlTarget(u *url.URL) (destination, bool) {
	if u == nil || u.User != nil || u.Host == "" || u.Opaque != "" || u.Fragment != "" {
		return destination{}, false
	}
	port := u.Port()
	switch strings.ToLower(u.Scheme) {
	case "http":
		if port == "" {
			port = "80"
		}
	case "https":
		if port == "" {
			port = "443"
		}
	default:
		return destination{}, false
	}
	return parseDestination(u.Hostname(), port)
}

const publicTargetHeader = "X-Infinity-Public-Target"

func (s *proxyService) handleHTTP(conn net.Conn, reader *bufio.Reader, limit *io.LimitedReader, auth credentials) {
	req, err := http.ReadRequest(reader)
	if err != nil {
		sendHTTPError(conn, 400, "无效的代理请求", "")
		return
	}
	if !auth.matchesHTTP(req.Header) {
		sendHTTPError(conn, 407, "本地代理需要认证", auth.challenge)
		return
	}
	publicOnly := req.Header.Get(publicTargetHeader) == "1"
	req.Header.Del(publicTargetHeader)
	limit.N = math.MaxInt64
	conn.SetDeadline(time.Time{})
	if req.Method == http.MethodConnect {
		host, port, err := net.SplitHostPort(req.RequestURI)
		target, ok := parseDestination(host, port)
		if err != nil || !ok || req.ContentLength != 0 || len(req.TransferEncoding) != 0 {
			sendHTTPError(conn, 400, "无效的连接目标", "")
			return
		}
		target.publicOnly = publicOnly
		s.openTunnel(conn, reader, target, func(ok bool) bool {
			if !ok {
				sendHTTPError(conn, 502, "无法连接目标", "")
				return false
			}
			_, err := io.WriteString(conn, "HTTP/1.1 200 Connection Established\r\n\r\n")
			return err == nil
		})
		return
	}
	target, ok := urlTarget(req.URL)
	if !ok {
		sendHTTPError(conn, 400, "请求必须包含完整的目标网址", "")
		return
	}
	if expect := req.Header.Get("Expect"); expect != "" {
		if !strings.EqualFold(expect, "100-continue") {
			sendHTTPError(conn, 417, "不支持的请求预期", "")
			return
		}
		if _, err := io.WriteString(conn, "HTTP/1.1 100 Continue\r\n\r\n"); err != nil {
			return
		}
		req.Header.Del("Expect")
	}
	target.publicOnly = publicOnly
	s.forwardHTTP(conn, req, target)
}

func proxyTransport(upstream net.Conn) *http.Transport {
	var used atomic.Bool
	return &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			if used.Swap(true) {
				return nil, errors.New("出站连接已使用")
			}
			return upstream, nil
		},
		DisableKeepAlives: true, DisableCompression: true,
		TLSClientConfig:     &tls.Config{RootCAs: ca.GetCertPool()},
		TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 30 * time.Second,
		MaxResponseHeaderBytes: 256 << 10,
	}
}

func removeHopHeaders(h http.Header) {
	for _, name := range []string{"Connection", "Proxy-Connection"} {
		for _, value := range h.Values(name) {
			for entry := range strings.SplitSeq(value, ",") {
				h.Del(strings.TrimSpace(entry))
			}
		}
	}
	for _, name := range []string{"Connection", "Proxy-Connection", "Proxy-Authorization", "Proxy-Authenticate",
		"Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		h.Del(name)
	}
}

func (s *proxyService) forwardHTTP(conn net.Conn, req *http.Request, target destination) {
	removeHopHeaders(req.Header)
	req.RequestURI = ""
	req.Host = req.URL.Host
	req.Trailer = nil
	req.TransferEncoding = nil
	req.Close = true
	req = req.WithContext(s.ctx)
	ctx, cancel := context.WithTimeout(s.ctx, 20*time.Second)
	upstream, err := s.dial(ctx, target)
	cancel()
	if err != nil {
		sendHTTPError(conn, 502, "无法连接目标", "")
		return
	}
	defer upstream.Close()
	transport := proxyTransport(upstream)
	defer transport.CloseIdleConnections()
	resp, err := transport.RoundTrip(req)
	if err != nil {
		sendHTTPError(conn, 502, "无法完成目标请求", "")
		return
	}
	defer resp.Body.Close()
	removeHopHeaders(resp.Header)
	resp.Trailer = nil
	resp.Close = true
	resp.Write(conn)
}

func (s *proxyService) openTunnel(conn net.Conn, reader *bufio.Reader, target destination, reply func(bool) bool) {
	ctx, cancel := context.WithTimeout(s.ctx, 20*time.Second)
	upstream, err := s.dial(ctx, target)
	cancel()
	if err != nil {
		reply(false)
		return
	}
	defer upstream.Close()
	if reply(true) {
		mnet.RelayContext(s.ctx, mnet.WarpConnWithBioReader(conn, reader), upstream)
	}
}

func readSized(reader *bufio.Reader) ([]byte, error) {
	size, err := reader.ReadByte()
	if err != nil || size == 0 {
		return nil, errors.New("字段长度无效")
	}
	value := make([]byte, int(size))
	_, err = io.ReadFull(reader, value)
	return value, err
}

func authenticateSOCKS(reader *bufio.Reader, conn net.Conn, auth credentials) bool {
	version, err := reader.ReadByte()
	if err != nil || version != 5 {
		return false
	}
	methods, err := readSized(reader)
	if err != nil || !strings.ContainsRune(string(methods), 2) {
		conn.Write([]byte{5, 255})
		return false
	}
	if _, err := conn.Write([]byte{5, 2}); err != nil {
		return false
	}
	version, err = reader.ReadByte()
	if err != nil || version != 1 {
		conn.Write([]byte{1, 1})
		return false
	}
	user, ue := readSized(reader)
	if ue != nil {
		conn.Write([]byte{1, 1})
		return false
	}
	pass, pe := readSized(reader)
	if pe != nil || !auth.matches(user, pass) {
		conn.Write([]byte{1, 1})
		return false
	}
	_, err = conn.Write([]byte{1, 0})
	return err == nil
}

func readSOCKSTarget(reader *bufio.Reader) (destination, byte) {
	var header [4]byte
	if _, err := io.ReadFull(reader, header[:]); err != nil || header[0] != 5 || header[2] != 0 {
		return destination{}, 1
	}
	if header[1] != 1 {
		return destination{}, 7
	}
	var host string
	switch header[3] {
	case 1:
		var addr [4]byte
		if _, err := io.ReadFull(reader, addr[:]); err != nil {
			return destination{}, 1
		}
		host = netip.AddrFrom4(addr).String()
	case 3:
		name, err := readSized(reader)
		if err != nil {
			return destination{}, 1
		}
		host = string(name)
	case 4:
		var addr [16]byte
		if _, err := io.ReadFull(reader, addr[:]); err != nil {
			return destination{}, 1
		}
		host = netip.AddrFrom16(addr).Unmap().String()
	default:
		return destination{}, 8
	}
	var port [2]byte
	if _, err := io.ReadFull(reader, port[:]); err != nil {
		return destination{}, 1
	}
	target, ok := parseDestination(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:]))))
	if !ok {
		return destination{}, 8
	}
	return target, 0
}

func socksReply(conn net.Conn, status byte) bool {
	_, err := conn.Write([]byte{5, status, 0, 1, 0, 0, 0, 0, 0, 0})
	return err == nil
}

func (s *proxyService) handleSOCKS(conn net.Conn, reader *bufio.Reader, auth credentials) {
	if !authenticateSOCKS(reader, conn, auth) {
		return
	}
	target, status := readSOCKSTarget(reader)
	if status != 0 {
		socksReply(conn, status)
		return
	}
	conn.SetDeadline(time.Time{})
	s.openTunnel(conn, reader, target, func(ok bool) bool {
		if !ok {
			socksReply(conn, 4)
			return false
		}
		return socksReply(conn, 0)
	})
}
