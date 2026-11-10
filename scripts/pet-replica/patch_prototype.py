#!/usr/bin/env python3
"""从现有 3D 原型 pet-3d.html 生成复刻模型专用副本。

用法：python3 patch_prototype.py <原型 pet-3d.html> <输出 html>

改动：
- 模型改读同目录的 replica-model.b64.txt；
- 删除 Q 版比例缩放、程序添加的鲸鱼部件与按材质名换色（复刻模型自带这些内容）；
- 测量改用模型自带的 headEnd 骨骼与脚底高度，坐姿高度按裙摆设定；
- 尾巴由模型自带的弹簧骨骼驱动，原型只摆动 tailRoot 骨骼并在坐下时把尾巴抬到座面；
- 举手类姿势把上臂前伸，避开长发；道具与特效位置按头部中心重新对位。
"""
import sys

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()


def rep(old, new, count=1):
    global s
    assert s.count(old) >= 1, 'not found: ' + old[:80]
    s = s.replace(old, new) if count == 0 else s.replace(old, new, count)


def cut(start, end, new=''):
    """删除 start（含）到 end（不含）之间的内容。"""
    global s
    a = s.index(start)
    b = s.index(end, a)
    s = s[:a] + new + s[b:]


rep("const MODEL_URL = 'base-model.b64.txt';", "const MODEL_URL = 'replica-model.b64.txt';")
rep('<button id="tChibi" aria-pressed="true">Q 版比例</button>', '')
rep('<button id="tParts" aria-pressed="true">鲸鱼部件</button>', '')
cut('模型：VRM 官方样例', '</p>',
    '模型：按设定图用 Blender 脚本建模的鲸鱼女仆复刻版（VRM 1.0，开发测试形象，不随应用分发）。Q 版比例、鲸鱼部件、配色都在模型里，原型不再做改造。')
rep("const opt={phys:true,trans:true,chibi:true,parts:true,outline:true,slow:false};",
    "const opt={phys:true,trans:true,outline:true,slow:false};")

# 姿势：举手与欢呼时上臂前伸，手臂从长发前方伸出
rep("standRaise:{sit:0,b:{leftUpperArm:[0,-.1,-1.25],leftLowerArm:[0,-.2,0],rightUpperArm:[0,.15,-1.15],rightLowerArm:[0,0,-.35],head:[-.05,0,-.12]}},",
    "standRaise:{sit:0,b:{leftUpperArm:[0,-.35,-1.2],leftLowerArm:[0,-.5,0],rightUpperArm:[0,.95,-.95],rightLowerArm:[0,.3,-.35],head:[-.05,0,-.12]}},")
rep("standCheer:{sit:0,b:{leftUpperArm:[0,0,1.05],leftLowerArm:[0,0,.35],rightUpperArm:[0,0,-1.05],rightLowerArm:[0,0,-.35],head:[-.12,0,0]}},",
    "standCheer:{sit:0,b:{leftUpperArm:[0,-.95,.95],leftLowerArm:[0,-.3,.35],rightUpperArm:[0,.95,-.95],rightLowerArm:[0,.3,-.35],head:[-.12,0,0]}},")

# 加载后不再改造模型
rep("restyle();parts=buildParts();props=buildProps();\n  applyChibi();", "restyle();props=buildProps();\n  measure();")
cut("// 换色：头发按亮度映射", "const savedMats=new Map();")
cut("function restyle(){", "function setOutline(on)",
    "function restyle(){for(const m of vrm.materials||[])savedMats.set(m,m.outlineWidthFactor)}\n")
cut("// 头部与身体比例：放大头", "let anchor=new THREE.Vector3(), petScale=1;",
    """function rawBone(n){return vrm.humanoid.getRawBoneNode(n)}
function normBone(n){return vrm.humanoid.getNormalizedBoneNode(n)}
// 在 T 姿势下测量：头顶取 headEnd 骨骼，脚底在原点，坐姿高度取裙摆荷叶边上沿
let tailRoot=null, tailRest=null;
function measure(){
  for(const n of BONES){const b=normBone(n);if(b)b.quaternion.identity()}
  vrm.humanoid.update();pet.position.set(0,0,0);pet.rotation.set(0,0,0);pet.scale.setScalar(1);vrm.scene.updateMatrixWorld(true);
  measures.top=vrm.scene.getObjectByName('headEnd').getWorldPosition(V).y;
  measures.hips=rawBone('hips').getWorldPosition(V).y;
  measures.head=rawBone('head').getWorldPosition(V2).y;
  measures.sole=0;measures.seat=measures.hips-.085;
  tailRoot=vrm.scene.getObjectByName('tailRoot');tailRest=tailRoot.quaternion.clone();
  vrm.springBoneManager?.setInitState();vrm.springBoneManager?.reset();
  layoutPet();
}
""")
rep("function layoutPet(){if(!vrm||!H)return;const targetPx=opt.chibi?250:270;",
    "function layoutPet(){if(!vrm||!H)return;const targetPx=236;")
rep("petScale=1;anchor=screenToWorld(W*.72,H-18-86+4)}", "petScale=1;anchor=screenToWorld(W*.66,H-18-86+4)}")
cut("// ---------- 鲸鱼部件：尾巴、鲸鳍耳、头饰 ----------", "// ---------- 道具：饭碗、筷子、工具面板 ----------",
    """function toonMat(color){const g=new THREE.DataTexture(new Uint8Array([90,90,90,255,255,255,255,255]),2,1);g.needsUpdate=true;g.minFilter=g.magFilter=THREE.NearestFilter;
  return new THREE.MeshToonMaterial({color,gradientMap:g})}
function outlined(geo,mat,w=.012){const g=new THREE.Group();const m=new THREE.Mesh(geo,mat);g.add(m);
  const o=new THREE.Mesh(geo,new THREE.MeshBasicMaterial({color:0x101830,side:THREE.BackSide}));o.scale.multiplyScalar(1+w*8);o.userData.outline=true;g.add(o);return g}

""")
rep("const seat=measures.hips-.05*(opt.chibi?.62:1), sole=measures.sole;", "const seat=measures.seat, sole=measures.sole;")
rep("const headW=rawBone('head').getWorldPosition(V);",
    "const headW=rawBone('head').getWorldPosition(V);headW.y+=.2*petScale; // 头骨在颈根，特效与道具按头部中心对位")
# 尾巴：摆动 tailRoot，弹簧骨骼带出滞后；坐下时抬起尾巴
cut("  // 鲸鱼部件的弹簧：尾巴波动", "  // 更新 VRM：骨骼、视线、表情",
    """  // 尾巴：绕竖直轴摆动 tailRoot，弹簧骨骼带出逐节滞后；坐下时把尾巴抬到座面
  if(tailRoot){
    const wag=stateKey==='done'?1.9:stateKey==='generating'?1.3:stateKey==='sleep'?.35:.8;
    const amp=opt.phys?(stateKey==='done'?.22:stateKey==='sleep'?.05:.12):0;
    Q.setFromEuler(E.set(.55*sitAmt,amp*Math.sin(clock*2.4*wag),0));
    tailRoot.parent.getWorldQuaternion(Q2);
    tailRoot.quaternion.copy(Q2).invert().multiply(pet.quaternion).multiply(Q).multiply(Q2.copy(pet.quaternion).invert()).multiply(tailRoot.parent.getWorldQuaternion(new THREE.Quaternion())).multiply(tailRest);
  }
""")
rep("props.bowl.position.copy(lh).add(V2.set(-.03*petScale,-.02*petScale,.05*petScale));",
    "props.bowl.position.copy(lh).add(V2.set(-.04*petScale,-.015*petScale,.06*petScale));")
rep("const mouth=headW.clone().add(V2.set(0,.02*petScale,.12*petScale));",
    "const mouth=headW.clone().add(V2.set(0,-.16*petScale,.2*petScale).applyQuaternion(pet.quaternion));")
rep("props.panel.scale.setScalar(petScale*1.1);props.panel.position.set(headW.x-.02*petScale,headW.y-.42*petScale,headW.z+.42*petScale);",
    "props.panel.scale.setScalar(petScale*.8);props.panel.position.set(headW.x-.02*petScale,headW.y-.36*petScale,headW.z+.36*petScale);")
rep("props.bowl.scale.setScalar(petScale*1.0);", "props.bowl.scale.setScalar(petScale*.8);")
rep("props.chop.scale.setScalar(petScale);", "props.chop.scale.setScalar(petScale*.8);")
# 角色首次摆到锚点或布局变化时，位置会瞬移几米；先重置弹簧骨骼，避免长发与裙摆被甩飞
rep("  vrm.humanoid.update();vrm.lookAt?.update(dt);",
    "  vrm.humanoid.update();if(springReset){pet.updateMatrixWorld(true);vrm.springBoneManager?.reset();springReset=false}vrm.lookAt?.update(dt);")
rep("let anchor=new THREE.Vector3(), petScale=1;", "let anchor=new THREE.Vector3(), petScale=1, springReset=true;")
rep("petScale=1;anchor=screenToWorld(W*.66,H-18-86+4)}", "petScale=1;anchor=screenToWorld(W*.66,H-18-86+4);springReset=true}")
rep("bindToggle('tChibi','chibi',()=>vrm&&applyChibi());\n", "")
rep("bindToggle('tParts','parts',()=>parts&&setPartsVisible(opt.parts));\n", "")
rep("const hs=worldToScreen(rawBone('head').getWorldPosition(V));for(let i=0;i<3;i++)",
    "const hs=worldToScreen(rawBone('head').getWorldPosition(V).setY(V.y+.2));for(let i=0;i<3;i++)")
# 供截图用：按状态名切换并停掉随机眨眼
rep("let simTimers=[];", "window.petDebug={setState:k=>{stopSim();setState(k)},get t(){return stateT}};\nlet simTimers=[];")
if len(sys.argv) > 3 and sys.argv[3] == '--v2':
    # 图生网格转出的平涂模型：受光面颜色应等于贴图颜色，主光与环境光合计约 1
    rep("new THREE.DirectionalLight(0xffffff,Math.PI*.95)", "new THREE.DirectionalLight(0xffffff,Math.PI*.72)")
    rep("scene.add(new THREE.AmbientLight(0xffffff,.5));", "scene.add(new THREE.AmbientLight(0xffffff,.28));")
    # 头大手短：举手和欢呼改成向外上方伸，手才不会埋进头里
    rep("standRaise:{sit:0,b:{leftUpperArm:[0,-.35,-1.2],leftLowerArm:[0,-.5,0],rightUpperArm:[0,.95,-.95],rightLowerArm:[0,.3,-.35],head:[-.05,0,-.12]}},",
        "standRaise:{sit:0,b:{leftUpperArm:[0,-.35,-1.2],leftLowerArm:[0,-.5,0],rightUpperArm:[0,.35,-.55],rightLowerArm:[0,.2,-.5],head:[-.05,0,-.12]}},")
    rep("standCheer:{sit:0,b:{leftUpperArm:[0,-.95,.95],leftLowerArm:[0,-.3,.35],rightUpperArm:[0,.95,-.95],rightLowerArm:[0,.3,-.35],head:[-.12,0,0]}},",
        "standCheer:{sit:0,b:{leftUpperArm:[0,-.3,.6],leftLowerArm:[0,-.15,.45],rightUpperArm:[0,.3,-.6],rightLowerArm:[0,.15,-.45],head:[-.12,0,0]}},")
    rep("模型：按设定图用 Blender 脚本建模的鲸鱼女仆复刻版", "模型：Pixal3D 图生网格经减面、平涂、绑定转出的鲸鱼女仆")
open(dst, 'w', encoding='utf-8').write(s)
print('patched ->', dst)
