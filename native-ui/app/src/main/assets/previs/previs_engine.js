import * as THREE from './three.module.min.js';
import { OrbitControls } from './OrbitControls.js';

const SENSOR_HEIGHT_MM = 24.0;
const DEG2RAD = Math.PI / 180.0;
const STAGE_BG_CLAY = 0x121721;
const STAGE_BG_DEPTH = 0x000000;

const bridge = () => window.PrevisBridge || null;

function notifyReady() {
  try {
    bridge()?.onReady?.();
  } catch (_) {}
}

function notifyError(message) {
  try {
    bridge()?.onError?.(String(message || 'Previs WebGL error'));
  } catch (_) {}
}

function notifyTick(state) {
  try {
    bridge()?.onPlaybackTick?.(
      state.shotIndex,
      state.localTime,
      computeGlobalTime(state.shotIndex, state.localTime),
      state.playing,
    );
  } catch (_) {}
}

function notifyComplete() {
  try {
    bridge()?.onPlaybackComplete?.();
  } catch (_) {}
}

const canvas = document.getElementById('stage');
let renderer = null;
let scene = null;
let shotCamera = null;
let orbitCamera = null;
let controls = null;
let stageGroup = null;
let worldGroup = null;
let helperGroup = null;
let gridHelper = null;
let shadowGround = null;
let depthMaterial = null;

const runtime = {
  data: null,
  shotStartTimes: [],
  totalDuration: 0,
  props: [],
  actors: new Map(),
  frustums: [],
  pathLines: [],
  axisGroup: null,
  axisSafeMesh: null,
  axisWarnMesh: null,
  axisLine: null,
  shotIndex: 0,
  localTime: 0,
  playing: false,
  viewMode: 'camera', // 'camera' | 'orbit'
  shaderMode: 'clay', // 'clay' | 'depth'
  showAxis: true,
  exporting: false,
  exportShader: 'clay',
  orbitDirty: false,
  lastCssW: 0,
  lastCssH: 0,
  lastDpr: 0,
  lastFrameMs: 0,
  lastTickSentMs: 0,
  pendingJson: null,
  ready: false,
};

function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

function applyEasing(u, easing) {
  const t = clamp(u, 0, 1);
  switch (easing) {
    case 'linear':
      return t;
    case 'easeIn':
      return t * t;
    case 'easeOut':
      return 1 - (1 - t) * (1 - t);
    case 'easeInOut':
    default:
      return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
  }
}

function vec3(arr, fallback = [0, 0, 0]) {
  const a = Array.isArray(arr) && arr.length >= 3 ? arr : fallback;
  return new THREE.Vector3(Number(a[0]) || 0, Number(a[1]) || 0, Number(a[2]) || 0);
}

function computeGlobalTime(shotIndex, localTime) {
  if (!runtime.shotStartTimes.length) return 0;
  const idx = clamp(shotIndex, 0, runtime.shotStartTimes.length - 1);
  const start = runtime.shotStartTimes[idx] || 0;
  const dur = runtime.data?.shots?.[idx]?.duration || 0;
  return start + clamp(localTime, 0, dur);
}

function initEngine() {
  if (!canvas) {
    notifyError('Canvas element not found');
    return;
  }
  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: true,
      alpha: false,
      preserveDrawingBuffer: true,
      powerPreference: 'high-performance',
    });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;

    scene = new THREE.Scene();
    scene.background = new THREE.Color(STAGE_BG_CLAY);

    shotCamera = new THREE.PerspectiveCamera(38, 16 / 9, 0.1, 150);
    orbitCamera = new THREE.PerspectiveCamera(45, 16 / 9, 0.1, 200);
    orbitCamera.position.set(6, 5.5, 8);

    controls = new OrbitControls(orbitCamera, canvas);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.minDistance = 1.5;
    controls.maxDistance = 45;
    controls.maxPolarAngle = Math.PI * 0.49;
    controls.enabled = false;
    controls.addEventListener('start', () => {
      runtime.orbitDirty = true;
    });

    canvas.addEventListener('webglcontextlost', (e) => {
      e.preventDefault();
      notifyError('WebGL context lost');
    });

    canvas.addEventListener('dblclick', () => {
      if (runtime.viewMode === 'orbit') {
        resetOrbitCamera();
      }
    });

    // 双指/双击兜底（部分移动端 WebView 不派发 dblclick）
    let lastTapMs = 0;
    let maxTouches = 0;
    canvas.addEventListener('touchstart', (e) => {
      maxTouches = Math.max(maxTouches, e.touches.length);
    }, { passive: true });
    canvas.addEventListener('touchend', (e) => {
      if (e.touches.length === 0) {
        if (maxTouches === 1 && runtime.viewMode === 'orbit') {
          const now = performance.now();
          if (now - lastTapMs < 280) {
            resetOrbitCamera();
            lastTapMs = 0;
          } else {
            lastTapMs = now;
          }
        }
        maxTouches = 0;
      }
    }, { passive: true });

    // 三点工作室白模灯光
    const hemi = new THREE.HemisphereLight(0xdce6f5, 0x262e3d, 0.85);
    scene.add(hemi);

    const keyLight = new THREE.DirectionalLight(0xfff7ed, 1.35);
    keyLight.position.set(8, 14, 10);
    keyLight.castShadow = true;
    keyLight.shadow.mapSize.set(1024, 1024);
    keyLight.shadow.camera.near = 1;
    keyLight.shadow.camera.far = 45;
    keyLight.shadow.camera.left = -16;
    keyLight.shadow.camera.right = 16;
    keyLight.shadow.camera.top = 16;
    keyLight.shadow.camera.bottom = -16;
    keyLight.shadow.bias = -0.0005;
    scene.add(keyLight);

    const rimLight = new THREE.DirectionalLight(0x93c5fd, 0.55);
    rimLight.position.set(-10, 7, -8);
    scene.add(rimLight);

    stageGroup = new THREE.Group();
    scene.add(stageGroup);

    gridHelper = new THREE.GridHelper(40, 40, 0x3b4861, 0x222b3a);
    gridHelper.position.y = 0.001;
    stageGroup.add(gridHelper);

    const groundMat = new THREE.ShadowMaterial({ opacity: 0.34 });
    shadowGround = new THREE.Mesh(new THREE.PlaneGeometry(80, 80), groundMat);
    shadowGround.rotation.x = -Math.PI / 2;
    shadowGround.receiveShadow = true;
    stageGroup.add(shadowGround);

    worldGroup = new THREE.Group();
    scene.add(worldGroup);

    helperGroup = new THREE.Group();
    scene.add(helperGroup);

    // 线性相机深度着色器：近=亮白(1.0)，远=深灰/黑(0.0)，兼容 ControlNet Depth
    depthMaterial = new THREE.ShaderMaterial({
      toneMapped: false,
      uniforms: {
        uNear: { value: 0.5 },
        uFar: { value: 24.0 },
      },
      vertexShader: `
        varying float vViewDepth;
        void main() {
          vec4 mvPos = modelViewMatrix * vec4(position, 1.0);
          vViewDepth = -mvPos.z;
          gl_Position = projectionMatrix * mvPos;
        }
      `,
      fragmentShader: `
        uniform float uNear;
        uniform float uFar;
        varying float vViewDepth;
        void main() {
          float span = max(0.001, uFar - uNear);
          float linear = clamp((vViewDepth - uNear) / span, 0.0, 1.0);
          float gray = 1.0 - linear;
          gl_FragColor = vec4(vec3(gray), 1.0);
        }
      `,
    });

    resizeToContainer();
    window.addEventListener('resize', resizeToContainer);
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(() => resizeToContainer());
      ro.observe(canvas);
      if (document.documentElement) ro.observe(document.documentElement);
    }

    runtime.ready = true;
    if (runtime.pendingJson) {
      const pending = runtime.pendingJson;
      runtime.pendingJson = null;
      applySceneData(pending);
    }
    requestAnimationFrame(animationLoop);
    notifyReady();
  } catch (err) {
    notifyError(err?.message || String(err));
  }
}

function syncCanvasSizeIfNeeded() {
  if (!renderer || runtime.exporting) return;
  const width = Math.max(0, Math.round(canvas.clientWidth || window.innerWidth || 0));
  const height = Math.max(0, Math.round(canvas.clientHeight || window.innerHeight || 0));
  if (width < 2 || height < 2) return;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  if (width === runtime.lastCssW && height === runtime.lastCssH && dpr === runtime.lastDpr) {
    return;
  }
  runtime.lastCssW = width;
  runtime.lastCssH = height;
  runtime.lastDpr = dpr;
  renderer.setPixelRatio(dpr);
  renderer.setSize(width, height, false);
  const aspect = width / height;
  if (shotCamera) {
    shotCamera.aspect = aspect;
    shotCamera.updateProjectionMatrix();
  }
  if (orbitCamera) {
    orbitCamera.aspect = aspect;
    orbitCamera.updateProjectionMatrix();
  }
}

function resizeToContainer() {
  runtime.lastCssW = 0;
  runtime.lastCssH = 0;
  syncCanvasSizeIfNeeded();
}

function createRampGeometry(w, h, d) {
  const hw = w * 0.5;
  const hd = d * 0.5;
  // 楔形斜坡：底面 (-hd .. +hd), +Z 端高为 h, -Z 端高为 0
  const vertices = new Float32Array([
    // 底面 (2 triangles)
    -hw, 0, -hd,  hw, 0, -hd,  hw, 0,  hd,
    -hw, 0, -hd,  hw, 0,  hd, -hw, 0,  hd,
    // 斜坡顶面
    -hw, 0, -hd, -hw, h,  hd,  hw, h,  hd,
    -hw, 0, -hd,  hw, h,  hd,  hw, 0, -hd,
    // +Z 背面
    -hw, 0,  hd,  hw, 0,  hd,  hw, h,  hd,
    -hw, 0,  hd,  hw, h,  hd, -hw, h,  hd,
    // -X 左侧三角
    -hw, 0, -hd, -hw, 0,  hd, -hw, h,  hd,
    // +X 右侧三角
     hw, 0, -hd,  hw, h,  hd,  hw, 0,  hd,
  ]);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(vertices, 3));
  geo.computeVertexNormals();
  return geo;
}

function buildPropMesh(spec) {
  const w = Math.max(0.05, Number(spec.size?.[0]) || 1);
  const h = Math.max(0.02, Number(spec.size?.[1]) || 1);
  const d = Math.max(0.05, Number(spec.size?.[2]) || 1);
  const pos = vec3(spec.pos);
  const color = spec.color ? new THREE.Color(spec.color) : new THREE.Color(0x94a3b8);
  const mat = new THREE.MeshStandardMaterial({
    color,
    roughness: 0.78,
    metalness: 0.05,
  });

  let geo;
  let yOffset = h * 0.5;
  switch (spec.shape) {
    case 'cylinder':
      geo = new THREE.CylinderGeometry(w * 0.5, w * 0.5, h, 24);
      break;
    case 'sphere':
      geo = new THREE.SphereGeometry(0.5, 24, 16);
      break;
    case 'plane':
      geo = new THREE.BoxGeometry(w, Math.max(0.02, Math.min(h, 0.08)), d);
      yOffset = Math.max(0.01, Math.min(h, 0.08) * 0.5);
      break;
    case 'ramp':
      geo = createRampGeometry(w, h, d);
      yOffset = 0;
      break;
    case 'box':
    default:
      geo = new THREE.BoxGeometry(w, h, d);
      break;
  }

  const mesh = new THREE.Mesh(geo, mat);
  if (spec.shape === 'sphere') {
    mesh.scale.set(w, h, d);
  }
  mesh.position.set(pos.x, pos.y + yOffset, pos.z);
  if (Array.isArray(spec.rot) && spec.rot.length >= 3) {
    mesh.rotation.set(
      (Number(spec.rot[0]) || 0) * DEG2RAD,
      (Number(spec.rot[1]) || 0) * DEG2RAD,
      (Number(spec.rot[2]) || 0) * DEG2RAD,
    );
  } else {
    mesh.rotation.y = (Number(spec.rotY) || 0) * DEG2RAD;
  }
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  return mesh;
}

function buildActorRig(spec) {
  const height = clamp(Number(spec.height) || 1.7, 0.4, 3.5);
  const scale = height / 1.7;
  const root = new THREE.Group();

  const primaryColor = spec.color ? new THREE.Color(spec.color) : new THREE.Color(0xd6dfed);
  const accentColor = spec.accent ? new THREE.Color(spec.accent) : new THREE.Color(0x60a5fa);

  const bodyMat = new THREE.MeshStandardMaterial({ color: primaryColor, roughness: 0.72, metalness: 0.04 });
  const jointMat = new THREE.MeshStandardMaterial({ color: 0x64748b, roughness: 0.8, metalness: 0.05 });
  const visorMat = new THREE.MeshStandardMaterial({ color: accentColor, roughness: 0.45, metalness: 0.1 });

  // 脚底接地椭圆投影盘（helper 逻辑外也帮助辨认接地位置）
  const baseDisk = new THREE.Mesh(
    new THREE.CylinderGeometry(0.24 * scale, 0.24 * scale, 0.012, 20),
    new THREE.MeshBasicMaterial({ color: accentColor, transparent: true, opacity: 0.35 }),
  );
  baseDisk.position.y = 0.006;
  root.add(baseDisk);

  // 脚底 +Z 朝向小三角指示锥
  const dirArrow = new THREE.Mesh(
    new THREE.ConeGeometry(0.065 * scale, 0.16 * scale, 8),
    new THREE.MeshBasicMaterial({ color: accentColor }),
  );
  dirArrow.rotation.x = Math.PI / 2;
  dirArrow.position.set(0, 0.025, 0.33 * scale);
  root.add(dirArrow);

  const pelvis = new THREE.Group();
  pelvis.position.y = 0.88 * scale;
  root.add(pelvis);

  const pelvisMesh = new THREE.Mesh(new THREE.BoxGeometry(0.28 * scale, 0.14 * scale, 0.17 * scale), jointMat);
  pelvisMesh.castShadow = true;
  pelvis.add(pelvisMesh);

  // 腿
  function createLeg(xOffset) {
    const hip = new THREE.Group();
    hip.position.set(xOffset * scale, -0.05 * scale, 0);
    const upper = new THREE.Mesh(new THREE.CapsuleGeometry(0.065 * scale, 0.28 * scale, 4, 10), bodyMat);
    upper.position.y = -0.21 * scale;
    upper.castShadow = true;
    hip.add(upper);

    const knee = new THREE.Group();
    knee.position.set(0, -0.41 * scale, 0);
    const lower = new THREE.Mesh(new THREE.CapsuleGeometry(0.052 * scale, 0.28 * scale, 4, 10), jointMat);
    lower.position.y = -0.20 * scale;
    lower.castShadow = true;
    knee.add(lower);

    const foot = new THREE.Mesh(new THREE.BoxGeometry(0.095 * scale, 0.06 * scale, 0.19 * scale), bodyMat);
    foot.position.set(0, -0.39 * scale, 0.04 * scale);
    foot.castShadow = true;
    knee.add(foot);

    hip.add(knee);
    return { hip, knee };
  }

  const legL = createLeg(-0.10);
  const legR = createLeg(0.10);
  pelvis.add(legL.hip, legR.hip);

  // 躯干与头部
  const torso = new THREE.Group();
  torso.position.y = 0.07 * scale;
  pelvis.add(torso);

  const chestMesh = new THREE.Mesh(new THREE.BoxGeometry(0.35 * scale, 0.44 * scale, 0.20 * scale), bodyMat);
  chestMesh.position.y = 0.23 * scale;
  chestMesh.castShadow = true;
  chestMesh.receiveShadow = true;
  torso.add(chestMesh);

  const neckHead = new THREE.Group();
  neckHead.position.y = 0.49 * scale;
  torso.add(neckHead);

  const headMesh = new THREE.Mesh(new THREE.SphereGeometry(0.125 * scale, 18, 14), bodyMat);
  headMesh.position.y = 0.13 * scale;
  headMesh.castShadow = true;
  neckHead.add(headMesh);

  // 面部护目/朝向条（清晰标识人物面向）
  const visor = new THREE.Mesh(new THREE.BoxGeometry(0.16 * scale, 0.045 * scale, 0.06 * scale), visorMat);
  visor.position.set(0, 0.14 * scale, 0.105 * scale);
  neckHead.add(visor);

  function createArm(xOffset) {
    const shoulder = new THREE.Group();
    shoulder.position.set(xOffset * scale, 0.40 * scale, 0);
    const upper = new THREE.Mesh(new THREE.CapsuleGeometry(0.048 * scale, 0.21 * scale, 4, 8), bodyMat);
    upper.position.y = -0.14 * scale;
    upper.castShadow = true;
    shoulder.add(upper);

    const elbow = new THREE.Group();
    elbow.position.set(0, -0.27 * scale, 0);
    const lower = new THREE.Mesh(new THREE.CapsuleGeometry(0.040 * scale, 0.20 * scale, 4, 8), jointMat);
    lower.position.y = -0.13 * scale;
    lower.castShadow = true;
    elbow.add(lower);

    shoulder.add(elbow);
    return { shoulder, elbow };
  }

  const armL = createArm(-0.23);
  const armR = createArm(0.23);
  torso.add(armL.shoulder, armR.shoulder);

  return {
    id: spec.id,
    scale,
    root,
    pelvis,
    torso,
    neckHead,
    legL,
    legR,
    armL,
    armR,
    helpers: [baseDisk, dirArrow],
  };
}

function applyActorPose(rig, pose, u, moveDist, timeSec) {
  const s = rig.scale;
  // 重置关节默认值
  rig.pelvis.position.set(0, 0.88 * s, 0);
  rig.torso.rotation.set(0, 0, 0);
  rig.neckHead.rotation.set(0, 0, 0);
  rig.legL.hip.rotation.set(0, 0, 0);
  rig.legL.knee.rotation.set(0, 0, 0);
  rig.legR.hip.rotation.set(0, 0, 0);
  rig.legR.knee.rotation.set(0, 0, 0);
  rig.armL.shoulder.rotation.set(0, 0, 0.1);
  rig.armL.elbow.rotation.set(-0.1, 0, 0);
  rig.armR.shoulder.rotation.set(0, 0, -0.1);
  rig.armR.elbow.rotation.set(-0.1, 0, 0);

  switch (pose) {
    case 'walk': {
      const cycles = Math.max(1.5, moveDist * 1.4);
      const phase = u * cycles * Math.PI * 2;
      const swing = 0.48 * Math.sin(phase);
      rig.legL.hip.rotation.x = swing;
      rig.legR.hip.rotation.x = -swing;
      rig.legL.knee.rotation.x = Math.max(0, -swing * 0.75);
      rig.legR.knee.rotation.x = Math.max(0, swing * 0.75);
      rig.armL.shoulder.rotation.x = -swing * 0.7;
      rig.armR.shoulder.rotation.x = swing * 0.7;
      rig.armL.elbow.rotation.x = -0.35;
      rig.armR.elbow.rotation.x = -0.35;
      rig.pelvis.position.y = (0.88 + Math.abs(Math.sin(phase)) * 0.03) * s;
      rig.torso.rotation.x = 0.06;
      break;
    }
    case 'sit': {
      rig.pelvis.position.y = 0.48 * s;
      rig.legL.hip.rotation.x = -1.45;
      rig.legR.hip.rotation.x = -1.45;
      rig.legL.knee.rotation.x = 1.42;
      rig.legR.knee.rotation.x = 1.42;
      rig.torso.rotation.x = 0.06;
      rig.armL.shoulder.rotation.x = -0.45;
      rig.armR.shoulder.rotation.x = -0.45;
      rig.armL.elbow.rotation.x = -0.85;
      rig.armR.elbow.rotation.x = -0.85;
      break;
    }
    case 'wave': {
      const w = Math.sin(timeSec * 6.5) * 0.35;
      rig.armR.shoulder.rotation.set(-0.2, 0, 2.35);
      rig.armR.elbow.rotation.set(0, 0, 0.55 + w);
      rig.neckHead.rotation.y = 0.08 * Math.sin(timeSec * 2.0);
      break;
    }
    case 'talk': {
      const g = Math.sin(timeSec * 4.2);
      rig.armR.shoulder.rotation.set(-0.55 - 0.15 * g, 0, -0.2);
      rig.armR.elbow.rotation.set(-0.85 - 0.25 * g, 0, 0);
      rig.armL.shoulder.rotation.set(-0.38 + 0.1 * g, 0, 0.18);
      rig.armL.elbow.rotation.set(-0.65, 0, 0);
      rig.neckHead.rotation.x = 0.06 * Math.sin(timeSec * 2.7);
      break;
    }
    case 'point': {
      rig.armR.shoulder.rotation.set(-1.35, -0.15, -0.1);
      rig.armR.elbow.rotation.set(-0.15, 0, 0);
      rig.torso.rotation.x = 0.05;
      break;
    }
    case 'lean': {
      rig.torso.rotation.x = 0.18;
      rig.armL.shoulder.rotation.x = -0.25;
      rig.armR.shoulder.rotation.x = -0.25;
      break;
    }
    case 'stand':
    default: {
      const breath = Math.sin(timeSec * 1.8) * 0.025;
      rig.torso.rotation.x = breath;
      break;
    }
  }
}

function disposeGroup(group) {
  while (group.children.length > 0) {
    const child = group.children[0];
    group.remove(child);
    child.traverse((obj) => {
      obj.geometry?.dispose?.();
      if (Array.isArray(obj.material)) {
        obj.material.forEach((m) => m?.dispose?.());
      } else {
        obj.material?.dispose?.();
      }
    });
  }
}

function applySceneData(jsonInput) {
  if (!runtime.ready) {
    runtime.pendingJson = jsonInput;
    return { ok: true, deferred: true };
  }
  const parsed = typeof jsonInput === 'string' ? JSON.parse(jsonInput) : jsonInput;
  runtime.data = parsed;
  runtime.shotStartTimes = [];
  let acc = 0;
  for (const s of parsed.shots || []) {
    runtime.shotStartTimes.push(acc);
    acc += Math.max(0.05, Number(s.duration) || 1);
  }
  runtime.totalDuration = acc;

  const bgHex = parsed.skyColor ? new THREE.Color(parsed.skyColor).getHex() : STAGE_BG_CLAY;
  runtime.clayBg = bgHex;

  disposeGroup(worldGroup);
  disposeGroup(helperGroup);
  runtime.props = [];
  runtime.actors.clear();
  runtime.frustums = [];
  runtime.pathLines = [];
  runtime.axisGroup = null;

  for (const p of parsed.props || []) {
    const mesh = buildPropMesh(p);
    worldGroup.add(mesh);
    runtime.props.push(mesh);
  }

  for (const a of parsed.actors || []) {
    const rig = buildActorRig(a);
    worldGroup.add(rig.root);
    runtime.actors.set(a.id, rig);
  }

  // 生成全部镜头的视锥框 & 角色路径指示（自由视角时可见）
  (parsed.shots || []).forEach((s, idx) => {
    const camSpec = s.camera;
    const from = vec3(camSpec.from);
    const look = vec3(camSpec.lookAtFrom);
    const frustumGroup = buildFrustumHelper(from, look, camSpec, idx);
    helperGroup.add(frustumGroup);
    runtime.frustums.push(frustumGroup);

    (s.actors || []).forEach((ak) => {
      if (ak.to) {
        const aFrom = vec3(ak.from);
        const aTo = vec3(ak.to);
        if (aFrom.distanceTo(aTo) > 0.08) {
          const pts = [
            new THREE.Vector3(aFrom.x, aFrom.y + 0.04, aFrom.z),
            new THREE.Vector3(aTo.x, aTo.y + 0.04, aTo.z),
          ];
          const geo = new THREE.BufferGeometry().setFromPoints(pts);
          const mat = new THREE.LineDashedMaterial({
            color: 0x60a5fa,
            dashSize: 0.25,
            gapSize: 0.18,
            transparent: true,
            opacity: 0.75,
          });
          const line = new THREE.Line(geo, mat);
          line.computeLineDistances();
          line.userData.shotIndex = idx;
          helperGroup.add(line);
          runtime.pathLines.push(line);
        }
      }
    });
  });

  // 构建可复用的 180° 轴线安全区组
  runtime.axisGroup = buildAxisGuideGroup();
  helperGroup.add(runtime.axisGroup);

  runtime.shotIndex = clamp(runtime.shotIndex, 0, Math.max(0, (parsed.shots?.length || 1) - 1));
  runtime.localTime = 0;
  runtime.orbitDirty = false;
  syncCanvasSizeIfNeeded();
  evaluateFrame(runtime.shotIndex, runtime.localTime, runtime.shaderMode, runtime.viewMode);
  renderCurrentView();
  return {
    ok: true,
    shotCount: parsed.shots?.length || 0,
    totalDuration: runtime.totalDuration,
  };
}

function buildFrustumHelper(from, look, camSpec, idx) {
  const g = new THREE.Group();
  const fov = camSpec.focalLengthMm
    ? (2 * Math.atan(SENSOR_HEIGHT_MM / (2 * Number(camSpec.focalLengthMm)))) / DEG2RAD
    : Number(camSpec.fov) || 40;
  const dist = clamp(from.distanceTo(look) * 0.36, 0.8, 2.6);
  const halfH = Math.tan((fov * DEG2RAD) / 2) * dist;
  const halfW = halfH * 1.7778;

  const verts = new Float32Array([
    0, 0, 0, -halfW,  halfH, -dist,
    0, 0, 0,  halfW,  halfH, -dist,
    0, 0, 0,  halfW, -halfH, -dist,
    0, 0, 0, -halfW, -halfH, -dist,
    -halfW,  halfH, -dist,  halfW,  halfH, -dist,
     halfW,  halfH, -dist,  halfW, -halfH, -dist,
     halfW, -halfH, -dist, -halfW, -halfH, -dist,
    -halfW, -halfH, -dist, -halfW,  halfH, -dist,
  ]);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(verts, 3));
  const mat = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.32 });
  const lines = new THREE.LineSegments(geo, mat);
  g.add(lines);

  const dot = new THREE.Mesh(
    new THREE.SphereGeometry(0.11, 12, 8),
    new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.75 }),
  );
  g.add(dot);

  g.position.copy(from);
  g.lookAt(look);
  // Three.js lookAt 让 +Z 指向目标或 -Z 对相机? Camera 的 +Z 背向目标，Object3D.lookAt 让 +Z 指向目标！
  // 所以上面视锥顶点沿 -dist 会朝背面；绕 Y 转 180° 使视锥 -Z 改为面向目标！
  lines.rotation.y = Math.PI;
  g.userData = { shotIndex: idx, linesMat: mat, dotMat: dot.material };
  return g;
}

function buildAxisGuideGroup() {
  const g = new THREE.Group();
  const safeGeo = new THREE.CircleGeometry(3.2, 32, 0, Math.PI);
  const safeMat = new THREE.MeshBasicMaterial({
    color: 0x4ade80,
    transparent: true,
    opacity: 0.14,
    side: THREE.DoubleSide,
    depthWrite: false,
  });
  const safeMesh = new THREE.Mesh(safeGeo, safeMat);
  safeMesh.rotation.x = -Math.PI / 2;

  const warnGeo = new THREE.CircleGeometry(3.2, 32, Math.PI, Math.PI);
  const warnMat = new THREE.MeshBasicMaterial({
    color: 0xf87171,
    transparent: true,
    opacity: 0.11,
    side: THREE.DoubleSide,
    depthWrite: false,
  });
  const warnMesh = new THREE.Mesh(warnGeo, warnMat);
  warnMesh.rotation.x = -Math.PI / 2;

  const lineGeo = new THREE.BufferGeometry().setFromPoints([
    new THREE.Vector3(-4.2, 0, 0),
    new THREE.Vector3(4.2, 0, 0),
  ]);
  const lineMat = new THREE.LineDashedMaterial({
    color: 0x4ade80,
    dashSize: 0.3,
    gapSize: 0.2,
    transparent: true,
    opacity: 0.85,
  });
  const axisLine = new THREE.Line(lineGeo, lineMat);
  axisLine.computeLineDistances();

  g.add(safeMesh, warnMesh, axisLine);
  g.visible = false;
  return g;
}

function updateAxisGuide(shot, worldActorsMap, camPos) {
  if (!runtime.axisGroup || !runtime.showAxis || runtime.viewMode !== 'orbit' || runtime.shaderMode !== 'clay') {
    if (runtime.axisGroup) runtime.axisGroup.visible = false;
    return;
  }
  const actorIds = shot.axis
    ? [shot.axis.actorA, shot.axis.actorB]
    : Array.from(worldActorsMap.keys()).slice(0, 2);
  if (actorIds.length < 2) {
    runtime.axisGroup.visible = false;
    return;
  }
  const posA = worldActorsMap.get(actorIds[0]);
  const posB = worldActorsMap.get(actorIds[1]);
  if (!posA || !posB) {
    runtime.axisGroup.visible = false;
    return;
  }
  const dx = posB.x - posA.x;
  const dz = posB.z - posA.z;
  const dist = Math.hypot(dx, dz);
  if (dist < 0.2) {
    runtime.axisGroup.visible = false;
    return;
  }
  const mx = (posA.x + posB.x) * 0.5;
  const mz = (posA.z + posB.z) * 0.5;
  const radius = clamp(dist * 1.15, 2.2, 5.5);
  const scaleFactor = radius / 3.2;

  runtime.axisGroup.position.set(mx, 0.018, mz);
  const angle = Math.atan2(dz, dx);
  // CircleGeometry 在 XY 平面绕 X=-90° 后映射到 XZ，+X 轴角度确定安全半圆侧别
  const sideSign = shot.axis?.side === -1 ? Math.PI : 0;
  runtime.axisGroup.rotation.y = -angle + sideSign;
  runtime.axisGroup.scale.set(scaleFactor, 1, scaleFactor);
  runtime.axisGroup.visible = true;
}

function evaluateFrame(shotIndex, localTime, shaderMode, viewMode) {
  const shots = runtime.data?.shots || [];
  if (!shots.length) return;
  const idx = clamp(shotIndex, 0, shots.length - 1);
  const shot = shots[idx];
  const dur = Math.max(0.05, Number(shot.duration) || 1);
  const u = clamp(localTime / dur, 0, 1);

  // 更新全部演员（隐藏非当前镜出现的角色）
  const activeActorIds = new Set((shot.actors || []).map((a) => a.id));
  const actorWorldPos = new Map();
  runtime.actors.forEach((rig, actorId) => {
    const visible = activeActorIds.has(actorId);
    rig.root.visible = visible;
    rig.helpers.forEach((h) => { h.visible = shaderMode === 'clay'; });
  });

  // 第一遍算位置，第二遍算 lookAtActor 朝向
  for (const ak of shot.actors || []) {
    const rig = runtime.actors.get(ak.id);
    if (!rig) continue;
    const pFrom = vec3(ak.from);
    const pTo = ak.to ? vec3(ak.to) : pFrom;
    const pos = pFrom.clone().lerp(pTo, u);
    rig.root.position.copy(pos);
    actorWorldPos.set(ak.id, pos);
    applyActorPose(rig, ak.pose || 'stand', u, pFrom.distanceTo(pTo), localTime);
  }

  for (const ak of shot.actors || []) {
    const rig = runtime.actors.get(ak.id);
    if (!rig) continue;
    const pos = rig.root.position;
    let lookTarget = null;
    if (ak.lookAtActor && actorWorldPos.has(ak.lookAtActor)) {
      lookTarget = actorWorldPos.get(ak.lookAtActor);
    } else if (Array.isArray(ak.lookAt) && ak.lookAt.length >= 3) {
      lookTarget = vec3(ak.lookAt);
    }
    if (lookTarget) {
      const dx = lookTarget.x - pos.x;
      const dz = lookTarget.z - pos.z;
      if (Math.hypot(dx, dz) > 0.001) {
        rig.root.rotation.y = Math.atan2(dx, dz);
      }
    } else if (ak.facing !== undefined && ak.facing !== null) {
      rig.root.rotation.y = Number(ak.facing) * DEG2RAD;
    } else if (ak.to) {
      const pFrom = vec3(ak.from);
      const pTo = vec3(ak.to);
      if (pFrom.distanceTo(pTo) > 0.05) {
        rig.root.rotation.y = Math.atan2(pTo.x - pFrom.x, pTo.z - pFrom.z);
      }
    }
  }

  // 更新摄影机机位
  const camSpec = shot.camera;
  const cu = applyEasing(u, camSpec.easing || 'easeInOut');
  const camFrom = vec3(camSpec.from);
  const camTo = camSpec.to ? vec3(camSpec.to) : camFrom;
  const lookFrom = vec3(camSpec.lookAtFrom);
  const lookTo = camSpec.lookAtTo ? vec3(camSpec.lookAtTo) : lookFrom;

  const camPos = camFrom.clone().lerp(camTo, cu);
  const lookAt = lookFrom.clone().lerp(lookTo, cu);

  const fov = camSpec.focalLengthMm
    ? (2 * Math.atan(SENSOR_HEIGHT_MM / (2 * Number(camSpec.focalLengthMm)))) / DEG2RAD
    : clamp(Number(camSpec.fov) || 40, 15, 120);
  if (Math.abs(shotCamera.fov - fov) > 0.01) {
    shotCamera.fov = fov;
    shotCamera.updateProjectionMatrix();
  }
  shotCamera.position.copy(camPos);
  shotCamera.lookAt(lookAt);
  if (camSpec.roll) {
    shotCamera.rotateZ(Number(camSpec.roll) * DEG2RAD);
  }

  // 若 OrbitControls 尚未被手势移动，跟随当前镜头给出直观鸟瞰初值
  if (!runtime.orbitDirty && viewMode === 'orbit') {
    positionOrbitOverShot(camPos, lookAt);
  }

  // 切换 Clay vs Depth 渲染配置
  const isDepth = shaderMode === 'depth';
  scene.background.setHex(isDepth ? STAGE_BG_DEPTH : (runtime.clayBg ?? STAGE_BG_CLAY));
  gridHelper.visible = !isDepth;
  shadowGround.visible = !isDepth;
  helperGroup.visible = !isDepth && viewMode === 'orbit';

  if (isDepth) {
    const dr = Array.isArray(shot.depthRange) && shot.depthRange.length >= 2
      ? shot.depthRange
      : [0.5, 22.0];
    depthMaterial.uniforms.uNear.value = Math.max(0.05, Number(dr[0]) || 0.5);
    depthMaterial.uniforms.uFar.value = Math.max(depthMaterial.uniforms.uNear.value + 0.5, Number(dr[1]) || 22.0);
    scene.overrideMaterial = depthMaterial;
  } else {
    scene.overrideMaterial = null;
  }

  // 更新视锥和路径高亮
  runtime.frustums.forEach((f) => {
    const active = f.userData.shotIndex === idx;
    f.userData.linesMat.color.setHex(active ? 0x60a5fa : 0xffffff);
    f.userData.linesMat.opacity = active ? 0.95 : 0.25;
    f.userData.dotMat.color.setHex(active ? 0x60a5fa : 0xffffff);
    f.userData.dotMat.opacity = active ? 1.0 : 0.45;
  });
  runtime.pathLines.forEach((line) => {
    line.visible = line.userData.shotIndex === idx;
  });
  updateAxisGuide(shot, actorWorldPos, camPos);
}

function positionOrbitOverShot(camPos, lookAt) {
  controls.target.copy(lookAt);
  const offset = new THREE.Vector3().subVectors(camPos, lookAt);
  if (offset.length() < 1) offset.set(3, 2, 4);
  offset.y = Math.max(offset.y + 4.5, 4.5);
  offset.multiplyScalar(1.35);
  orbitCamera.position.copy(lookAt).add(offset);
  controls.update();
}

function resetOrbitCamera() {
  runtime.orbitDirty = false;
  const shots = runtime.data?.shots || [];
  const shot = shots[runtime.shotIndex];
  if (shot) {
    evaluateFrame(runtime.shotIndex, runtime.localTime, runtime.shaderMode, 'orbit');
  } else {
    orbitCamera.position.set(6, 5.5, 8);
    controls.target.set(0, 1, 0);
    controls.update();
  }
}

function renderCurrentView() {
  if (!renderer || !scene) return;
  const cam = runtime.viewMode === 'orbit' && !runtime.exporting ? orbitCamera : shotCamera;
  renderer.render(scene, cam);
}

function animationLoop(nowMs) {
  requestAnimationFrame(animationLoop);
  if (!runtime.ready || runtime.exporting) return;

  const dtSec = runtime.lastFrameMs > 0 ? clamp((nowMs - runtime.lastFrameMs) / 1000.0, 0, 0.1) : 0;
  runtime.lastFrameMs = nowMs;

  if (runtime.playing && runtime.data?.shots?.length) {
    const shots = runtime.data.shots;
    let idx = clamp(runtime.shotIndex, 0, shots.length - 1);
    let t = runtime.localTime + dtSec;
    while (idx < shots.length) {
      const dur = Math.max(0.05, Number(shots[idx].duration) || 1);
      if (t < dur) break;
      t -= dur;
      idx++;
    }
    if (idx >= shots.length) {
      runtime.playing = false;
      runtime.shotIndex = shots.length - 1;
      runtime.localTime = Math.max(0.05, Number(shots[runtime.shotIndex].duration) || 1);
      evaluateFrame(runtime.shotIndex, runtime.localTime, runtime.shaderMode, runtime.viewMode);
      notifyTick(runtime);
      notifyComplete();
    } else {
      runtime.shotIndex = idx;
      runtime.localTime = t;
      evaluateFrame(runtime.shotIndex, runtime.localTime, runtime.shaderMode, runtime.viewMode);
      if (nowMs - runtime.lastTickSentMs >= 60) {
        runtime.lastTickSentMs = nowMs;
        notifyTick(runtime);
      }
    }
  } else if (runtime.viewMode === 'orbit' && controls.enabled) {
    controls.update();
  }

  syncCanvasSizeIfNeeded();
  renderCurrentView();
}

function resolveGlobalTime(globalTimeSec) {
  const shots = runtime.data?.shots || [];
  if (!shots.length) return { shotIndex: 0, localTime: 0 };
  let remaining = Math.max(0, Number(globalTimeSec) || 0);
  for (let i = 0; i < shots.length; i++) {
    const dur = Math.max(0.05, Number(shots[i].duration) || 1);
    if (remaining <= dur || i === shots.length - 1) {
      return { shotIndex: i, localTime: Math.min(remaining, dur) };
    }
    remaining -= dur;
  }
  return { shotIndex: shots.length - 1, localTime: 0 };
}

window.__previs = {
  loadScene(jsonStr) {
    try {
      return JSON.stringify(applySceneData(jsonStr));
    } catch (err) {
      return JSON.stringify({ ok: false, error: err?.message || String(err) });
    }
  },
  setState(optsJson) {
    const opts = typeof optsJson === 'string' ? JSON.parse(optsJson) : (optsJson || {});
    if (typeof opts.shotIndex === 'number' && runtime.data?.shots?.length) {
      const nextShot = clamp(Math.floor(opts.shotIndex), 0, runtime.data.shots.length - 1);
      if (nextShot !== runtime.shotIndex) {
        runtime.orbitDirty = false;
      }
      runtime.shotIndex = nextShot;
    }
    if (typeof opts.localTime === 'number') {
      runtime.localTime = Math.max(0, opts.localTime);
    }
    if (typeof opts.playing === 'boolean') {
      runtime.playing = opts.playing;
      runtime.lastFrameMs = performance.now();
    }
    if (opts.viewMode === 'camera' || opts.viewMode === 'orbit') {
      if (opts.viewMode !== runtime.viewMode) {
        runtime.viewMode = opts.viewMode;
        runtime.orbitDirty = false;
        controls.enabled = opts.viewMode === 'orbit';
      }
    }
    if (opts.shaderMode === 'clay' || opts.shaderMode === 'depth') {
      runtime.shaderMode = opts.shaderMode;
    }
    if (typeof opts.showAxis === 'boolean') {
      runtime.showAxis = opts.showAxis;
    }
    syncCanvasSizeIfNeeded();
    evaluateFrame(runtime.shotIndex, runtime.localTime, runtime.shaderMode, runtime.viewMode);
    renderCurrentView();
    return true;
  },
  resetOrbit() {
    resetOrbitCamera();
    renderCurrentView();
    return true;
  },
  captureStill(shotIndex, localTime, shaderMode, width, height) {
    if (!renderer || !runtime.data?.shots?.length) return '';
    const prevShader = runtime.shaderMode;
    const prevView = runtime.viewMode;
    const idx = clamp(shotIndex ?? runtime.shotIndex, 0, runtime.data.shots.length - 1);
    const t = typeof localTime === 'number' ? localTime : runtime.localTime;
    const w = clamp(Math.round(Number(width) || 1280), 64, 1920);
    const h = clamp(Math.round(Number(height) || 720), 64, 1920);
    const mode = shaderMode === 'depth' ? 'depth' : 'clay';

    runtime.exporting = true;
    try {
      renderer.setPixelRatio(1);
      renderer.setSize(w, h, false);
      shotCamera.aspect = w / h;
      shotCamera.updateProjectionMatrix();
      evaluateFrame(idx, t, mode, 'camera');
      renderer.render(scene, shotCamera);
      return renderer.domElement.toDataURL('image/png');
    } finally {
      runtime.exporting = false;
      resizeToContainer();
      evaluateFrame(runtime.shotIndex, runtime.localTime, prevShader, prevView);
      renderCurrentView();
    }
  },
  beginExport(width, height, shaderMode) {
    if (!renderer || !runtime.data?.shots?.length) return false;
    const w = clamp(Math.round(Number(width) || 1280), 64, 1920);
    const h = clamp(Math.round(Number(height) || 720), 64, 1920);
    runtime.exporting = true;
    runtime.playing = false;
    runtime.exportShader = shaderMode === 'depth' ? 'depth' : 'clay';
    renderer.setPixelRatio(1);
    renderer.setSize(w, h, false);
    shotCamera.aspect = w / h;
    shotCamera.updateProjectionMatrix();
    return true;
  },
  renderExportFrame(globalTimeSec) {
    if (!renderer || !runtime.exporting || !runtime.data?.shots?.length) return '';
    const { shotIndex, localTime } = resolveGlobalTime(globalTimeSec);
    evaluateFrame(shotIndex, localTime, runtime.exportShader, 'camera');
    renderer.render(scene, shotCamera);
    return renderer.domElement.toDataURL('image/jpeg', 0.92);
  },
  endExport() {
    runtime.exporting = false;
    resizeToContainer();
    evaluateFrame(runtime.shotIndex, runtime.localTime, runtime.shaderMode, runtime.viewMode);
    renderCurrentView();
    return true;
  },
};

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initEngine);
} else {
  initEngine();
}
