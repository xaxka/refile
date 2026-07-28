# ===========================================================================
# refile ProGuard / R8 规则
# ===========================================================================
# 通用：保留泛型签名、注解、内部类、异常表，供反射式库（Retrofit / 序列化 / Hilt）使用。
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations

# ---------------------------------------------------------------------------
# kotlinx.serialization
# @Serializable 类的 $$serializer 由序列化插件生成，反序列化依赖其 serialDesc
# （含字段名 / @SerialName 映射）。R8 若裁剪会导致 JSON 解析失败。
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

# 保留 Json companion 与 serializer(...) 查找入口
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留所有 @Serializable 生成的 $$serializer 及其 descriptor（含字段名）
-keep,includedescriptorclasses class **$$serializer { *; }

# 保留 @Serializable 类的字段名与 companion，避免序列化字段被重命名导致映射丢失
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <fields>;
    *** Companion;
}

# 枚举序列化器：保留枚举常量
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Retrofit / OkHttp
# Retrofit 服务接口用注解描述 HTTP 调用，运行时由动态代理反射读取；返回类型的泛型签名
# 用于构造 Converter。OkHttp 平台检测走反射。
# ---------------------------------------------------------------------------
-keep, allowobfuscation, allowshrinking interface retrofit2.Call
-keep, allowobfuscation, allowshrinking class retrofit2.Response
-keep, allowobfuscation, allowshrinking class kotlin.coroutines.Continuation

# 保留 @retrofit2.http.* 标注的服务接口及其方法签名
-keep, allowobfuscation, allowshrinking @retrofit2.http.* interface *
-keepclassmembers, allowshrinking, allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Retrofit Converter（kotlinx.serialization）反射构造
-keep class retrofit2.KotlinExtensions { *; }
-keep class retrofit2.Converter$Factory { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.internal.platform.**
-keepnames class okhttp3.internal.platform.* { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Hilt / Dagger
# Hilt 生成的注入代码由插件规则覆盖；但 @HiltWorker 由 HiltWorkerFactory 反射实例化，
# 需保留 Worker 子类的构造器。
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep, allowobfuscation @dagger.hilt.android.HiltAndroidApp class *
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}
-keep class * extends androidx.work.WorkerFactory { *; }
-dontwarn dagger.hilt.**

# ---------------------------------------------------------------------------
# Room
# Room 运行时 AAR 已自带 consumer rules；此处仅兜底保留 @Entity 字段（DAO 生成代码按字段名
# 读写 Cursor）与 RoomDatabase 子类。
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers @androidx.room.Entity class * { <fields>; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Kotlin 协程 / 反射元数据
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.coroutines.Continuation { *; }
-keep class kotlin.coroutines.Continuation

# ---------------------------------------------------------------------------
# Coil 3（图片加载，含 OkHttp 网络层）
# ---------------------------------------------------------------------------
-dontwarn coil3.**

# ---------------------------------------------------------------------------
# dav4jvm（:core 依赖，WebDAV 客户端，使用 XML 解析）
# ---------------------------------------------------------------------------
-dontwarn at.bitfire.dav4jvm.**
-keep class at.bitfire.dav4jvm.** { *; }

# ---------------------------------------------------------------------------
# 应用自身：被反射 / 序列化间接引用的 data class 保持字段名
# （TmdbDtos 等已在 :core 经 @Serializable 规则覆盖；此处兜底 app 模块内的备份/缓存模型）
# ---------------------------------------------------------------------------
-keep class xa.refile.data.backup.** { *; }
-keep class xa.refile.data.db.** { *; }
