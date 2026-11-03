package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
)

// stdin/stdout JSON 行协议。父进程（Infinity Workbench）每行写一个 request，本进程每行回一个 response 或推送一个 event。
// 协议内容不含 auth key 之外的秘密；auth key 与代理口令只经 stdin 进入本进程，任何输出都不回显。

// maxLineBytes 限制单行请求大小，防止异常输入耗尽内存。
const maxLineBytes = 64 * 1024

// Request 是父进程发来的一条命令。
type Request struct {
	ID    int64         `json:"id"`
	Op    string        `json:"op"`
	Start *StartOptions `json:"start,omitempty"`
}

// StartOptions 是 start 命令的参数；只能发送一次。
type StartOptions struct {
	StateDir      string `json:"stateDir"`
	Hostname      string `json:"hostname"`
	ControlURL    string `json:"controlUrl,omitempty"`
	AuthKey       string `json:"authKey,omitempty"`
	Ephemeral     bool   `json:"ephemeral,omitempty"`
	ProxyUser     string `json:"proxyUser"`
	ProxyPassword string `json:"proxyPassword"`
}

// 命令名。
const (
	OpStart    = "start"
	OpStatus   = "status"
	OpPeers    = "peers"
	OpLogin    = "login"
	OpLogout   = "logout"
	OpShutdown = "shutdown"
)

// 错误码；父进程按错误码而不是消息文本处理。
const (
	ErrBadRequest  = "bad_request"
	ErrNotStarted  = "not_started"
	ErrStarted     = "already_started"
	ErrUnsupported = "unsupported_op"
	ErrStartFailed = "start_failed"
	ErrNotRunning  = "tailnet_not_running"
	ErrInternal    = "internal"
)

// Response 是对某个 request 的应答。
type Response struct {
	ID      int64  `json:"id"`
	OK      bool   `json:"ok"`
	Result  any    `json:"result,omitempty"`
	Error   string `json:"error,omitempty"`
	Message string `json:"message,omitempty"`
}

// Event 是本进程主动推送的状态变化。
type Event struct {
	Event string         `json:"event"`
	State *StatusSummary `json:"state,omitempty"`
}

// StartResult 是 start 的应答：本机代理端口（只绑定 127.0.0.1）。
type StartResult struct {
	ProxyPort int `json:"proxyPort"`
}

// StatusSummary 是本机节点状态（不含节点列表）。
type StatusSummary struct {
	BackendState   string   `json:"backendState"`
	AuthURL        string   `json:"authUrl,omitempty"`
	SelfName       string   `json:"selfName,omitempty"`
	SelfDNSName    string   `json:"selfDnsName,omitempty"`
	SelfIPs        []string `json:"selfIps,omitempty"`
	Online         bool     `json:"online"`
	MagicDNSSuffix string   `json:"magicDnsSuffix,omitempty"`
	TailnetName    string   `json:"tailnetName,omitempty"`
	Health         []string `json:"health,omitempty"`
	Error          string   `json:"error,omitempty"`
}

// PeerInfo 是节点列表中的一项。
type PeerInfo struct {
	Name    string   `json:"name"`
	DNSName string   `json:"dnsName,omitempty"`
	IPs     []string `json:"ips"`
	OS      string   `json:"os,omitempty"`
	Online  bool     `json:"online"`
}

// PeersResult 是 peers 的应答。
type PeersResult struct {
	Peers []PeerInfo `json:"peers"`
}

// errLineTooLong 表示单行超过 maxLineBytes。
var errLineTooLong = errors.New("request line too long")

// RequestReader 逐行解码请求。
type RequestReader struct {
	r *bufio.Reader
}

func NewRequestReader(r io.Reader) *RequestReader {
	return &RequestReader{r: bufio.NewReaderSize(r, 4096)}
}

// Next 读取下一条请求；输入结束时返回 io.EOF。超长行与无法解析的行返回错误，调用方可继续读取下一行。
func (rr *RequestReader) Next() (*Request, error) {
	var line []byte
	for {
		chunk, isPrefix, err := rr.r.ReadLine()
		if err != nil {
			return nil, err
		}
		if len(line)+len(chunk) > maxLineBytes {
			// 丢弃本行剩余部分。
			for isPrefix {
				if _, isPrefix, err = rr.r.ReadLine(); err != nil {
					return nil, err
				}
			}
			return nil, errLineTooLong
		}
		line = append(line, chunk...)
		if !isPrefix {
			break
		}
	}
	if len(line) == 0 {
		return rr.Next()
	}
	var req Request
	if err := json.Unmarshal(line, &req); err != nil {
		return nil, fmt.Errorf("decode request: %w", err)
	}
	return &req, nil
}

// Writer 串行写出 JSON 行，可在多个 goroutine 中使用。
type Writer struct {
	mu  sync.Mutex
	enc *json.Encoder
}

func NewWriter(w io.Writer) *Writer {
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	return &Writer{enc: enc}
}

func (w *Writer) write(v any) {
	w.mu.Lock()
	defer w.mu.Unlock()
	// 父进程已退出时写入失败；stdin EOF 会随后结束本进程，这里无需处理。
	_ = w.enc.Encode(v)
}

func (w *Writer) Reply(id int64, result any) {
	w.write(Response{ID: id, OK: true, Result: result})
}

func (w *Writer) Fail(id int64, code, message string) {
	w.write(Response{ID: id, OK: false, Error: code, Message: message})
}

func (w *Writer) State(state StatusSummary) {
	w.write(Event{Event: "state", State: &state})
}
