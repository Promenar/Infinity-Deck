'use strict';

/**
 * Infinity Deck Metro 调试 TUI
 *
 * 解析 adb logcat（或 --stdin）中的 INFINITY_METRO 事件流，
 * 渲染为开发者可读的彩色/纯文本输出。
 *
 * 设计约束：
 *   - 零运行时 npm 依赖，仅使用 Node 内置模块。
 *   - --help / --version 不启动 adb。
 *   - 默认模式启动 adb logcat；adb 缺失、无设备、子进程异常退出时
 *     给出明确中文错误与非零退出码。
 *   --stdin 模式从标准输入读取事件流。
 *   - 缺字段不得崩溃；非 TTY 不输出 ANSI/emoji。
 *   - 兼容 macOS/Linux/Windows 的 Node 18+。
 *
 * 可测试性：解析与渲染均为纯函数，运行模式接受可注入依赖，
 * 入口逻辑以 `require.main === module` 守卫，require 时不产生副作用。
 */

const { spawn } = require('child_process');
const { EventEmitter } = require('events');

const VERSION = '2.1.0-logcat-tui';
const DEFAULT_TAG = 'INFINITY_METRO';

const HELP_TEXT = `Infinity Deck Metro 调试 TUI — 实时解析 adb logcat 中的 INFINITY_METRO 事件流

用法:
  node infinity-metro-tui.js [选项]

选项:
  --help                显示本帮助信息并退出（不会启动 adb）
  --version             显示版本号并退出（不会启动 adb）
  --stdin               从标准输入读取事件流，而非启动 adb logcat
  --serial <id>         指定目标设备序列号（透传给 adb -s）
  --tag <tag>           指定 logcat 标签过滤器（默认 ${DEFAULT_TAG}）
  --no-color            强制关闭 ANSI 颜色与 emoji（非 TTY 时默认关闭）

说明:
  默认模式执行：adb [-s <serial>] logcat -s <tag>
  --stdin 模式按行读取标准输入，每行应为一条原始 logcat 文本。

退出码:
  0    正常退出（--help / --version / stdin 结束 / adb 正常退出）
  2    参数校验失败
  126  adb 已运行但失败（无设备 / 异常退出 / 启动错误）
  127  未找到 adb 可执行文件（ENOENT）
  130  收到 SIGINT 主动收尾
`;

class ArgError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ArgError';
  }
}

/**
 * 解析命令行参数。
 * @param {string[]} argv process.argv.slice(2)
 * @param {{isTTY?: boolean}} [env] 可注入的运行环境
 */
function parseArgs(argv, env) {
  if (!Array.isArray(argv)) {
    throw new ArgError('参数列表必须为数组');
  }
  const isTTY =
    env && typeof env.isTTY === 'boolean'
      ? env.isTTY
      : process.stdout.isTTY === true;

  let serial;
  let tag = DEFAULT_TAG;
  let stdin = false;
  let noColor = false;
  let help = false;
  let version = false;

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h') {
      help = true;
    } else if (a === '--version' || a === '-V') {
      version = true;
    } else if (a === '--stdin') {
      stdin = true;
    } else if (a === '--no-color') {
      noColor = true;
    } else if (a === '--serial') {
      if (i + 1 >= argv.length) {
        throw new ArgError('--serial 需要一个参数（设备序列号）');
      }
      const value = argv[++i];
      if (!value) {
        throw new ArgError('--serial 的参数不能为空');
      }
      serial = value;
    } else if (a === '--tag') {
      if (i + 1 >= argv.length) {
        throw new ArgError('--tag 需要一个参数（logcat 标签）');
      }
      const value = argv[++i];
      if (!value) {
        throw new ArgError('--tag 的参数不能为空');
      }
      tag = value;
    } else if (a.startsWith('--serial=')) {
      const value = a.slice('--serial='.length);
      if (!value) {
        throw new ArgError('--serial 的参数不能为空');
      }
      serial = value;
    } else if (a.startsWith('--tag=')) {
      const value = a.slice('--tag='.length);
      if (!value) {
        throw new ArgError('--tag 的参数不能为空');
      }
      tag = value;
    } else {
      throw new ArgError(`未知参数：${a}（使用 --help 查看用法）`);
    }
  }

  let action;
  if (help) action = 'help';
  else if (version) action = 'version';
  else if (stdin) action = 'stdin';
  else action = 'default';

  const useColor = !noColor && isTTY;

  return { action, serial, tag, stdin: !!stdin, useColor, isTTY };
}

/**
 * 把任意值格式化为安全字符串。null/undefined → '?'，对象 → JSON。
 */
function fmt(value) {
  if (value === null || value === undefined) return '?';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function boolStr(value) {
  if (value === true) return 'true';
  if (value === false) return 'false';
  if (value === null || value === undefined) return '?';
  return String(value);
}

function safeSubstring(value, max) {
  if (value === null || value === undefined) return '';
  return String(value).substring(0, max);
}

/**
 * 从原始 logcat 行中解析出 { event, data, time }。
 * 无法识别时返回 null。任何缺字段都不会抛错。
 */
function parseLogcatLine(line) {
  if (typeof line !== 'string' || line.length === 0) return null;

  const startIdx = line.indexOf('EVENT_START|');
  if (startIdx === -1) return null;
  const endIdx = line.indexOf('|EVENT_END', startIdx);
  if (endIdx === -1 || endIdx <= startIdx) return null;

  const payloadStr = line.substring(
    startIdx + 'EVENT_START|'.length,
    endIdx
  );
  const parts = payloadStr.split('|');
  if (parts.length < 1) return null;

  const event = parts[0] || '';
  const rawJson = parts.slice(1).join('|');

  let data = {};
  if (rawJson.length > 0) {
    try {
      data = JSON.parse(rawJson);
      if (data === null || typeof data !== 'object' || Array.isArray(data)) {
        data = { message: rawJson };
      }
    } catch (_e) {
      data = { message: rawJson };
    }
  }

  let time = new Date().toLocaleTimeString();
  const timeMatch = line.match(/^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}(?:\.\d{3})?)/);
  if (timeMatch) {
    time = timeMatch[1];
  }

  return { event, data, time };
}

/**
 * 渲染单条事件为多行字符串。
 * @returns {{lines: string[], inline: boolean}}
 */
function renderEvent(event, data, time, useColor) {
  const lines = [];
  let inline = false;
  const d = data && typeof data === 'object' ? data : {};

  const c = (code, text) => (useColor ? `\x1b[${code}m${text}\x1b[0m` : text);
  const E = (emoji, label) => (useColor ? `${emoji} ` : '') + label;

  switch (event) {
    case 'LOG': {
      lines.push(
        `${c('90', `[${time}] ${E('', '[LOG] ──>')} ${fmt(d.message)}`)}`
      );
      break;
    }

    case 'CONTEXT_ASSEMBLY': {
      lines.push(
        c('35', `[${time}] ${E('🧠', '[CONTEXT ASSEMBLY] ──>')} ${E('⏳', 'Sliding Window Applied')}`)
      );
      lines.push(
        `   ├─ model: ${c('1;36', fmt(d.model))} | temp: ${c('33', d.temperature === undefined ? 'default' : fmt(d.temperature))}`
      );
      lines.push(
        `   ├─ RAG Memory: ${c('32', boolStr(d.enableMemorySearch))} | KG: ${c('32', boolStr(d.enableKnowledgeSearch))} | Search: ${c('32', boolStr(d.enableWebSearch))}`
      );
      if (d.messageCount !== undefined) {
        lines.push(`   ├─ messages_count: ${c('36', fmt(d.messageCount))}`);
      }
      if (d.inputChars !== undefined) {
        lines.push(`   ├─ input_chars: ${c('36', fmt(d.inputChars))}`);
      }
      if (d.system && d.system !== 'none') {
        lines.push(
          `   ├─ system_prompt: ${c('90', `"${safeSubstring(d.system, 120)}..."`)}`
        );
      }
      if (Array.isArray(d.messages) && d.messages.length > 0) {
        const lastMsg = d.messages[d.messages.length - 1];
        lines.push(`   ├─ messages_count: ${c('36', String(d.messages.length))}`);
        lines.push(
          `   └─ ${c('1;34', 'USER')}: ${c('1;37', `"${safeSubstring(lastMsg && lastMsg.content, 200)}"`)}`
        );
      } else {
        lines.push(`   └─ ready`);
      }
      break;
    }

    case 'HTTP_REQUEST': {
      lines.push(
        c('33', `[${time}] ${E('🌐', '[HTTP REQUEST] ──>')} ${E('📤', '')}${fmt(d.method)} ${fmt(d.url)}`)
      );
      break;
    }

    case 'HTTP_STREAM_CHUNK': {
      lines.push(
        c(
          '36',
          `   ${E('✍️ ', '[LLM GENERATING] ──>')} ${E('⏳', 'Stream Chunks')} (Bytes: ${fmt(d.totalBytes)} | Tokens: ${fmt(d.totalTokens)} | Speed: ${c('1;32', `${fmt(d.cps)} CPS`)})`
        )
      );
      inline = true;
      break;
    }

    case 'HTTP_RESPONSE': {
      lines.push(
        c('32', `[${time}] ${E('🌐', '[HTTP RESPONSE] ──>')} ${E('🟢', `Code: ${fmt(d.code)}`)}`)
      );
      if (d.elapsedMs !== undefined) {
        lines.push(`   ├─ elapsed: ${fmt(d.elapsedMs)} ms`);
      }
      if (d.bytes !== undefined) {
        lines.push(`   ├─ bytes: ${fmt(d.bytes)}`);
      }
      if (d.response) {
        lines.push(
          `   └─ response: ${c('90', `"${safeSubstring(d.response, 200)}..."`)}`
        );
      }
      break;
    }

    case 'LLM_COMPLETE': {
      lines.push(
        c('32', `[${time}] ${E('🏁', '[GEN COMPLETED] ──>')} ${E('🟢', 'Flow Terminated Safely')}`)
      );
      lines.push(
        `   └─ model: ${c('36', fmt(d.model))} | status: ${c('32', fmt(d.status))}`
      );
      break;
    }

    case 'ERROR': {
      const tag = d.tag || d.component || '未知';
      const reason = d.error || d.message || d.reason || '无信息';
      lines.push(
        c('1;31', `[${time}] ${E('🚨', '[ERROR DETECTED IN PIPELINE] ─────────────────────────────')}`)
      );
      lines.push(
        `${c('1;31', ' ├─ Component:')}  ${c('1;37', fmt(tag))}`
      );
      lines.push(
        `${c('1;31', ' ├─ Reason:')}     ${c('1;33', fmt(reason))}`
      );
      if (d.stacktrace) {
        lines.push(c('1;31', ' ├─ StackTrace Traceback (Top 6 lines): '));
        const stLines = String(d.stacktrace)
          .split('\n')
          .filter((l) => l.trim().length > 0)
          .slice(0, 6);
        for (const l of stLines) {
          lines.push(c('90', `     ${l.trim()}`));
        }
      }
      lines.push(
        c('1;31', ' └───────────────────────────────────────────────────────────────────')
      );
      break;
    }

    case 'RAG': {
      const msg = d.message || '';
      if (msg.includes('Extracting chunk')) {
        const chunkMatch = msg.match(/Extracting chunk (\d+)\/(\d+)/);
        if (chunkMatch) {
          lines.push(c('36', `[${time}] ${E('🔬', '[KG EXTRACT] ──>')} Chunk ${chunkMatch[1]}/${chunkMatch[2]}`));
        } else {
          lines.push(c('36', `[${time}] ${E('🔬', '[KG EXTRACT] ──>')} ${fmt(msg)}`));
        }
      } else if (msg.includes('returned error')) {
        lines.push(c('33', `[${time}] ${E('⚠️ ', '[KG EXTRACT] ──>')} ${E('❌', fmt(msg))}`));
      } else if (msg.includes('Chunk summary')) {
        lines.push(c('1;36', `[${time}] ${E('📊', '[KG EXTRACT] ──>')} ${fmt(msg)}`));
      } else if (msg.includes('TIMEOUT')) {
        lines.push(c('1;31', `[${time}] ${E('⏱️ ', '[KG TIMEOUT] ──>')} ${fmt(msg)}`));
      } else if (msg.includes('EMPTY')) {
        lines.push(c('33', `[${time}] ${E('📭', '[KG EMPTY] ──>')} ${fmt(msg)}`));
      } else if (msg.includes('LLM response received')) {
        lines.push(c('32', `[${time}] ${E('📨', '[KG LLM] ──>')} ${safeSubstring(msg, 120)}`));
      } else if (msg.includes('Parse result')) {
        lines.push(c('33', `[${time}] ${E('🔧', '[KG PARSE] ──>')} ${safeSubstring(msg, 150)}`));
      } else {
        lines.push(c('90', `[${time}] ${E('', '[LOG] ──>')} ${fmt(msg)}`));
      }
      break;
    }

    case 'DB_QUERY': {
      const sql = d.sql || d.operation || '?';
      let argPart = '';
      if (d.bindArgs !== undefined) {
        argPart = ` | args: ${JSON.stringify(d.bindArgs)}`;
      } else if (d.argumentCount !== undefined) {
        argPart = ` | args: ${fmt(d.argumentCount)}`;
      }
      lines.push(
        c('90', `[${time}] ${E('🗄️ ', '[ROOM SQL] ──>')} ${safeSubstring(sql, 100)}${argPart}`)
      );
      break;
    }

    default: {
      lines.push(
        c('36', `[${time}] ${E('⚙️ ', `[${event || 'UNKNOWN'}] ──>`)} ${fmt(d.message)}`)
      );
      break;
    }
  }

  return { lines, inline };
}

/**
 * 解析并渲染一条原始 logcat 行。
 * @returns {{lines: string[], inline: boolean}}
 */
function formatLine(line, opts) {
  const useColor = opts && opts.useColor !== false;
  const parsed = parseLogcatLine(line);
  if (!parsed) return { lines: [], inline: false };
  return renderEvent(parsed.event, parsed.data, parsed.time, useColor);
}

function banner(opts) {
  const useColor = opts && opts.useColor;
  const mode = opts && opts.action === 'stdin' ? '标准输入解析' : 'ADB Logcat 流式解析';
  const target = opts && opts.serial ? `serial=${opts.serial}` : '自动设备';
  const tag = (opts && opts.tag) || DEFAULT_TAG;
  if (useColor) {
    return (
      '\x1b[36m\n' +
      '┌────────────────────────────────────────────────────────────────────────┐\n' +
      `│  N E X A R A   M E T R O   D E B U G G E R   [v${VERSION}]              │\n` +
      `│  模式：${mode}  |  目标：${target}  |  标签：${tag}                       │\n` +
      '└────────────────────────────────────────────────────────────────────────┘\n' +
      '\x1b[0m\n'
    );
  }
  return `\nInfinity Metro Debugger v${VERSION} | 模式：${mode} | 目标：${target} | 标签：${tag}\n`;
}

/**
 * 创建带状态的小型写入器，处理 inline（\r）与非 inline（\n）的衔接。
 */
function makeWriter(stdout) {
  let prevInline = false;
  return {
    write(lines, inline) {
      if (!lines || lines.length === 0) return;
      if (prevInline && !inline) {
        stdout.write('\n');
      }
      for (let i = 0; i < lines.length; i++) {
        const last = i === lines.length - 1;
        if (inline && last) {
          stdout.write('\r' + lines[i]);
        } else {
          stdout.write(lines[i] + '\n');
        }
      }
      prevInline = inline && lines.length > 0;
    },
    flush() {
      if (prevInline) {
        stdout.write('\n');
        prevInline = false;
      }
    },
  };
}

function writeRaw(stdout, text) {
  stdout.write(text);
}

/**
 * 默认模式：启动 adb logcat。
 * @param {{serial?: string, tag?: string, useColor?: boolean, isTTY?: boolean, action?: string}} opts
 * @param {{spawnFn?: Function, stdout?: object, stderr?: object, _registerChild?: Function}} [deps]
 * @returns {Promise<number>} 退出码
 */
function runDefaultMode(opts, deps) {
  const spawnFn = (deps && deps.spawnFn) || spawn;
  const stdout = (deps && deps.stdout) || process.stdout;
  const stderr = (deps && deps.stderr) || process.stderr;
  const useColor = opts && opts.useColor;

  return new Promise((resolve) => {
    if (useColor) {
      writeRaw(stdout, '\x1b[2J\x1b[H');
    }
    writeRaw(stdout, banner(Object.assign({}, opts, { action: 'default' })));

    const adbArgs = [];
    if (opts && opts.serial) {
      adbArgs.push('-s', opts.serial);
    }
    adbArgs.push('logcat', '-s', (opts && opts.tag) || DEFAULT_TAG);

    let child;
    try {
      child = spawnFn('adb', adbArgs, {
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (e) {
      stderr.write(`adb 子进程启动失败：${e && e.message}\n`);
      resolve(126);
      return;
    }

    if (deps && typeof deps._registerChild === 'function') {
      deps._registerChild(child);
    }

    let stderrBuf = '';
    let logBuffer = '';
    let resolved = false;
    const writer = makeWriter(stdout);

    const finish = (code) => {
      if (resolved) return;
      resolved = true;
      writer.flush();
      resolve(code);
    };

    child.on('error', (err) => {
      if (err && err.code === 'ENOENT') {
        stderr.write(
          '未找到 adb 可执行文件，请确认 Android Platform Tools 已安装并在 PATH 中。\n'
        );
        stderr.write(
          "提示：运行 'adb devices' 检查安装；macOS 可执行 'brew install --cask android-platform-tools'。\n"
        );
        finish(127);
      } else {
        stderr.write(`adb 子进程启动失败：${err && err.message}\n`);
        finish(126);
      }
    });

    if (child.stderr) {
      child.stderr.on('data', (chunk) => {
        stderrBuf += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk);
        if (
          /no devices|waiting for device|device ([\w.:_-]+)?\s*not found|device offline|failed to start daemon/i.test(
            stderrBuf
          )
        ) {
          stderr.write(
            '未检测到已连接的 Android 设备/模拟器，adb logcat 无法继续。\n'
          );
          stderr.write(`adb 输出：${stderrBuf.trim()}\n`);
          stderr.write(
            "提示：运行 'adb devices' 确认设备在线；必要时使用 --serial <id> 指定设备。\n"
          );
          try {
            child.kill('SIGKILL');
          } catch (_e) {
            // 忽略
          }
          finish(126);
        }
      });
    }

    if (child.stdout) {
      child.stdout.on('data', (chunk) => {
        logBuffer += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk);
        const lines = logBuffer.split('\n');
        logBuffer = lines.pop();
        for (const l of lines) {
          const out = formatLine(l, { useColor });
          writer.write(out.lines, out.inline && useColor);
        }
      });
    }

    child.on('exit', (code, signal) => {
      if (logBuffer.trim().length > 0) {
        const out = formatLine(logBuffer, { useColor });
        writer.write(out.lines, out.inline && useColor);
        logBuffer = '';
      }
      if (resolved) return;
      if (signal) {
        stderr.write(`adb 子进程被信号 ${signal} 终止。\n`);
        finish(126);
      } else if (code === 0) {
        finish(0);
      } else {
        stderr.write(`adb logcat 异常退出（退出码 ${code}）。\n`);
        if (stderrBuf.trim()) {
          stderr.write(`adb 输出：${stderrBuf.trim()}\n`);
        }
        finish(126);
      }
    });
  });
}

/**
 * stdin 模式：从可读流按行读取事件。
 * @param {{useColor?: boolean, action?: string}} opts
 * @param {{input?: object, stdout?: object, stderr?: object}} [deps]
 * @returns {Promise<number>}
 */
function runStdinMode(opts, deps) {
  const input = (deps && deps.input) || process.stdin;
  const stdout = (deps && deps.stdout) || process.stdout;
  const stderr = (deps && deps.stderr) || process.stderr;
  const useColor = opts && opts.useColor;

  return new Promise((resolve) => {
    writeRaw(stdout, banner(Object.assign({}, opts, { action: 'stdin' })));

    if (typeof input.setEncoding === 'function') {
      try {
        input.setEncoding('utf8');
      } catch (_e) {
        // 忽略
      }
    }

    const writer = makeWriter(stdout);
    let logBuffer = '';
    let resolved = false;
    const finish = (code) => {
      if (resolved) return;
      resolved = true;
      writer.flush();
      resolve(code);
    };

    const onData = (chunk) => {
      logBuffer += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk);
      const lines = logBuffer.split('\n');
      logBuffer = lines.pop();
      for (const l of lines) {
        const out = formatLine(l, { useColor });
        writer.write(out.lines, out.inline && useColor);
      }
    };
    const onEnd = () => {
      if (logBuffer.trim().length > 0) {
        const out = formatLine(logBuffer, { useColor });
        writer.write(out.lines, out.inline && useColor);
        logBuffer = '';
      }
      finish(0);
    };
    const onError = (err) => {
      stderr.write(`读取标准输入失败：${err && err.message}\n`);
      finish(126);
    };

    input.on('data', onData);
    input.on('end', onEnd);
    input.on('error', onError);
    if (typeof input.resume === 'function') {
      input.resume();
    }
  });
}

/**
 * 分发动作。help/version 不会启动 adb。
 */
async function dispatch(action, opts, deps) {
  const stdout = (deps && deps.stdout) || process.stdout;
  if (action === 'help') {
    writeRaw(stdout, HELP_TEXT);
    return 0;
  }
  if (action === 'version') {
    writeRaw(stdout, `${VERSION}\n`);
    return 0;
  }
  if (action === 'stdin') {
    return runStdinMode(opts, deps);
  }
  return runDefaultMode(opts, deps);
}

function main() {
  let parsed;
  try {
    parsed = parseArgs(process.argv.slice(2));
  } catch (e) {
    if (e instanceof ArgError) {
      process.stderr.write(`参数错误：${e.message}\n\n`);
      process.stderr.write(HELP_TEXT);
      process.exitCode = 2;
      return;
    }
    throw e;
  }

  let child = null;
  let shuttingDown = false;
  const cleanup = () => {
    if (shuttingDown) {
      process.exit(130);
    }
    shuttingDown = true;
    if (child) {
      try {
        child.kill();
      } catch (_e) {
        // 忽略
      }
    }
    process.stderr.write('\n已收到中断信号，正在安全收尾…再见。\n');
    process.exit(130);
  };
  process.on('SIGINT', cleanup);
  process.on('SIGTERM', cleanup);

  const deps = {
    spawnFn: spawn,
    stdout: process.stdout,
    stderr: process.stderr,
    input: process.stdin,
    _registerChild: (c) => {
      child = c;
    },
  };

  dispatch(parsed.action, parsed, deps)
    .then((code) => {
      process.exitCode = code;
    })
    .catch((err) => {
      process.stderr.write(
        `发生未预期错误：${(err && err.stack) || err}\n`
      );
      process.exitCode = 1;
    });
}

if (require.main === module) {
  main();
}

module.exports = {
  VERSION,
  HELP_TEXT,
  DEFAULT_TAG,
  ArgError,
  parseArgs,
  parseLogcatLine,
  renderEvent,
  formatLine,
  banner,
  runDefaultMode,
  runStdinMode,
  dispatch,
  EventEmitter,
};
