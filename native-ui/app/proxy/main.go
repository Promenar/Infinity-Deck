package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/signal"
	"sync"
	"syscall"

	"github.com/sirupsen/logrus"
)

const maxCommandBytes = 4 << 20

var errLineSize = errors.New("控制请求超出大小上限")

type command struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

type controlError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type response struct {
	ID     json.RawMessage `json:"id"`
	Result any             `json:"result,omitempty"`
	Error  *controlError   `json:"error,omitempty"`
}

type stateEvent struct {
	Event string     `json:"event"`
	State proxyState `json:"state"`
}

type validationResult struct {
	Valid       bool              `json:"valid"`
	Error       string            `json:"error"`
	Nodes       []nodeInfo        `json:"nodes"`
	NodeConfigs []json.RawMessage `json:"nodeConfigs,omitempty"`
}

type importResult struct {
	Nodes       []nodeInfo        `json:"nodes"`
	YAML        string            `json:"yaml"`
	NodeConfigs []json.RawMessage `json:"nodeConfigs"`
}

type okResult struct {
	OK bool `json:"ok"`
}

func init() {
	logrus.SetOutput(io.Discard)
}

func readCommandLine(r *bufio.Reader) ([]byte, error) {
	var line []byte
	oversize := false
	for {
		part, err := r.ReadSlice('\n')
		if !oversize && len(line)+len(part) <= maxCommandBytes {
			line = append(line, part...)
		} else {
			line = nil
			oversize = true
		}
		if err == bufio.ErrBufferFull {
			continue
		}
		if oversize {
			return nil, errLineSize
		}
		if len(line) > 0 && err == io.EOF {
			err = nil
		}
		return bytes.TrimSpace(line), err
	}
}

func validID(id json.RawMessage) bool {
	if len(id) == 0 {
		return false
	}
	first := id[0]
	return first == '"' || first == '-' || first >= '0' && first <= '9'
}

func decodeParams(raw json.RawMessage, into any) error {
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return nil
	}
	return json.Unmarshal(raw, into)
}

func controlFailure(id json.RawMessage, code int, message string) response {
	return response{ID: id, Error: &controlError{Code: code, Message: message}}
}

func runControl(input io.Reader, output io.Writer) {
	outgoing := make(chan any, 128)
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		encoder := json.NewEncoder(output)
		encoder.SetEscapeHTML(false)
		for item := range outgoing {
			if encoder.Encode(item) != nil {
				return
			}
		}
	}()
	send := func(item any) {
		select {
		case outgoing <- item:
		case <-writerDone:
		}
	}
	s := newProxyService(func(state proxyState) {
		// 消费端发生背压时，入口连接仍可转发。status 可取得最新完整状态。
		select {
		case outgoing <- stateEvent{Event: "state", State: state}:
		default:
		}
	})
	var probes sync.WaitGroup
	probeRequests := make(chan struct{}, 16)
	defer func() {
		s.stop()
		probes.Wait()
		close(outgoing)
		<-writerDone
	}()
	reader := bufio.NewReaderSize(input, 32<<10)
	for {
		line, err := readCommandLine(reader)
		if errors.Is(err, errLineSize) {
			send(controlFailure(nil, -32600, errLineSize.Error()))
			continue
		}
		if err != nil {
			return
		}
		if len(line) == 0 {
			send(controlFailure(nil, -32700, "控制请求的 JSON 格式无效"))
			continue
		}
		var req command
		switch {
		case !json.Valid(line):
			send(controlFailure(nil, -32700, "控制请求的 JSON 格式无效"))
			continue
		case json.Unmarshal(line, &req) != nil || req.Method == "" || !validID(req.ID):
			if !validID(req.ID) {
				req.ID = nil
			}
			send(controlFailure(req.ID, -32600, "控制请求缺少有效编号或方法"))
			continue
		}
		if req.Method == "shutdown" {
			s.stop()
			probes.Wait()
			send(response{ID: req.ID, Result: okResult{OK: true}})
			return
		}
		if req.Method == "test_latency" {
			var p latencyParams
			if decodeParams(req.Params, &p) != nil {
				send(controlFailure(req.ID, -32602, "参数格式无效"))
				continue
			}
			select {
			case probeRequests <- struct{}{}:
			default:
				send(controlFailure(req.ID, -32000, "测速请求过多"))
				continue
			}
			batch, err := s.prepareLatency(p)
			if err != nil {
				<-probeRequests
				send(controlFailure(req.ID, -32000, err.Error()))
				continue
			}
			probes.Go(func() {
				defer func() { <-probeRequests }()
				send(response{ID: req.ID, Result: s.runLatency(batch)})
			})
			continue
		}
		send(s.dispatch(req))
	}
}

// 规范化的节点配置仅由显式导入和校验返回，供父进程按节点加密保存。
func wireNodeConfigs(source string) ([]json.RawMessage, error) {
	proxies, err := yamlProxies(source)
	if err != nil {
		return nil, err
	}
	result := make([]json.RawMessage, 0, len(proxies))
	for _, p := range proxies {
		node, err := parseNode(p)
		if err != nil {
			return nil, err
		}
		config, err := json.Marshal(node.options)
		if err != nil {
			return nil, errors.New("节点配置包含不支持的字段类型")
		}
		result = append(result, config)
	}
	return result, nil
}

func (s *proxyService) dispatch(req command) response {
	reply := response{ID: req.ID}
	invalid := controlFailure(req.ID, -32602, "参数格式无效")
	var err error
	switch req.Method {
	case "start", "reload":
		var p configParams
		if decodeParams(req.Params, &p) != nil {
			return invalid
		}
		if req.Method == "start" {
			reply.Result, err = s.start(p)
		} else {
			reply.Result, err = s.reload(p)
		}
	case "set_dns":
		var p struct {
			DNSServers []string `json:"dnsServers"`
		}
		if decodeParams(req.Params, &p) != nil {
			return invalid
		}
		err = s.setDNS(p.DNSServers)
		reply.Result = okResult{OK: true}
	case "status":
		reply.Result = s.status()
	case "validate_yaml":
		var p struct {
			YAML string `json:"yaml"`
		}
		if decodeParams(req.Params, &p) != nil {
			return invalid
		}
		nodes, validationErr := compileNodes(p.YAML)
		result := validationResult{Valid: validationErr == nil, Nodes: make([]nodeInfo, 0, len(nodes))}
		if validationErr != nil {
			result.Error = validationErr.Error()
		} else {
			defer closeNodes(nodes)
			result.NodeConfigs, validationErr = wireNodeConfigs(p.YAML)
			if validationErr != nil {
				reply.Result = validationResult{Error: validationErr.Error(), Nodes: []nodeInfo{}}
				return reply
			}
			for _, node := range nodes {
				result.Nodes = append(result.Nodes, node.nodeInfo)
			}
		}
		reply.Result = result
	case "parse_import":
		var p struct {
			Content string `json:"content"`
		}
		if decodeParams(req.Params, &p) != nil {
			return invalid
		}
		var result importResult
		result.Nodes, result.YAML, err = parseImport(p.Content)
		if err == nil {
			result.NodeConfigs, err = wireNodeConfigs(result.YAML)
		}
		reply.Result = result
	default:
		return controlFailure(req.ID, -32601, "未知控制方法")
	}
	if err != nil {
		return controlFailure(req.ID, -32000, err.Error())
	}
	return reply
}

func main() {
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(signals)
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-signals:
			os.Stdin.Close()
		case <-done:
		}
	}()
	runControl(os.Stdin, os.Stdout)
}
