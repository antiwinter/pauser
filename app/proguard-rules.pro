# OpenTune

# QuickJS JNI callbacks are called by name from C — must not be renamed or removed
-keep class com.opentune.provider.js.QuickJsEngine {
    @androidx.annotation.Keep *;
}

# javax.el and org.ietf.jgss are not present on Android; referenced only by
# net.engio:mbassy (transitive dep of smbj) code paths that are never called.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

# Ktor IntellijIdeaDebugDetector; java.lang.management is not on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
