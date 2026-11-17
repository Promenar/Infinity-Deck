// 双语专题共用路由，首页、文档页与 sitemap 使用同一清单。
export const guides = {
  'android-ai-coding': {
    zh: {
      title: '在手机上用 AI 管理 Git 仓库与编写代码',
      intro: 'Infinity Deck 内置了免 Root 的 Alpine Linux 与完整 Git 工具链。不在电脑旁时，你可以直接用手机拉取代码仓库、运行测试脚本，并通过多分支工作树（Worktree）让智能体并行处理不同任务。',
      steps: [
        '安装应用并配置常用的模型服务（或启用本地模型），在会话设置中将执行环境切换为内置 Linux。',
        '克隆 HTTPS 仓库并绑定到当前会话工作区；如果是私有仓库，可在「钥匙串 › Git」中保存个人访问令牌，或通过内置浏览器完成 GitHub 登录授权。',
        '直接用自然语言说明要修改的功能或需要排查的 Bug。如果手头还有其它延伸任务，可以点击派生「分支会话」，在独立的 Git 工作树中同时推进，避免代码冲突。',
        '打开右侧的 Git 检视器查看文件修改差异（Diff）、分支状态与测试运行结果，确认无误后再提交或推送到远端。',
      ],
      note: '内置 Linux 与 Git 命令均在手机本机直接运行；拉取远端代码仓库和调用云端大模型时需保持网络连接。开启默认命令审批后，每一条修改或执行命令都会先由你确认。',
      alt: 'Infinity Deck 会话中的 Git 分支方案与工作树',
    },
    en: {
      title: 'Manage Git repos and code with AI right on your phone',
      intro: 'Infinity Deck includes rootless Alpine Linux and a full Git toolchain on Android. Away from your desk, pull repositories, run test scripts, and spin up isolated branch chats backed by dedicated Git worktrees.',
      steps: [
        'Install the app, add a cloud model provider (or enable local models), and switch the session execution environment to Built-in Linux.',
        'Clone an HTTPS repository and bind it to your session workspace. For private repos, save a token under Keychain › Git or sign in via the built-in browser.',
        'Describe the change or bug you want investigated. Fork side tasks into a Branch Chat so main and branch sessions work in separate worktrees without conflicts.',
        'Inspect modified files, branches, and test results in the right-hand Git panel before committing or pushing.',
      ],
      note: 'Built-in Linux and Git commands execute locally on the phone. Fetching remote repositories and calling cloud models requires network access. With default command approval enabled, every change awaits your tap.',
      alt: 'Git branch plan and worktrees inside an Infinity Deck session',
    },
    image: 'inner-chat.state.branch-plan.webp',
    source: 'workspaces.md',
  },
  'android-ssh-ops': {
    zh: {
      title: '出门在外用手机 SSH 安全排查服务器故障',
      intro: '收到线上告警而身边没有电脑时，Infinity Deck 可以直接从手机发起 SSH 连接。配合内置的 Tailscale 私有网络与逐条命令审批机制，既能快速查清故障原因，又能避免误操作风险。',
      steps: [
        '在应用中添加目标 SSH 主机并核对指纹，从本机钥匙串选择登录密码或 SSH 私钥。如果服务器位于内网，可先一键开启应用内置的 Tailscale 节点。',
        '将告警现象或异常接口告诉智能体，让它先登录服务器检查服务状态、配置文件和近期日志。',
        '在命令审批模式下，智能体拟运行的每条检查命令都会先呈现在卡片上，你可以看清具体命令与参数后再点击「批准当前」。',
        '定位到根因后，批准修复命令并让智能体验证接口恢复情况，最后自动整理出一份包含故障原因、处理步骤与验证结果的排障记录。',
      ],
      note: 'SSH 命令在远程服务器上按当前登录账户权限执行。所有主机密码与私钥均由手机本机钥匙串加密保管，不会发送给大模型。',
      alt: 'Infinity Deck 外屏显示的 SSH 排障报告',
    },
    en: {
      title: 'Inspect and fix remote servers safely over SSH from Android',
      intro: 'When an alert triggers while you are away from a computer, Infinity Deck lets you connect directly over SSH. Paired with the embedded Tailscale node and per-command approval cards, you diagnose issues quickly without risky blind execution.',
      steps: [
        'Add your SSH host and verify its host key fingerprint using a password or SSH key stored in your device keychain. Turn on the built-in Tailscale node first for private tailnet hosts.',
        'Describe the incident symptoms or failing endpoints and have the agent start with read-only health and log checks.',
        'In approval mode, every proposed remote command appears in a review card with full parameters before running on tap.',
        'Once the root cause is clear, approve the fix, verify service recovery, and let the agent write a clean incident report.',
      ],
      note: 'SSH commands run on the remote server under your signed-in account permissions. Host passwords and private keys stay encrypted in your phone keychain and never enter the AI prompt.',
      alt: 'An SSH incident summary displayed on the Infinity Deck cover screen',
    },
    image: 'outer-chat.state.ops-report.webp',
    source: 'execution-environments.md',
  },
};

export const guidePaths = Object.keys(guides).map(slug => `/guides/${slug}/`);
