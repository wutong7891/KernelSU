# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# ---------------------------------------------------------------
# 仙鹤定制 · 混淆强化
#
# 原则：只保留「运行时靠字符串名字查找」的东西，
# 其余（含 ui.customize 包本体）全部交给 R8 压平改名。
# ---------------------------------------------------------------

# 类名压平到默认包，包结构不再泄露
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# 去掉调试信息
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# JNI：C++ 侧按名字找方法，名字必须留
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# KernelSU 的 supercall 桥接入口
-keep class me.weishu.kernelsu.Natives { *; }
-keep class me.weishu.kernelsu.Natives$* { *; }

# Compose 靠 @Composable 的签名分发，方法体可混淆但签名要留
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Kotlin 数据类的合成方法
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlin.Metadata <fields>;
}

# 反射/序列化需要的元数据
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 移除日志调用，减少运行时痕迹
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# 自定义混淆字典：默认的 a/b/c 一眼可辨，换成随机词更像原生符号
-classobfuscationdictionary obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt
-obfuscationdictionary obfuscation-dictionary.txt

# 反射里用到的系统类名放在字符串常量里，
# -adaptclassstrings 会把它们一起改掉导致崩溃，所以不启用那条规则。
# 下面这些是通过 Class.forName 访问的系统类，名字必须原样保留。
-keep class android.os.SystemProperties { *; }
-keep class com.samsung.android.feature.SemFloatingFeature { *; }
-dontwarn android.os.SystemProperties
-dontwarn com.samsung.android.feature.**
