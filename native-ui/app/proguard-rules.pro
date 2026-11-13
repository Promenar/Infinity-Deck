# Infinity Deck Native ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlinx.serialization.** { *; }

# 保留应用栈帧的包归属和异常类型名；类及普通成员仍可混淆、优化和裁剪。
-keeppackagenames com.promenar.infinity,com.promenar.infinity.**
-keepnames class com.promenar.infinity.** extends java.lang.Throwable

# 发行版只剥离普通调试输出，logError 的私有诊断写入必须保留。
-assumenosideeffects class com.promenar.infinity.utils.InfinityLogger {
    public void log(java.lang.String);
    public void metro(java.lang.String, java.lang.String);
}

# 精确覆盖统一日志器内部使用的平台日志重载，避免未来残留调用进入 Release。
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String, java.lang.Throwable);
}

# Release 禁止直接打印异常堆栈；源码契约测试同时禁止业务源码出现该调用。
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# XMLBeans/OOXML 类型系统入口会根据 .xsb 元数据加载。
# 不得全成员 keep wordprocessingml：poi-ooxml-lite 有意省略的高级 schema
# 仍出现在部分接口签名中，全 keep 会将这些不可达方法错误保活。
# Log4j 的 AbstractLogger 会反射实例化以下内建消息工厂；R8 无法静态识别该调用。
# 仅保留三个反射目标的 public 无参构造器并禁止类抽象化，类名仍允许混淆。
-keepclassmembers,allowobfuscation class org.apache.logging.log4j.message.DefaultFlowMessageFactory {
    public <init>();
}
-keepclassmembers,allowobfuscation class org.apache.logging.log4j.message.ReusableMessageFactory {
    public <init>();
}
-keepclassmembers,allowobfuscation class org.apache.logging.log4j.message.ParameterizedMessageFactory {
    public <init>();
}

# Commons Compress 的 ExtraFieldUtils 会反射实例化内建 ZipExtraField 实现。
# 仅保留该接口实现的 public 无参构造器，允许类名混淆；不保留其它成员。
-keepclassmembers,allowobfuscation class org.apache.commons.compress.archivers.zip.** implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}

-keep,allowoptimization class org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder { *; }
-keepnames class org.openxmlformats.schemas.wordprocessingml.x2006.main.**
-keep,allowoptimization class org.openxmlformats.schemas.wordprocessingml.x2006.main.impl.** {
    public <init>(org.apache.xmlbeans.SchemaType);
}
-keep,allowoptimization class org.openxmlformats.schemas.officeDocument.x2006.relationships.** { *; }

# 以下类型均属于依赖库的可选集成，Infinity Deck 的 Android 文本提取路径不可达：
# Log4j OSGi 发现注解、PDFBox JP2 图像解码、POI AWT 桌面渲染、
# XMLBeans StAX/Saxon XPath/XQuery，以及 SLF4J 的可选静态 binder。
# 保持精确类名，严禁扩大为整库或全局 -dontwarn。
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn com.gemalto.jp2.JP2Decoder
# Commons Compress 的 Zstandard 集成是可选的，听写模型直接使用 bzip2 解码。
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn java.awt.Color
# poi-scratchpad（旧版 .doc）的 EscherMetafileBlip 只在读取嵌入图元文件尺寸时引用，正文抽取不经过。
-dontwarn java.awt.Dimension
-dontwarn java.awt.Rectangle
-dontwarn java.awt.color.ColorSpace
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.Dimension2D
-dontwarn java.awt.geom.Path2D
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ColorModel
-dontwarn java.awt.image.ComponentColorModel
-dontwarn java.awt.image.DirectColorModel
-dontwarn java.awt.image.IndexColorModel
-dontwarn java.awt.image.PackedColorModel
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn net.sf.saxon.Configuration
-dontwarn net.sf.saxon.dom.DOMNodeWrapper
-dontwarn net.sf.saxon.dom.DocumentWrapper
-dontwarn net.sf.saxon.dom.NodeOverNodeInfo
-dontwarn net.sf.saxon.lib.ConversionRules
-dontwarn net.sf.saxon.ma.map.HashTrieMap
-dontwarn net.sf.saxon.om.GroundedValue
-dontwarn net.sf.saxon.om.Item
-dontwarn net.sf.saxon.om.NamespaceUri
-dontwarn net.sf.saxon.om.NodeInfo
-dontwarn net.sf.saxon.om.Sequence
-dontwarn net.sf.saxon.om.SequenceTool
-dontwarn net.sf.saxon.om.StructuredQName
-dontwarn net.sf.saxon.query.DynamicQueryContext
-dontwarn net.sf.saxon.query.StaticQueryContext
-dontwarn net.sf.saxon.query.XQueryExpression
-dontwarn net.sf.saxon.str.StringView
-dontwarn net.sf.saxon.str.UnicodeString
-dontwarn net.sf.saxon.sxpath.IndependentContext
-dontwarn net.sf.saxon.sxpath.XPathDynamicContext
-dontwarn net.sf.saxon.sxpath.XPathEvaluator
-dontwarn net.sf.saxon.sxpath.XPathExpression
-dontwarn net.sf.saxon.sxpath.XPathStaticContext
-dontwarn net.sf.saxon.sxpath.XPathVariable
-dontwarn net.sf.saxon.tree.wrapper.VirtualNode
-dontwarn net.sf.saxon.type.BuiltInAtomicType
-dontwarn net.sf.saxon.type.ConversionResult
-dontwarn net.sf.saxon.value.AnyURIValue
-dontwarn net.sf.saxon.value.AtomicValue
-dontwarn net.sf.saxon.value.BigDecimalValue
-dontwarn net.sf.saxon.value.BigIntegerValue
-dontwarn net.sf.saxon.value.BooleanValue
-dontwarn net.sf.saxon.value.CalendarValue
-dontwarn net.sf.saxon.value.DateTimeValue
-dontwarn net.sf.saxon.value.DateValue
-dontwarn net.sf.saxon.value.DoubleValue
-dontwarn net.sf.saxon.value.DurationValue
-dontwarn net.sf.saxon.value.FloatValue
-dontwarn net.sf.saxon.value.GDateValue
-dontwarn net.sf.saxon.value.GDayValue
-dontwarn net.sf.saxon.value.GMonthDayValue
-dontwarn net.sf.saxon.value.GMonthValue
-dontwarn net.sf.saxon.value.GYearMonthValue
-dontwarn net.sf.saxon.value.GYearValue
-dontwarn net.sf.saxon.value.HexBinaryValue
-dontwarn net.sf.saxon.value.Int64Value
-dontwarn net.sf.saxon.value.ObjectValue
-dontwarn net.sf.saxon.value.QNameValue
-dontwarn net.sf.saxon.value.SaxonDuration
-dontwarn net.sf.saxon.value.SaxonXMLGregorianCalendar
-dontwarn net.sf.saxon.value.StringValue
-dontwarn net.sf.saxon.value.TimeValue
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.slf4j.impl.StaticLoggerBinder

# JSch 2.28.8 固定算法表的反射保留规则。
# 涵盖 Ed25519、RSA 4096 生成，OpenSSH v1、PEM、PKCS8 私钥导入与加密私钥解密；不支持 PuTTY。
# 使用前须固定 server_host_key、PubkeyAcceptedAlgorithms、CheckSignatures 为裸 Ed25519/ECDSA/RSA-SHA2。
# 同时固定网络 KEX/cipher/MAC/auth/compression，禁用证书、GSS、压缩、agent。
# 私钥导入须显式验证 cipher 为下列 AES-CTR/GCM，kdf 为 bcrypt 或明文 none。
# 类名与构造通过 JSch 配置字符串反射解析；不对全包保留，不抑制 missing-class 警告。

# KEX：curve25519-sha256、ecdh-sha2-nistp256、diffie-hellman-group14-sha256。
-keep class com.jcraft.jsch.DH25519 { *; }
-keep class com.jcraft.jsch.DHEC256 { *; }
-keep class com.jcraft.jsch.DHG14256 { *; }
-keep class com.jcraft.jsch.bc.XDH { *; }
-keep class com.jcraft.jsch.jce.ECDHN { *; }
-keep class com.jcraft.jsch.jce.DH { *; }

# 主机和用户签名，RSA 只使用 SHA2，ECDSA 的摘要由标准 JCA 内部选择。
-keep class com.jcraft.jsch.bc.SignatureEd25519 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA256 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA384 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA521 { *; }
-keep class com.jcraft.jsch.jce.SignatureRSASHA256 { *; }
-keep class com.jcraft.jsch.jce.SignatureRSASHA512 { *; }

# 密钥生成、指纹、KEX 摘要及加盐随机源。
-keep class com.jcraft.jsch.bc.KeyPairGenEdDSA { *; }
-keep class com.jcraft.jsch.jce.KeyPairGenRSA { *; }
-keep class com.jcraft.jsch.jce.KeyPairGenECDSA { *; }
-keep class com.jcraft.jsch.jce.SHA256 { *; }
-keep class com.jcraft.jsch.jce.Random { *; }

# 网络及 OpenSSH v1 私钥的 AES-CTR/GCM；AES192CTR 仅用于私钥导入。
-keep class com.jcraft.jsch.jce.AES128CTR { *; }
-keep class com.jcraft.jsch.jce.AES192CTR { *; }
-keep class com.jcraft.jsch.jce.AES256CTR { *; }
-keep class com.jcraft.jsch.jce.AES128GCM { *; }
-keep class com.jcraft.jsch.jce.AES256GCM { *; }

# 网络 MAC。
-keep class com.jcraft.jsch.jce.HMACSHA256 { *; }
-keep class com.jcraft.jsch.jce.HMACSHA512 { *; }
-keep class com.jcraft.jsch.jce.HMACSHA256ETM { *; }
-keep class com.jcraft.jsch.jce.HMACSHA512ETM { *; }

# OpenSSH v1 加密私钥的 bcrypt KDF；它不是 PKCS8 的 PBKDF2。
-keep class com.jcraft.jsch.jbcrypt.JBCrypt { *; }

# 加密私钥解密：KeyPair.genKey 对任何格式都先反射创建 md5，缺失时 decrypt 吞掉异常只返回 false（表现为口令错误）。
-keep class com.jcraft.jsch.jce.MD5 { *; }
# 加密 PEM（DEK-Info）与加密 PKCS8 的 CBC 解密及 PBKDF2、scrypt 口令派生。
-keep class com.jcraft.jsch.jce.AES128CBC { *; }
-keep class com.jcraft.jsch.jce.AES192CBC { *; }
-keep class com.jcraft.jsch.jce.AES256CBC { *; }
-keep class com.jcraft.jsch.jce.TripleDESCBC { *; }
-keep class com.jcraft.jsch.jce.PBKDF2 { *; }
-keep class com.jcraft.jsch.bc.SCrypt { *; }

# JSch 总会先反射创建 UserAuthNone，随后才进入固定的 publickey/password。
-keep class com.jcraft.jsch.UserAuthNone { *; }
-keep class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep class com.jcraft.jsch.UserAuthPassword { *; }

# 上列 KEX 与认证类多为包内私有，由 Session 等同包类反射实例化。R8 会把可改名的同包类
# 搬到其它包（实测 Session 被移出），跨包反射抛 IllegalAccessException，Release 握手必然失败。
# 顶层包（不含 jce/bc 子包）保留类名与成员、不做优化，未使用的类仍可裁剪。
-keep,allowshrinking class com.jcraft.jsch.* { *; }


-keep class com.jcraft.jsch.bc.ChaCha20Poly1305 { *; }


# sherpa-onnx JNI 按 Kotlin 类名和成员名读取配置，并构造识别结果。
-keep class com.k2fsa.sherpa.onnx.** { *; }
# LlamaContext JNI 通过导出符号绑定类名和 native 方法名。
-keep,allowshrinking class com.promenar.infinity.data.local.inference.LlamaContext { native <methods>; }
-keep,allowshrinking class com.promenar.infinity.data.local.inference.LlamaContext$Companion { native <methods>; }
